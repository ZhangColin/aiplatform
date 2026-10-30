package com.semgravio.aiplatform.business.order.application.dto.command;

/**
 * 打回命令（#337 后台机机面，核验族动作——凭证主张被否）：依据必填留痕
 * （ORD_042）、回待支付。字段合法性由聚合守卫裁决，不在命令层重复校验。
 *
 * @param voucherId 关联凭证（可空）
 * @param basis     打回依据（必填自由文本，至多 1000 字）
 */
public record RejectPaymentCommand(
        String voucherId,
        String basis
) {
}
