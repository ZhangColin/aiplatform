package com.aieducenter.aiplatform.base.metering.application.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

import com.aieducenter.aiplatform.base.metering.domain.aggregate.PriceEntry;

/**
 * 后台单价行响应（#160，清单行/改价回执/停用回执共形）：匹配键＋单价＋生效
 * 区间＋操作者。与订单价目行（{@code BackofficePriceEntryResponse}）是两个
 * 域概念——本行为平台成本换算用的单价数据（met_price_entries），非订单报价史。
 *
 * <p>{@code unitPrice} 序列化为十进制原串（{@code toPlainString}）：BigDecimal
 * 直出 JSON 会落科学计数（0.00000132 → 1.32E-6），单价契约按精确十进制串交接。
 * {@code effectiveTo} 为 null 即当前行；历史行含区间两端。</p>
 *
 * @param id             单价行标识（TSID 十进制字符串）
 * @param provider       模型提供方
 * @param model          模型
 * @param tokenKind      token 档位 code（1=input 2=output 3=cache_read 4=cache_write 5=reasoning）
 * @param tokenKindName  token 档位名（直读展示）
 * @param unitPrice      每 token 单价（精确十进制串）
 * @param currency       币种（ISO 4217）
 * @param effectiveFrom  生效起点（含）
 * @param effectiveTo    生效终点（不含；null = 当前行）
 * @param operatorId     操作者标识（该行最近管理动作——开行或停用；存量行/种子行/无头为 null）
 * @param operatorName   操作者昵称（直读展示；同上落空口径）
 */
public record UnitPriceEntryResponse(
        String id,
        String provider,
        String model,
        Integer tokenKind,
        String tokenKindName,
        String unitPrice,
        String currency,
        Instant effectiveFrom,
        Instant effectiveTo,
        String operatorId,
        String operatorName
) {

    /** 单价行 → 后台响应。 */
    public static UnitPriceEntryResponse of(PriceEntry entry) {
        return new UnitPriceEntryResponse(
                entry.getId().toString(),
                entry.getProvider(),
                entry.getModel(),
                entry.getTokenKind().getCode(),
                entry.getTokenKind().getName(),
                toPlain(entry.getUnitPrice()),
                entry.getCurrency(),
                entry.getEffectiveFrom(),
                entry.getEffectiveTo(),
                entry.getOperatorId(),
                entry.getOperatorName());
    }

    private static String toPlain(BigDecimal unitPrice) {
        // PG numeric(20,10) 读回补零到声明标度（0.000001 → 0.0000010000）——canonical
        // 十进制串去尾零交接
        return unitPrice == null ? null : unitPrice.stripTrailingZeros().toPlainString();
    }
}
