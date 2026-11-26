package com.semgravio.aiplatform.base.knowledge.domain.model;

import java.time.Instant;

/**
 * 向量服务健康探活结果（#639）：可用／不可用、检查时间、耗时。探活用服务
 * 已有健康路径（{@code GET /health}）、短超时，失败不影响检索或入库。
 *
 * @param available 服务是否可用（健康路径 200）
 * @param checkedAt 检查时间
 * @param elapsedMs 探活耗时（毫秒）
 */
public record EmbeddingHealth(boolean available, Instant checkedAt, long elapsedMs) {
}
