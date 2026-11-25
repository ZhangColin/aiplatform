package com.semgravio.aiplatform.base.knowledge.application.dto.response;

import java.time.Instant;
import java.util.List;

import com.semgravio.aiplatform.base.knowledge.domain.model.KnowledgeHit;
import com.semgravio.aiplatform.base.knowledge.domain.model.RetrievalRecord;

/**
 * 后台命中留痕行（#639，只读）：一次主智能体开场检索——项目、时间、命中条目
 * （类别／标题／来源项目展示名／片段）、是否降级。检索成功但 0 条时 hits 为空、
 * degraded=false；向量服务不可用或检索异常时 degraded=true（与「真没有」可区分）。
 *
 * @param id         留痕记录 id（TSID 十进制字符串）
 * @param projectId  检索所属项目
 * @param recordedAt 检索时间
 * @param degraded   是否降级
 * @param hits       命中条目
 */
public record BackofficeKnowledgeRetrievalResponse(
        String id,
        String projectId,
        Instant recordedAt,
        boolean degraded,
        List<HitItem> hits) {

    /**
     * 命中条目（类别／标题／来源项目展示名／片段）。
     *
     * @param kind              素材类别
     * @param sourceProjectName 来源项目展示名
     * @param title             素材标题
     * @param chunk             命中片段
     */
    public record HitItem(String kind, String sourceProjectName, String title, String chunk) {

        static HitItem of(KnowledgeHit hit) {
            return new HitItem(hit.kind(), hit.sourceProjectName(), hit.title(), hit.chunk());
        }
    }

    /** 留痕行 → 后台响应。 */
    public static BackofficeKnowledgeRetrievalResponse of(RetrievalRecord record) {
        return new BackofficeKnowledgeRetrievalResponse(
                Long.toString(record.id()),
                record.projectId(),
                record.recordedAt(),
                record.degraded(),
                record.hits().stream().map(HitItem::of).toList());
    }
}
