package com.semgravio.aiplatform.business.billing.domain.model;

import java.math.BigDecimal;

/**
 * 固定单价项的单价报价（按次直记的计价素材）：计件档单价行的现值读法。
 *
 * @param unitPrice 单价（元/件——计量表原值）
 * @param currency  币种（ISO 4217；非 CNY 不进用户账）
 */
public record PiecePrice(BigDecimal unitPrice, String currency) {
}
