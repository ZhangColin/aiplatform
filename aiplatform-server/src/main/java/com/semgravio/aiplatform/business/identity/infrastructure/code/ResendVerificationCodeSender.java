package com.semgravio.aiplatform.business.identity.infrastructure.code;

import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.semgravio.aiplatform.business.identity.application.VerificationCodeProperties;
import com.semgravio.aiplatform.business.identity.domain.enums.AccountBindingProvider;
import com.semgravio.aiplatform.business.identity.domain.port.VerificationCodeSender;

/**
 * Resend 发信适配器（#376）：验证码经 Resend 发信 API 真发邮件——「服务商＝上线
 * 参数」的 Resend 落地（端口 {@link VerificationCodeSender} 与登录编排零改动）。
 * 条件装配：配置了 RESEND_API_KEY 即本实现主发（@Primary），缺失回落既有日志桩
 * {@link LoggingVerificationCodeSender}（本地开发零配置）；非邮箱通道未接真发信，
 * 委派日志桩保持过渡态（手机通道开关默认关，机制位在）。
 *
 * <p>直调 Resend REST（Spring 自带 RestClient，不引 SDK）：POST /emails、
 * Bearer 鉴权、中文纯文本三段式正文——验证码＋「N 分钟内有效」（N 从验证码 TTL
 * 配置渲染，防文案与配置漂移）＋「若非本人操作，请忽略此邮件」。发信失败不重试、
 * 不吞错，异常上抛接口如实报错（demo 口径）。</p>
 */
@Component
@Primary
@Conditional(ResendApiKeyCondition.class)
public class ResendVerificationCodeSender implements VerificationCodeSender {

    static final String EMAILS_ENDPOINT = "https://api.resend.com/emails";
    static final String FROM_NAME = "Semgravio";
    static final String SUBJECT = "Semgravio 登录验证码";

    private final ResendProperties properties;
    private final VerificationCodeProperties verificationProperties;
    private final RestClient restClient;
    private final LoggingVerificationCodeSender fallback;

    public ResendVerificationCodeSender(ResendProperties properties,
            VerificationCodeProperties verificationProperties,
            RestClient.Builder restClientBuilder, LoggingVerificationCodeSender fallback) {
        this.properties = properties;
        this.verificationProperties = verificationProperties;
        this.restClient = restClientBuilder.build();
        this.fallback = fallback;
    }

    @Override
    public void send(AccountBindingProvider channel, String target, String code) {
        if (channel != AccountBindingProvider.EMAIL) {
            fallback.send(channel, target, code); // 手机通道未接真发信：保持日志过渡态
            return;
        }
        try {
            restClient.post().uri(EMAILS_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .body(new ResendEmailRequest(fromAddress(), target, SUBJECT, bodyText(code)))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            // 4xx/5xx：带出状态码 + Resend 错误体（{name, message}）供日志排查
            throw new ResendSendException(
                    "Resend 发信失败：HTTP " + e.getStatusCode().value(),
                    e.getStatusCode().value(), e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            // 连接/超时/2xx 解析失败等：无状态码/响应体，带原始原因
            throw new ResendSendException("Resend 发信调用失败：" + e.getMessage(),
                    null, null, e);
        }
    }

    private String fromAddress() {
        return FROM_NAME + " <" + properties.getFrom() + ">";
    }

    private String bodyText(String code) {
        // 分钟数向下取整（TTL 90s 写「1 分钟」而非四舍五入的「2 分钟」）——「N 分钟内
        // 有效」对用户必须永真：宁可少说（用户提前重发），不可虚标（码已失效仍写着有效）
        long minutes = Math.max(1, verificationProperties.getCodeTtlSeconds() / 60);
        return "您的验证码：" + code
                + "\n\n" + minutes + " 分钟内有效。"
                + "\n\n若非本人操作，请忽略此邮件。";
    }

    /** Resend 发信请求体（POST /emails，纯文本单收件人）。 */
    record ResendEmailRequest(String from, String to, String subject, String text) {
    }
}
