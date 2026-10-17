package com.aieducenter.aiplatform.business.identity.domain.port;

import com.aieducenter.aiplatform.business.identity.domain.enums.AccountBindingProvider;

/**
 * 验证码发送端口（#327）：平台内自建验证码登录的发码出口——「服务商＝上线参数」
 * 的接口位。接真通道（SMTP/短信服务商）＝加配置换实现，本端口与登录编排不动。
 *
 * <p>实现只担「把码送到目标」（尽力而为、失败如实抛）；频控/有效期/试错上限
 * 归 {@link com.aieducenter.aiplatform.business.identity.infrastructure.code.VerificationCodeStore}
 * 与 VerificationCodeProperties，不散进适配器。</p>
 */
public interface VerificationCodeSender {

    /**
     * 发送验证码到目标。
     *
     * @param channel 收码通道（email/phone）
     * @param target  归一后的收码目标（邮箱小写、手机号原样）
     * @param code    六位数字码
     */
    void send(AccountBindingProvider channel, String target, String code);
}
