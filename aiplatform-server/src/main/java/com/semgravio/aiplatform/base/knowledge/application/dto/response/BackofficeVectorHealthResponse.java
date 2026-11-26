package com.semgravio.aiplatform.base.knowledge.application.dto.response;

import java.time.Instant;

import com.semgravio.aiplatform.base.knowledge.domain.model.EmbeddingHealth;

/**
 * 后台向量服务健康读面（#639，只读）：可用／不可用、检查时间、耗时。健康探活
 * 走服务已有健康路径、短超时，探活失败不影响检索或入库——读面只反映当次探活。
 *
 * @param available 服务是否可用
 * @param checkedAt 检查时间
 * @param elapsedMs 探活耗时（毫秒）
 */
public record BackofficeVectorHealthResponse(
        boolean available,
        Instant checkedAt,
        long elapsedMs) {

    /** 探活结果 → 后台响应。 */
    public static BackofficeVectorHealthResponse of(EmbeddingHealth health) {
        return new BackofficeVectorHealthResponse(
                health.available(), health.checkedAt(), health.elapsedMs());
    }
}
