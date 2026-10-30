package com.semgravio.aiplatform.business.identity.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 验证码登录配置（#327，{@code app.verification-code.*}）：频控/有效期/试错上限
 * 全参数化（上线参数，不进代码）；通道机制双开（email/phone）、上线先邮箱——
 * 两个通道开关位在配置，翻开关即开放手机通道，机制零改动。
 *
 * <p>发送服务商＝上线参数（spec-320 Out of scope 定死）：本配置不含服务商凭据，
 * 接真通道时在发送适配器（LoggingVerificationCodeSender）内加配置/换实现，
 * 端口（VerificationCodeSender）与本配置的频控面不动。</p>
 */
@Component
@ConfigurationProperties(prefix = "app.verification-code")
public class VerificationCodeProperties {

    /** 验证码有效期（秒）。 */
    private long codeTtlSeconds = 300;

    /** 同目标发码冷却窗（秒）——发码频控参数配置化（AC）。 */
    private long sendCooldownSeconds = 60;

    /** 单码试错上限（含正确验码；超次即作废重发）。 */
    private int maxVerifyAttempts = 5;

    /** 邮箱通道开关（上线先开）。 */
    private boolean emailChannelEnabled = true;

    /** 手机通道开关（机制位在，上线先关）。 */
    private boolean phoneChannelEnabled = false;

    public long getCodeTtlSeconds() { return codeTtlSeconds; }
    public void setCodeTtlSeconds(long codeTtlSeconds) { this.codeTtlSeconds = codeTtlSeconds; }

    public long getSendCooldownSeconds() { return sendCooldownSeconds; }
    public void setSendCooldownSeconds(long sendCooldownSeconds) {
        this.sendCooldownSeconds = sendCooldownSeconds;
    }

    public int getMaxVerifyAttempts() { return maxVerifyAttempts; }
    public void setMaxVerifyAttempts(int maxVerifyAttempts) {
        this.maxVerifyAttempts = maxVerifyAttempts;
    }

    public boolean isEmailChannelEnabled() { return emailChannelEnabled; }
    public void setEmailChannelEnabled(boolean emailChannelEnabled) {
        this.emailChannelEnabled = emailChannelEnabled;
    }

    public boolean isPhoneChannelEnabled() { return phoneChannelEnabled; }
    public void setPhoneChannelEnabled(boolean phoneChannelEnabled) {
        this.phoneChannelEnabled = phoneChannelEnabled;
    }
}
