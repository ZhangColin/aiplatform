package com.aieducenter.aiplatform.business.order.application.dto.command;

import java.time.LocalDateTime;

/**
 * 到账核验录入命令（#337 后台机机面，「到账核验录入」）：实到金额/实到时间/
 * 关联凭证随行留痕，渠道流水号可空落值（渠道接口位）。字段合法性由聚合守卫
 * 裁决（ORD_038/041/043），不在命令层重复校验。
 *
 * @param actualAmount 实到金额（分，正数，必填）
 * @param arrivedAt    实到时间（业务时间，必填，ISO-8601）
 * @param voucherId    关联凭证（可空——凭证行身份软引用）
 * @param channelRefNo 渠道流水号（可空；核验录入时落值）
 * @param basis        备注（可空，至多 1000 字）
 */
public record VerifyPaymentCommand(
        Long actualAmount,
        LocalDateTime arrivedAt,
        String voucherId,
        String channelRefNo,
        String basis
) {
}
