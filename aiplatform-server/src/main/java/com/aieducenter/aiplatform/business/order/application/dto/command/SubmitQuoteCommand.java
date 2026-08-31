package com.aieducenter.aiplatform.business.order.application.dto.command;

/**
 * 提交报价命令（#29 后台机机面）：已报价态重复提交 = 改价。金额单位分
 * （正整数），备注为后台文本（用户面展示）。字段合法性由聚合守卫裁决
 * （ORD_008/ORD_009），不在命令层重复校验。
 *
 * @param amount 总价（分，正数）
 * @param note   报价备注（可空，至多 1000 字）
 */
public record SubmitQuoteCommand(
        Long amount,
        String note
) {
}
