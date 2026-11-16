package com.semgravio.aiplatform.business.billing.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 模拟收银台配置（前缀 {@code app.billing.checkout}，#534 计费④）：<b>准入码</b>
 * 固定码起步——形态与发放属运营面调参（ADR-0047 出界），机制面＝本配置项：
 * 运营改档即生效（环境变量覆盖），不发版。渠道真接后只换通道、流程形态不变。
 */
@Component
@ConfigurationProperties(prefix = "app.billing.checkout")
public class CheckoutProperties {

    /**
     * 充值准入码（固定码起步）：模拟收银台的充值准入门槛（CONTEXT「准入码」——
     * 控制谁能自助充值，无码用户引导联系平台）。比对做空白归一（双方 trim）。
     */
    private String accessCode = "DEMO-2026";

    public String getAccessCode() {
        return accessCode;
    }

    public void setAccessCode(String accessCode) {
        this.accessCode = accessCode;
    }
}
