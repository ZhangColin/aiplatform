package com.semgravio.aiplatform.business.billing.application.dto.response;

/**
 * 池现值读面（#534 计费④「我的账户」双池余额呈现）：余额可负（负余额软模型）。
 *
 * @param poolId  池 id（字符串化防 JS 精度丢失）
 * @param balance 余额（分；无池＝视图级 null，不造假零）
 */
public record PoolView(String poolId, long balance) {
}
