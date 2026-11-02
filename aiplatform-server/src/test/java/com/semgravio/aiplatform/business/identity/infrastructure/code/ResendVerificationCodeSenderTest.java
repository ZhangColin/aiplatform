package com.semgravio.aiplatform.business.identity.infrastructure.code;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.semgravio.aiplatform.business.identity.application.VerificationCodeProperties;
import com.semgravio.aiplatform.business.identity.domain.enums.AccountBindingProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

/**
 * Resend 发信适配器（#376 S1，端口层）：出站 HTTP 造假（MockRestServiceServer，
 * 先例 OidcClientTest）——只对外部可见行为断言：请求形态（发信端点、Bearer 鉴权头、
 * from/to、正文三段式）与错误如实抛出；Resend 侧响应体除错误细节外不解析。
 */
class ResendVerificationCodeSenderTest {

    private static final String EMAILS_ENDPOINT = "https://api.resend.com/emails";
    private static final String API_KEY = "re_test_key";

    private MockRestServiceServer server;
    private ResendVerificationCodeSender sender;

    @BeforeEach
    void setUp() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        server = MockRestServiceServer.bindTo(restClientBuilder).build();
        sender = new ResendVerificationCodeSender(
                resendProps("no-reply@semgravio.com"),
                verificationProps(300),
                restClientBuilder,
                new LoggingVerificationCodeSender());
    }

    @Test
    void given_email_code_when_send_then_resend_email_api_called_with_bearer_and_three_part_body() {
        server.expect(requestTo(EMAILS_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(content().json("""
                        {"from":"Semgravio <no-reply@semgravio.com>",
                         "to":"user@example.com",
                         "subject":"Semgravio 登录验证码",
                         "text":"您的验证码：482913\\n\\n5 分钟内有效。\\n\\n若非本人操作，请忽略此邮件。"}
                        """))
                .andRespond(withSuccess("{\"id\":\"email-1\"}", MediaType.APPLICATION_JSON));

        sender.send(AccountBindingProvider.EMAIL, "user@example.com", "482913");

        server.verify();
    }

    @Test
    void given_ttl_configured_120s_when_send_then_body_renders_2_minutes_not_hardcoded() {
        // 「N 分钟内有效」从验证码 TTL 配置渲染（AC：不写死）——TTL 120s ≠ 300s 缺省，
        // 分钟数必须跟着变，钉死文案与配置不漂移
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer ttlServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        ResendVerificationCodeSender ttlSender = new ResendVerificationCodeSender(
                resendProps("no-reply@semgravio.com"), verificationProps(120), restClientBuilder,
                new LoggingVerificationCodeSender());

        ttlServer.expect(requestTo(EMAILS_ENDPOINT))
                .andExpect(content().json("""
                        {"text":"您的验证码：000001\\n\\n2 分钟内有效。\\n\\n若非本人操作，请忽略此邮件。"}
                        """))
                .andRespond(withSuccess("{\"id\":\"email-2\"}", MediaType.APPLICATION_JSON));

        ttlSender.send(AccountBindingProvider.EMAIL, "another@example.com", "000001");

        ttlServer.verify();
    }

    @Test
    void given_resend_rejects_when_send_then_exception_carries_status_and_body() {
        // 不重试、不吞错：Resend 4xx/5xx 原样上抛，状态码与错误体带出供日志排查
        server.expect(requestTo(EMAILS_ENDPOINT))
                .andRespond(withUnauthorizedRequest()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"name\":\"validation_error\",\"message\":\"Invalid from address\"}"));

        assertThatThrownBy(() -> sender.send(AccountBindingProvider.EMAIL, "user@example.com", "482913"))
                .isInstanceOfSatisfying(ResendSendException.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(401);
                    assertThat(e.responseBody()).contains("validation_error")
                            .contains("Invalid from address");
                });
        server.verify();
    }

    @Test
    void given_resend_unreachable_when_send_then_wrapped_without_status() {
        // 连接级失败替身：无状态码/响应体（重点是 RestClientException 分支被兜住）
        server.expect(requestTo(EMAILS_ENDPOINT))
                .andRespond(request -> {
                    throw new ResourceAccessException("连接拒绝");
                });

        assertThatThrownBy(() -> sender.send(AccountBindingProvider.EMAIL, "user@example.com", "482913"))
                .isInstanceOfSatisfying(ResendSendException.class,
                        e -> assertThat(e.httpStatus()).isNull());
    }

    @Test
    void given_phone_channel_when_send_then_no_http_and_fallback_logs() {
        // 手机通道未接真发信：委派日志桩保持过渡态。MockRestServiceServer 未注册任何
        // 期望——若误打出站 HTTP，请求会被当场拦下抛错，走到 verify() 即证零 HTTP
        sender.send(AccountBindingProvider.PHONE, "13800138000", "246810");

        server.verify();
    }

    // -------- 测试工具（期望值与配置同源，勿单边改） --------

    private static ResendProperties resendProps(String from) {
        ResendProperties properties = new ResendProperties();
        properties.setApiKey(API_KEY);
        properties.setFrom(from);
        return properties;
    }

    private static VerificationCodeProperties verificationProps(long ttlSeconds) {
        VerificationCodeProperties properties = new VerificationCodeProperties();
        properties.setCodeTtlSeconds(ttlSeconds);
        return properties;
    }
}
