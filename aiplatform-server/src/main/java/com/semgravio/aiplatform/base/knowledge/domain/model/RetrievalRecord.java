package com.semgravio.aiplatform.base.knowledge.domain.model;

import java.time.Instant;
import java.util.List;

/**
 * 命中留痕记录（#639）：每次主智能体开场检索一条——项目、时间、命中条目
 * （类别／标题／来源项目展示名／片段）、是否降级。检索成功但 0 条也记
 * （用于「有命中／无命中的项目」比较）；检索失败记降级。
 *
 * @param id         记录 id（TSID）
 * @param projectId  检索所属项目
 * @param recordedAt 检索时间
 * @param degraded   是否降级
 * @param hits       命中条目（降级恒为空）
 */
public record RetrievalRecord(
        long id,
        String projectId,
        Instant recordedAt,
        boolean degraded,
        List<KnowledgeHit> hits) {

    public RetrievalRecord {
        hits = hits == null ? List.of() : List.copyOf(hits);
    }
}
