package com.semgravio.aiplatform.business.identity.infrastructure.code;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import com.semgravio.aiplatform.business.identity.application.VerificationCodeProperties;
import com.semgravio.aiplatform.business.identity.domain.port.VerificationCodeSender;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 发码适配器条件装配（#376 S2，web 装配层）：条件装配本身是被测行为——
 * 配 RESEND_API_KEY（属性 {@code app.verification-code.resend.api-key} 非空）
 * 则 Resend 实现为主发（@Primary，端口注入面拿到它）；缺失/空串回落既有日志桩
 * （本地开发零配置）。真实 bean 定义即生产装配件（@Conditional/@Primary 在类上），
 * 上下文用 ApplicationContextRunner 双态各跑一遍。
 */
class VerificationCodeSenderWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withUserConfiguration(LoggingVerificationCodeSender.class,
                    ResendVerificationCodeSender.class, ResendProperties.class,
                    VerificationCodeProperties.class);

    @Test
    void given_no_api_key_when_context_starts_then_logging_fallback_is_the_sender() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(VerificationCodeSender.class);
            assertThat(context).doesNotHaveBean(ResendVerificationCodeSender.class);
            assertThat(context.getBean(VerificationCodeSender.class))
                    .isInstanceOf(LoggingVerificationCodeSender.class);
        });
    }

    @Test
    void given_blank_api_key_when_context_starts_then_logging_fallback_is_the_sender() {
        // yml 占位符 ${RESEND_API_KEY:} 缺省解析为空串而非属性缺失——空串必须等价
        // 未配置（@ConditionalOnProperty 分不出空串，故自写 ResendApiKeyCondition）
        runner.withPropertyValues("app.verification-code.resend.api-key=")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ResendVerificationCodeSender.class);
                    assertThat(context.getBean(VerificationCodeSender.class))
                            .isInstanceOf(LoggingVerificationCodeSender.class);
                });
    }

    @Test
    void given_api_key_when_context_starts_then_resend_sender_is_primary_sender() {
        runner.withPropertyValues("app.verification-code.resend.api-key=re_test_key")
                .run(context -> {
                    assertThat(context).hasSingleBean(ResendVerificationCodeSender.class);
                    // 端口注入面：两 bean 并存时 @Primary 决定 CodeLoginAppService 拿到谁
                    assertThat(context.getBean(VerificationCodeSender.class))
                            .isInstanceOf(ResendVerificationCodeSender.class);
                    // 日志桩仍在（非邮箱通道的委派位），但不再是端口注入面
                    assertThat(context).hasSingleBean(LoggingVerificationCodeSender.class);
                    // 前缀绑定钉死：属性名与 ResendProperties 前缀/字段对得上（防 typo 静默失绑）
                    assertThat(context.getBean(ResendProperties.class).getApiKey())
                            .isEqualTo("re_test_key");
                });
    }
}
