package com.semgravio.aiplatform.base.knowledge.domain.model;

import java.util.List;

/**
 * 知识检索结果（#639）：命中列表＋是否降级。降级＝向量服务不可用或检索异常，
 * 与「真的 0 条命中」必须可区分——留痕与后台读面据此区分「真没有」与「服务
 * 挂了」。
 *
 * @param hits     命中条目（降级恒为空列表）
 * @param degraded 是否降级（向量服务不可用 / 检索异常）
 */
public record KnowledgeRetrieval(List<KnowledgeHit> hits, boolean degraded) {

    public KnowledgeRetrieval {
        hits = hits == null ? List.of() : List.copyOf(hits);
    }

    /** 检索成功（含成功但 0 条）。 */
    public static KnowledgeRetrieval of(List<KnowledgeHit> hits) {
        return new KnowledgeRetrieval(hits, false);
    }

    /** 检索降级（向量服务不可用或检索异常）。 */
    public static KnowledgeRetrieval unavailable() {
        return new KnowledgeRetrieval(List.of(), true);
    }

    /** 成功但 0 条命中。 */
    public static KnowledgeRetrieval empty() {
        return of(List.of());
    }
}
