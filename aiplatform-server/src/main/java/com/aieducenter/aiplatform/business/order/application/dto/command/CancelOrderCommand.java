package com.aieducenter.aiplatform.business.order.application.dto.command;

/**
 * 运营取消命令（#157 后台机机面）：取消原因必填（运营内部口径，不呈现用户面）。
 * 字段合法性由聚合守卫裁决（ORD_013/ORD_014），不在命令层重复校验——同
 * {@link SubmitQuoteCommand} 形制。
 *
 * @param reason 取消原因（必填，至多 1000 字）
 */
public record CancelOrderCommand(
        String reason
) {
}
