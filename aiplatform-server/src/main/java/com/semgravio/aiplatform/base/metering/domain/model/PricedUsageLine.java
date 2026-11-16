package com.semgravio.aiplatform.base.metering.domain.model;

import java.math.BigDecimal;

import com.semgravio.aiplatform.base.metering.domain.enums.TokenKind;

/**
 * 已配价用量线（成本换算的分组结果形，#531 供计费读面）：一组事件按
 * (provider, model, 档位, 时点生效单价, 币种) 归并——同键改价跨区间会出多线
 * （各按各时点单价，不混价、历史不漂移）。金额换算归调用方（本模型只出素材，
 * metering 保持零商业概念）。
 *
 * @param provider  模型提供方（匹配键之一）
 * @param model     模型（匹配键）
 * @param kind      档位（token 五档/按张/按件）
 * @param quantity  该线用量（token 数/张数/件数）
 * @param unitPrice 时点生效单价（元/token 或 元/件——表原值）
 * @param currency  单价币种（ISO 4217，分桶不折算口径）
 */
public record PricedUsageLine(
        String provider,
        String model,
        TokenKind kind,
        long quantity,
        BigDecimal unitPrice,
        String currency) {
}
