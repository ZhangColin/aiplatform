package com.aieducenter.aiplatform.base.knowledge.domain.repository;

import java.util.List;

import com.aieducenter.aiplatform.base.knowledge.domain.enums.MaterialStatus;
import com.aieducenter.aiplatform.base.knowledge.domain.model.KnowledgeHit;
import com.aieducenter.aiplatform.base.knowledge.domain.model.KnowledgeSpec;
import com.aieducenter.aiplatform.base.knowledge.domain.model.Operator;

/**
 * 知识存取（素材登记表 {@code knw_materials} + 块表 {@code knw_chunks}）：素材
 * 身份、状态与操作者落登记表（单一事实源），块表只存内容与向量。pgvector 向量
 * 列超出 JPA 映射面，写读两侧都走原生 SQL（照 {@code UsageEventAggregations}
 * 先例：接口在 domain/repository，JdbcTemplate 实现在 infrastructure，不挂
 * Spring Data 仓储）。
 */
public interface KnowledgeStore {

    /**
     * 幂等落库：登记表 upsert（保 id/状态/操作者/首沉淀时间，只刷新展示字段）
     * ＋ {@code (kind, sourceRef)} 块删后插，同一事务内完成（新旧块原子切换、
     * 停用状态跨重沉淀存活）。embeddings 与 {@link KnowledgeSpec#chunks()}
     * 同序同量（调用方保证）。
     */
    void replace(KnowledgeSpec spec, List<float[]> embeddings);

    /**
     * 余弦相似检索（HNSW 索引）：全局跨项目、按相似度升序取 topK（A5 §3 策略）；
     * 仅启用素材的块参与命中（停用素材整体退出，#153）。
     */
    List<KnowledgeHit> findSimilar(float[] queryVector, int topK);

    /**
     * 素材状态迁移（停用⇄启用，#153 可逆开关）：落状态与最近管理动作操作者。
     *
     * @return 素材不存在（未沉淀过）返回 false，由调用方定域内语义
     */
    boolean setStatus(String kind, String sourceRef, MaterialStatus status, Operator operator);

    /**
     * 按项目清理登记行与全部知识块（级联清理入口，A5 §5；照删含已停用素材）。
     */
    void deleteByProject(String projectId);
}
