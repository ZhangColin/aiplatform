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
                    .body(new ResendEmailRequest(fromAddress(), target, SUBJECT,
                            bodyText(code), bodyHtml(code)))
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

    /**
     * HTML 邮件面（multipart alternative 的 html，纯文本见 {@link #bodyText}）：
     * 「语义引力」视觉 token（tokens.css 单一事实源）的邮件侧投影——electric-indigo
     * 主色渐变顶条、白卡 16px 圆角、violet-tint 码框、JetBrains Mono 码字。邮件客户端
     * 不吃外部样式：全内联、表格布局、深色模式由客户端自行反转，无图片依赖。
     */
    private String bodyHtml(String code) {
        long minutes = Math.max(1, verificationProperties.getCodeTtlSeconds() / 60);
        return """
                <!doctype html>
                <html lang="zh-CN">
                <body style="margin:0;padding:0;background-color:#f8fafc;">
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background-color:#f8fafc;">
                <tr><td align="center" style="padding:32px 16px;">
                <table role="presentation" cellpadding="0" cellspacing="0" style="max-width:480px;width:100%;background-color:#ffffff;border-radius:16px;border:1px solid #e2e8f0;overflow:hidden;">
                <tr><td style="height:4px;line-height:4px;font-size:0;background-color:#4f46e5;background-image:linear-gradient(90deg,#4f46e5,#06b6d4);">&nbsp;</td></tr>
                <tr><td style="padding:36px 40px 0;font-family:-apple-system,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;">
                <div style="font-size:15px;line-height:22px;font-weight:600;letter-spacing:0.02em;color:#4f46e5;">Semgravio</div>
                <h1 style="margin:16px 0 0;font-size:20px;line-height:28px;font-weight:600;color:#0f172a;">您的登录验证码</h1>
                <p style="margin:8px 0 0;font-size:14px;line-height:22px;color:#475569;">请在验证窗口输入以下验证码完成登录：</p>
                </td></tr>
                <tr><td align="center" style="padding:24px 40px;">
                <div style="padding:14px 28px;background-color:#f5f3ff;border:1px solid #e0e7ff;border-radius:12px;font-family:'JetBrains Mono',Consolas,'Courier New',monospace;font-size:32px;line-height:40px;font-weight:600;letter-spacing:8px;color:#4f46e5;">{{CODE}}</div>
                </td></tr>
                <tr><td style="padding:0 40px 32px;font-family:-apple-system,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;">
                <p style="margin:0;font-size:14px;line-height:22px;color:#475569;">{{MINUTES}} 分钟内有效。</p>
                <p style="margin:16px 0 0;padding-top:16px;border-top:1px solid #f0f4f8;font-size:13px;line-height:20px;color:#64748b;">若非本人操作，请忽略此邮件。</p>
                </td></tr>
                </table>
                <div style="margin-top:16px;font-family:-apple-system,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;font-size:12px;line-height:18px;color:#94a3b8;">此邮件由 Semgravio 平台自动发送，请勿回复。</div>
                </td></tr>
                </table>
                </body>
                </html>
                """
                .replace("{{CODE}}", code)
                .replace("{{MINUTES}}", Long.toString(minutes));
    }

    /** Resend 发信请求体（POST /emails；text＋html 双面＝multipart alternative）。 */
    record ResendEmailRequest(String from, String to, String subject, String text, String html) {
    }
}
