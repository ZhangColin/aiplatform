package com.aieducenter.aiplatform.business.identity.application;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aieducenter.aiplatform.business.identity.domain.aggregate.Guest;
import com.aieducenter.aiplatform.business.identity.domain.model.AuthCookies;
import com.aieducenter.aiplatform.business.identity.domain.model.OauthTransaction;
import com.aieducenter.aiplatform.business.identity.domain.repository.GuestRepository;
import com.aieducenter.aiplatform.business.identity.infrastructure.oidc.SsoProperties;

/**
 * 访客用例（#326 访客域）：凭证发放（惰性创建的写入面——首条需求消息提交成功才由
 * 建项目编排调用）、无状态直查（每请求直查访客行：行存在且未作废且在保留期内即
 * 有效）＋滑动续期（写节流）＋凭证 cookie 装配。
 *
 * <p>不进内存会话 store（与 {@code BffSessionStore} 的根本分野）：有效性每请求
 * 查库自证，进程重启无感；期限与节流窗口走 {@link GuestProperties}。发放/续期
 * 的会话优先序（账户会话在场不解析访客凭证）不归本服务——归
 * {@code BffSessionContextFilter} 的装配序。</p>
 */
@Service
public class GuestAppService {

    private final GuestRepository guestRepository;
    private final GuestProperties properties;
    private final SsoProperties ssoProperties;
    private final Clock clock;

    public GuestAppService(GuestRepository guestRepository, GuestProperties properties,
            SsoProperties ssoProperties, Clock clock) {
        this.guestRepository = guestRepository;
        this.properties = properties;
        this.ssoProperties = ssoProperties;
        this.clock = clock;
    }

    /**
     * 建访客行＋发凭证（惰性创建）：事务归调用方（建项目编排的单事务——项目落库
     * 失败则访客行同滚，「提交成功才建访客行」）；凭证值即 cookie 值，由调用方经
     * {@link #credentialCookie} 落 Set-Cookie。
     */
    @Transactional
    public IssuedGuest registerNew() {
        Guest guest = guestRepository.save(
                Guest.register(OauthTransaction.randomToken(), LocalDateTime.now(clock)));
        return new IssuedGuest(guest.getId(), guest.getCredential());
    }

    /**
     * 凭证直查（无状态校验）：按凭证值唯一索引查行——行不存在/已作废/过保留期
     * 皆不认（empty，数据不删）；在保留期内且距最近活跃达节流阈值则顺带滑动续期
     * （一次写），{@code renewed} 供过滤层同步刷新浏览器侧 cookie 寿命。
     */
    public Optional<ResolvedGuest> resolve(String credential) {
        if (credential == null || credential.isBlank()) {
            return Optional.empty();
        }
        Guest guest = guestRepository.findByCredential(credential).orElse(null);
        if (guest == null) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (!guest.activeAt(now, properties.getRetentionPeriod())) {
            return Optional.empty();
        }
        boolean renewed = false;
        if (guest.renewDue(now, properties.getRenewThreshold())) {
            guest.renew(now);
            guestRepository.save(guest);
            renewed = true;
        }
        return Optional.of(new ResolvedGuest(guest.getId(), renewed));
    }

    /**
     * 凭证 cookie 装配（契约名/生命周期见 {@link AuthCookies}）：HttpOnly +
     * SameSite=Lax + Path=/；Secure 随 {@code sso.cookie-secure}（local http 下
     * Safari/Firefox 拒存 Secure cookie，与账户会话 cookie 同一取舍）；MaxAge 对齐
     * 保留期，续期时随节流写重发同值 cookie 刷新浏览器侧寿命（双端同窗）。
     */
    public ResponseCookie credentialCookie(String credential) {
        return ResponseCookie.from(AuthCookies.GUEST_COOKIE_NAME, credential)
                .httpOnly(true)
                .secure(ssoProperties.isCookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofSeconds(properties.getRetentionPeriod().toSeconds()))
                .build();
    }

    /**
     * 清凭证 cookie（#328 绑定平移）：MaxAge=0 同值清——登录完成自动平移后
     * 丢弃浏览器侧访客凭证（账户会话接管）；契约名/属性与 {@link #credentialCookie}
     * 同源（HttpOnly + SameSite=Lax + Path=/，Secure 随 sso.cookie-secure）。
     */
    public ResponseCookie clearCredentialCookie() {
        return ResponseCookie.from(AuthCookies.GUEST_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(ssoProperties.isCookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
    }

    /** 发放结果（访客行 id＋凭证值——值即凭证，cookie 由端点层落头）。 */
    public record IssuedGuest(Long guestId, String credential) {
    }

    /** 直查结果：guestId 进请求上下文访客维度；renewed＝本次触发了滑动续期写。 */
    public record ResolvedGuest(Long guestId, boolean renewed) {
    }
}
