package com.aieducenter.aiplatform.business.order.application.dto.response;

import java.time.LocalDateTime;

import com.aieducenter.aiplatform.business.order.domain.entity.OrderPriceEntry;

/**
 * 价目行响应（用户面改价历史条目，#29 交易环②）：时间 + 金额 + 备注。
 *
 * @param id        价目行标识（TSID 十进制字符串，时间有序）
 * @param amount    本次报价金额（分）
 * @param currency  币种（v1 恒 CNY）
 * @param note      报价备注（后台文本；可空）
 * @param createdAt 报价/改价时间
 */
public record PriceEntryResponse(
        String id,
        Long amount,
        String currency,
        String note,
        LocalDateTime createdAt
) {

    /** 价目行 → 响应。 */
    public static PriceEntryResponse of(OrderPriceEntry entry) {
        return new PriceEntryResponse(
                entry.getId().toString(),
                entry.getAmount(),
                entry.getCurrency(),
                entry.getNote(),
                entry.getCreatedAt());
    }
}
