package com.aieducenter.aiplatform.business.order.application.dto.response;

import java.time.LocalDateTime;

import com.aieducenter.aiplatform.business.order.domain.entity.OrderPriceEntry;

/**
 * 后台价目历史条目（#155，订单详情内嵌）：时间 + 金额 + 备注 + 操作者——改价
 * 过程可回看、责任可查。与用户面 {@link PriceEntryResponse} 同构外加操作者两肢
 * （admin 侧管理员标识不对用户面呈现）。
 *
 * @param id           价目行标识（TSID 十进制字符串，时间有序）
 * @param amount       本次报价金额（分）
 * @param currency     币种（v1 恒 CNY）
 * @param note         报价备注（后台文本；可空）
 * @param operatorId   操作者标识（存量行/无头落 NULL）
 * @param operatorName 操作者昵称（直读展示；存量行/无头落 NULL）
 * @param createdAt    报价/改价时间
 */
public record BackofficePriceEntryResponse(
        String id,
        Long amount,
        String currency,
        String note,
        String operatorId,
        String operatorName,
        LocalDateTime createdAt
) {

    /** 价目行 → 后台响应。 */
    public static BackofficePriceEntryResponse of(OrderPriceEntry entry) {
        return new BackofficePriceEntryResponse(
                entry.getId().toString(),
                entry.getAmount(),
                entry.getCurrency(),
                entry.getNote(),
                entry.getOperatorId(),
                entry.getOperatorName(),
                entry.getCreatedAt());
    }
}
