package com.aieducenter.aiplatform.business.identity.application;

import java.security.SecureRandom;
import java.time.Clock;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.business.identity.application.dto.response.CodeSentResponse;
import com.aieducenter.aiplatform.business.identity.application.dto.response.MeResponse;
import com.aieducenter.aiplatform.business.identity.domain.aggregate.Account;
import com.aieducenter.aiplatform.business.identity.domain.enums.AccountBindingProvider;
import com.aieducenter.aiplatform.business.identity.domain.error.IdentityMessage;
import com.aieducenter.aiplatform.business.identity.domain.model.AuthCookies;
import com.aieducenter.aiplatform.business.identity.domain.model.OauthTransaction;
import com.aieducenter.aiplatform.business.identity.domain.model.VerificationTarget;
import com.aieducenter.aiplatform.business.identity.domain.port.VerificationCodeSender;
import com.aieducenter.aiplatform.business.identity.infrastructure.code.VerificationCodeStore;
import com.aieducenter.aiplatform.business.identity.infrastructure.oidc.SsoProperties;
import com.aieducenter.aiplatform.business.identity.infrastructure.session.BffSession;
import com.aieducenter.aiplatform.business.identity.infrastructure.session.BffSessionStore;

/**
 * 验证码登录用例（#327，M01 弹窗后端链路）：平台内自建发码/验码/建账户/种会话
 * ——无密码、登录即注册（验码即 upsert，不存在静默建账户）；不走 identity 侧
 * 加通道。会话与 OIDC 流同口种（BffSessionStore 落 {@code idn_sessions}，#329 起
 * 持久化；token 三件套无值，code 登录无 token 语义）。
 *
 * <p>守卫序（发码）：形态（IDN_005）→ 通道开关（IDN_006，机制双开上线先邮箱）
 * → 频控（IDN_007，冷却窗）→ 落码后经端口发出。验码（IDN_008）统一口径
 * 「验证码错误或已过期」——错码/过期/作废/无在途码不区分，不泄漏哪个环节。</p>
 */
@Service
public class CodeLoginAppService {

    private final VerificationCodeProperties properties;
    private final VerificationCodeStore codeStore;
    private final VerificationCodeSender sender;
    private final AccountAppService accountAppService;
    private final GuestBindingAppService guestBindingAppService;
    private final BffSessionStore sessionStore;
    private final SsoProperties ssoProperties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public CodeLoginAppService(VerificationCodeProperties properties, VerificationCodeStore codeStore,
            VerificationCodeSender sender, AccountAppService accountAppService,
            GuestBindingAppService guestBindingAppService, BffSessionStore sessionStore,
            SsoProperties ssoProperties) {
        this(properties, codeStore, sender, accountAppService, guestBindingAppService, sessionStore,
                ssoProperties, Clock.systemUTC());
    }

    CodeLoginAppService(VerificationCodeProperties properties, VerificationCodeStore codeStore,
            VerificationCodeSender sender, AccountAppService accountAppService,
            GuestBindingAppService guestBindingAppService, BffSessionStore sessionStore,
            SsoProperties ssoProperties, Clock clock) {
        this.properties = properties;
        this.codeStore = codeStore;
        this.sender = sender;
        this.accountAppService = accountAppService;
        this.guestBindingAppService = guestBindingAppService;
        this.sessionStore = sessionStore;
        this.ssoProperties = ssoProperties;
        this.clock = clock;
    }

    /**
     * 发码：落码（先占频控与挑战位）再经端口发出——发送侧故障时码已在途、
     * 用户冷却窗后重发即可；反序会出现「码已收到但无处可验」的更差现场。
     */
    public CodeSentResponse sendCode(String rawTarget) {
        VerificationTarget target = parseTarget(rawTarget);
        if (!channelEnabled(target.channel())) {
            throw new ApplicationException(IdentityMessage.VERIFICATION_CHANNEL_DISABLED);
        }
        if (codeStore.inCooldown(target.channel(), target.identifier(), clock.instant())) {
            throw new ApplicationException(IdentityMessage.VERIFICATION_SEND_TOO_FREQUENT);
        }
        String code = sixDigitCode();
        codeStore.issue(target.channel(), target.identifier(), code, clock.instant());
        sender.send(target.channel(), target.identifier(), code);
        return new CodeSentResponse(properties.getCodeTtlSeconds());
    }

    /**
     * 验码登录：验过即消费（一次性）→ 登录即注册 upsert（绑定表）→ 种会话 →
     * 登录即自动平移（#328：请求携带有效访客凭证即把访客未绑定项目过户给账户，
     * 幂等；过期/无效凭证仅清 cookie 不平移，不阻断登录）。会话 cookie 契约与
     * OIDC 流同源（aiplatform_session；Secure 随 sso.cookie-secure——同契约同
     * 开关，两处装配形见 AuthAppService）。
     */
    public CodeLoginCompletion verifyAndLogin(String rawTarget, String code,
            String guestCredential) {
        VerificationTarget target = parseTarget(rawTarget);
        if (code == null || code.isBlank()
                || !codeStore.verifyAndConsume(target.channel(), target.identifier(), code,
                        clock.instant())) {
            throw new ApplicationException(IdentityMessage.VERIFICATION_CODE_MISMATCH);
        }

        Account account = accountAppService.loginByBinding(target.channel(), target.identifier());

        String sessionId = OauthTransaction.randomToken();
        sessionStore.put(sessionId, new BffSession(
                account.getId(), account.getDisplayName(), null, null, null));

        GuestBindingAppService.BindingOutcome binding =
                guestBindingAppService.bindOnLogin(guestCredential, account.getId());

        return new CodeLoginCompletion(
                new MeResponse(account.getId().toString(), account.getDisplayName()),
                sessionCookie(sessionId), binding.guestClearCookie());
    }

    private VerificationTarget parseTarget(String rawTarget) {
        return VerificationTarget.parse(rawTarget)
                .orElseThrow(() -> new ApplicationException(IdentityMessage.VERIFICATION_TARGET_INVALID));
    }

    private boolean channelEnabled(AccountBindingProvider channel) {
        return switch (channel) {
            case EMAIL -> properties.isEmailChannelEnabled();
            case PHONE -> properties.isPhoneChannelEnabled();
            case OIDC -> false; // oidc 非收码通道（302 流走 AuthAppService）
        };
    }

    private String sixDigitCode() {
        return "%06d".formatted(random.nextInt(1_000_000));
    }

    private ResponseCookie sessionCookie(String sessionId) {
        return ResponseCookie.from(AuthCookies.SESSION_COOKIE_NAME, sessionId)
                .httpOnly(true).secure(ssoProperties.isCookieSecure()).sameSite("Lax")
                .path("/")
                .build();
    }

    /** 登录完成：账号面（MeResponse 同形）＋会话 cookie＋清访客凭证 cookie（#328 绑定平移后置；无凭证为 null，端点层据此落 Set-Cookie） */
    public record CodeLoginCompletion(MeResponse account, ResponseCookie sessionCookie,
            ResponseCookie guestClearCookie) {
    }
}
