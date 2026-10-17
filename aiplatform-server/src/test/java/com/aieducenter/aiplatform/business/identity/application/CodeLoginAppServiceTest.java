package com.aieducenter.aiplatform.business.identity.application;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.aieducenter.aiplatform.IntegrationTest;
import com.aieducenter.aiplatform.business.identity.application.CodeLoginAppService.CodeLoginCompletion;
import com.aieducenter.aiplatform.business.identity.domain.enums.AccountBindingProvider;
import com.aieducenter.aiplatform.business.identity.domain.model.AuthCookies;
import com.aieducenter.aiplatform.business.identity.domain.port.VerificationCodeSender;
import com.aieducenter.aiplatform.business.identity.domain.repository.AccountBindingRepository;
import com.aieducenter.aiplatform.business.identity.infrastructure.session.BffSession;
import com.aieducenter.aiplatform.business.identity.infrastructure.session.BffSessionStore;

import com.cartisan.core.exception.ApplicationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * 验证码登录用例（#327，M01 后端链路）：发码（假发送器收件）→ 验码即 upsert
 * （登录即注册）→ 种会话；无密码。时间语义走参数（ttl=1s 可真等过期、冷却窗
 * 1h 钉频控），不 mock 时钟。通道开关按上线口径：邮箱开、手机关。
 */
@IntegrationTest(properties = {
        "app.verification-code.code-ttl-seconds=1",
        "app.verification-code.send-cooldown-seconds=3600"})
class CodeLoginAppServiceTest {

    @Autowired
    private CodeLoginAppService appService;

    @Autowired
    private BffSessionStore sessionStore;

    @Autowired
    private AccountBindingRepository bindingRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 假发送器：收件断言的观察点（真服务商＝上线参数，接口位在 port） */
    @MockitoBean
    private VerificationCodeSender sender;

    /** 每次运行唯一收码目标，规避冷却窗跨用例碰撞与库中历史行 */
    private final String email = "user-" + UUID.randomUUID() + "@example.com";

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM idn_account_bindings");
        jdbcTemplate.update("DELETE FROM idn_accounts");
    }

    @Test
    void given_new_email_when_send_then_code_dispatched_and_verify_logs_in_with_account_created() {
        appService.sendCode(email);

        String code = capturedCode();
        CodeLoginCompletion completion = appService.verifyAndLogin(email, code, null);

        // 登录即注册：账号静默建档（显示名缺省=标识原文）＋email 绑定行
        assertThat(completion.account().accountId()).isEqualTo(
                bindingRepository.findByProviderAndIdentifier(AccountBindingProvider.EMAIL, email)
                        .orElseThrow().getAccountId().toString());
        assertThat(completion.account().displayName()).isEqualTo(email);

        // 会话按现有机制种下（持久化归 #329）：cookie 值即 sessionId
        BffSession session = sessionStore.get(completion.sessionCookie().getValue()).orElseThrow();
        assertThat(session.accountId().toString()).isEqualTo(completion.account().accountId());
        assertThat(session.displayName()).isEqualTo(email);
        assertThat(session.idToken()).isNull(); // 无 token 语义（对偶 OIDC 流的三件套）
    }

    @Test
    void given_mixed_case_email_when_send_then_identifier_normalized() {
        String mixed = "User." + UUID.randomUUID() + "@Example.COM";

        appService.sendCode(mixed);
        String code = capturedCode();

        // 归一形态（小写）发码——同一人不同大小写输入不裂成两个收码目标
        verify(sender).send(eq(AccountBindingProvider.EMAIL), eq(mixed.toLowerCase()), eq(code));
        CodeLoginCompletion completion = appService.verifyAndLogin(mixed.toUpperCase(), code, null);
        assertThat(completion.account().displayName()).isEqualTo(mixed.toLowerCase());
    }

    @Test
    void given_same_target_within_cooldown_when_send_again_then_rate_limited() {
        appService.sendCode(email);

        assertThatThrownBy(() -> appService.sendCode(email))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.getCodeMessage().code()).isEqualTo("IDN_007"));
    }

    @Test
    void given_wrong_code_when_verify_then_mismatch_and_correct_code_still_usable() {
        appService.sendCode(email);

        assertThatThrownBy(() -> appService.verifyAndLogin(email, "000000", null))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.getCodeMessage().code()).isEqualTo("IDN_008"));

        // 未超次：正确码仍可验（一次错码不作废）
        assertThat(appService.verifyAndLogin(email, capturedCode(), null).account()).isNotNull();
    }

    @Test
    void given_exhausted_attempts_when_verify_with_correct_code_then_rejected() {
        appService.sendCode(email);
        String code = capturedCode();

        for (int i = 0; i < 5; i++) { // 缺省 max-verify-attempts=5：五次错码即作废
            assertThatThrownBy(() -> appService.verifyAndLogin(email, "000000", null))
                    .isInstanceOfSatisfying(ApplicationException.class,
                            e -> assertThat(e.getCodeMessage().code()).isEqualTo("IDN_008"));
        }

        assertThatThrownBy(() -> appService.verifyAndLogin(email, code, null))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.getCodeMessage().code()).isEqualTo("IDN_008"));
    }

    @Test
    void given_expired_code_when_verify_then_mismatch() throws InterruptedException {
        appService.sendCode(email);
        String code = capturedCode();

        Thread.sleep(1500); // ttl=1s（本测试类的参数）：真等到过期

        assertThatThrownBy(() -> appService.verifyAndLogin(email, code, null))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.getCodeMessage().code()).isEqualTo("IDN_008"));
    }

    @Test
    void given_never_sent_code_when_verify_then_mismatch() {
        assertThatThrownBy(() -> appService.verifyAndLogin(email, "123456", null))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.getCodeMessage().code()).isEqualTo("IDN_008"));
    }

    @Test
    void given_garbage_target_when_send_then_invalid_without_dispatch() {
        assertThatThrownBy(() -> appService.sendCode("not-an-email-or-phone"))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.getCodeMessage().code()).isEqualTo("IDN_005"));
        verify(sender, org.mockito.Mockito.never()).send(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void given_phone_target_when_send_then_channel_disabled() {
        // 通道机制双开、上线先邮箱：手机号可识别（phone 通道位）但开关关＝如实 IDN_006
        assertThatThrownBy(() -> appService.sendCode("13800138000"))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.getCodeMessage().code()).isEqualTo("IDN_006"));
        verify(sender, org.mockito.Mockito.never()).send(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    private String capturedCode() {
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq(AccountBindingProvider.EMAIL),
                org.mockito.ArgumentMatchers.anyString(), code.capture());
        assertThat(code.getValue()).matches("\\d{6}"); // 六位数字码（M01 输入框契约）
        return code.getValue();
    }
}
