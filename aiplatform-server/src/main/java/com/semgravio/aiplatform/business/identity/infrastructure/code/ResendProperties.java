package com.semgravio.aiplatform.business.identity.infrastructure.code;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Resend 发信配置（#376，{@code app.verification-code.resend.*}）：发送服务商＝
 * 上线参数（VerificationCodeProperties 定死不含服务商凭据），key 与发件地址落本配置。
 * key 只经环境变量 RESEND_API_KEY 注入（.env.local／.env.production 手工落值，
 * gitignored），不进 git；空＝未配置（条件装配回落日志桩，见
 * {@link ResendApiKeyCondition}）。MAIL_FROM 缺省品牌域 no-reply；发件人名
 * 「Semgravio」固定（品牌面，不作上线参数）。
 */
@Component
@ConfigurationProperties(prefix = "app.verification-code.resend")
public class ResendProperties {

    /** Resend API key（RESEND_API_KEY 注入）；空＝未配置、回落日志桩。 */
    private String apiKey = "";

    /** 发件地址（MAIL_FROM 注入，缺省 no-reply@semgravio.com）。 */
    private String from = "no-reply@semgravio.com";

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }
}
