package com.semgravio.aiplatform.business.billing.domain.model;

import java.math.BigDecimal;

/**
 * 已配价用量线（结算的换算素材）：一段用量按 (provider, model, 档位, 时点生效
 * 单价, 币种) 归并的一条线——同键改价跨段界会出多线（各按各时点单价，不混价）。
 * {@code kindName} 为计量面档名（快照直读用）；金额＝{@code quantity}×
 * {@code unitPrice}（元，BigDecimal——不预先取整，行落分时一次性 HALF_UP）。
 *
 * @param provider  模型提供方（单价表匹配键之一）
 * @param model     模型（匹配键）
 * @param tier      计价档族（折算率适用面单点）
 * @param kindName  计量面档名（如 OUTPUT/IMAGE/UNIT）
 * @param quantity  该线用量（token 数/件数）
 * @param unitPrice 时点生效单价（元/token 或 元/件——计量表原值）
 * @param currency  单价币种（ISO 4217；非 CNY 线不进用户账、快照留痕）
 */
public record PricedLine(
        String provider,
        String model,
        PricingTier tier,
        String kindName,
        long quantity,
        BigDecimal unitPrice,
        String currency) {
}
