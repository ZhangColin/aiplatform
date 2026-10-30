package com.semgravio.aiplatform.business.identity.infrastructure.code;

import org.springframework.stereotype.Component;

import com.semgravio.aiplatform.business.identity.domain.enums.AccountBindingProvider;
import com.semgravio.aiplatform.business.identity.domain.port.VerificationCodeSender;

import lombok.extern.slf4j.Slf4j;

/**
 * 日志发送适配器（#327 过渡态）：验证码落服务端日志——「服务商＝上线参数」未定，
 * 真通道（SMTP/短信服务商）接入＝本类内加配置换实现或替换 bean，端口
 * （{@link VerificationCodeSender}）与频控机制不动。日志含码明文：在途码短命数
 * 挑战件（分钟级作废），排障面需要它（「用户说没收到码」的第一现场）。
 */
@Component
@Slf4j
public class LoggingVerificationCodeSender implements VerificationCodeSender {

    @Override
    public void send(AccountBindingProvider channel, String target, String code) {
        log.info("验证码发送（{}）：target={}，code={}", channel.getName(), target, code);
    }
}
