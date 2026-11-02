package com.semgravio.aiplatform.business.identity.infrastructure.code;

import org.springframework.stereotype.Component;

import com.semgravio.aiplatform.business.identity.domain.enums.AccountBindingProvider;
import com.semgravio.aiplatform.business.identity.domain.port.VerificationCodeSender;

import lombok.extern.slf4j.Slf4j;

/**
 * 日志发送适配器（#327 起，#376 起为回落件）：验证码落服务端日志——未配
 * RESEND_API_KEY 环境的缺省出口（本地开发零配置；Resend 主发见
 * {@link ResendVerificationCodeSender} 条件装配），也是手机通道的过渡出口。
 * 端口（{@link VerificationCodeSender}）与频控机制不动。日志含码明文：在途码
 * 短命数挑战件（分钟级作废），排障面需要它（「用户说没收到码」的第一现场）。
 */
@Component
@Slf4j
public class LoggingVerificationCodeSender implements VerificationCodeSender {

    @Override
    public void send(AccountBindingProvider channel, String target, String code) {
        log.info("验证码发送（{}）：target={}，code={}", channel.getName(), target, code);
    }
}
