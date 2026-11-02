package com.semgravio.aiplatform.business.project.infrastructure;

import java.lang.reflect.Constructor;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 外部资料取数口装配测试（#372）：缺省回落与 fail-fast 以装配面钉死——行为面等
 * 45s 不经济，且生产构造带真实 SSRF 判定（假服务在环回必被拒），故反射 @Value
 * 注解默认值（45s／不设代理）＋上下文双态（干净环境 bean 起＝默认可解析；脏配置
 * 启动失败＝运维显式配置错不静默直连）。形制照 VerificationCodeSenderWiringTest。
 */
class HttpExternalContentFetcherWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withBean(HttpExternalContentFetcher.class);

    @Test
    void given_no_properties_when_context_then_defaults_wire_45s_and_direct() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(HttpExternalContentFetcher.class);
            // 缺省回落（#372 Agent Brief：30–45s 取上界）：字面量钉死 45（独立于实现侧
            // DEFAULT_REQUEST_TIMEOUT_SECONDS 常量拼进注解的写法，常量漂移在此被抓住）
            Value timeout = constructorParameterAnnotation(1);
            assertThat(timeout.value())
                    .isEqualTo("${app.external-content.request-timeout-seconds:45}");
            Value proxy = constructorParameterAnnotation(2);
            assertThat(proxy.value()).isEqualTo("${app.external-content.proxy:}");
        });
    }

    @Test
    void given_request_timeout_property_when_context_then_fetcher_starts() {
        runner.withPropertyValues("app.external-content.request-timeout-seconds=1")
                .run(context -> assertThat(context).hasSingleBean(HttpExternalContentFetcher.class));
    }

    @Test
    void given_proxy_property_when_context_then_fetcher_starts_with_proxy() {
        runner.withPropertyValues("app.external-content.proxy=127.0.0.1:7890")
                .run(context -> assertThat(context).hasSingleBean(HttpExternalContentFetcher.class));
    }

    @Test
    void given_malformed_proxy_property_when_context_then_startup_fails_fast() {
        runner.withPropertyValues("app.external-content.proxy=no-port").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
        });
    }

    private static Value constructorParameterAnnotation(int index) throws NoSuchMethodException {
        Constructor<HttpExternalContentFetcher> constructor =
                HttpExternalContentFetcher.class.getDeclaredConstructor(
                        int.class, long.class, String.class);
        return constructor.getParameters()[index].getAnnotation(Value.class);
    }
}
