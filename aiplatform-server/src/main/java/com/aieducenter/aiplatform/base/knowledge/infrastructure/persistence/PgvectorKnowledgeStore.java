package com.aieducenter.aiplatform.base.knowledge.infrastructure.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.cartisan.data.jpa.id.TsidGenerator;

import com.aieducenter.aiplatform.base.knowledge.domain.enums.MaterialStatus;
import com.aieducenter.aiplatform.base.knowledge.domain.model.KnowledgeHit;
import com.aieducenter.aiplatform.base.knowledge.domain.model.KnowledgeSpec;
import com.aieducenter.aiplatform.base.knowledge.domain.model.Operator;
import com.aieducenter.aiplatform.base.knowledge.domain.repository.KnowledgeStore;

/**
 * 知识存取实现（JdbcTemplate 原生 SQL，Phase A 弱化形态——B0 蓝图 §3：数据量
 * 或检索质量需求到了再换腾讯云向量库，接口不动）。vector 列超出 JPA 映射面，
 * 入参/出参经字面量序列化（{@code ?::vector} / {@code ?::jsonb} 服务端转型）。
 *
 * <p>素材登记表 {@code knw_materials} 是身份/状态/操作者的单一事实源（#153）；
 * 块表 {@code knw_chunks} 只存内容与向量。沉淀幂等替换时登记行 upsert——保
 * id/状态/操作者/首沉淀时间，只刷新展示字段（被停用素材重沉淀不复活）。</p>
 */
@Component
public class PgvectorKnowledgeStore implements KnowledgeStore {

    private static final String UPSERT_MATERIAL_SQL = """
            INSERT INTO knw_materials (id, kind, source_ref, project_id, project_name, title)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (kind, source_ref) DO UPDATE SET
                project_id = EXCLUDED.project_id,
                project_name = EXCLUDED.project_name,
                title = EXCLUDED.title,
                updated_at = CURRENT_TIMESTAMP
            """;

    private static final String INSERT_CHUNK_SQL = """
            INSERT INTO knw_chunks
                (id, kind, source_ref, project_id, project_name, title, seq, chunk, embedding, meta)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::vector, ?::jsonb)
            """;

    /** 检索状态过滤：status = 1 即 MaterialStatus.ENABLED（SQL 无法引用 Java 常量，靠注释对齐）。 */
    private static final String FIND_SIMILAR_SQL = """
            SELECT c.kind, c.project_name, c.title, c.chunk
            FROM knw_chunks c
            JOIN knw_materials m ON m.kind = c.kind AND m.source_ref = c.source_ref
            WHERE m.status = 1
            ORDER BY c.embedding <=> ?::vector LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PgvectorKnowledgeStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 登记行 upsert ＋ (kind, source_ref) 块删后插，同一事务：中途失败整体回滚，
     * 新旧块原子切换（不存在「旧块已删、新块半落」的中间态）。素材 id 为 TSID
     * （表规范 BIGINT 主键，首沉淀生成、重沉淀保留）。
     */
    @Override
    @Transactional
    public void replace(KnowledgeSpec spec, List<float[]> embeddings) {
        jdbcTemplate.update(UPSERT_MATERIAL_SQL,
                TsidGenerator.newInstance().generate(),
                spec.kind(), spec.sourceRef(), spec.projectId(), spec.projectName(), spec.title());
        jdbcTemplate.update("DELETE FROM knw_chunks WHERE kind = ? AND source_ref = ?",
                spec.kind(), spec.sourceRef());
        String metaJson = toJson(spec.meta());
        // seq 从 0 起，与 chunks/embeddings 下标一致（调用方保证同序同量）
        List<Object[]> rows = new ArrayList<>(embeddings.size());
        for (int i = 0; i < embeddings.size(); i++) {
            rows.add(new Object[]{
                    TsidGenerator.newInstance().generate(),
                    spec.kind(), spec.sourceRef(), spec.projectId(), spec.projectName(), spec.title(),
                    i,
                    spec.chunks().get(i),
                    vectorLiteral(embeddings.get(i)),
                    metaJson
            });
        }
        jdbcTemplate.batchUpdate(INSERT_CHUNK_SQL, rows);
    }

    @Override
    public List<KnowledgeHit> findSimilar(float[] queryVector, int topK) {
        return jdbcTemplate.query(FIND_SIMILAR_SQL,
                (rs, rowNum) -> new KnowledgeHit(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                vectorLiteral(queryVector), topK);
    }

    @Override
    public boolean setStatus(String kind, String sourceRef, MaterialStatus status, Operator operator) {
        int updated = jdbcTemplate.update(
                "UPDATE knw_materials SET status = ?, operator_id = ?, operator_name = ?, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE kind = ? AND source_ref = ?",
                status.getCode(), operator.id(), operator.name(), kind, sourceRef);
        return updated > 0;
    }

    @Override
    @Transactional
    public void deleteByProject(String projectId) {
        jdbcTemplate.update("DELETE FROM knw_chunks WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM knw_materials WHERE project_id = ?", projectId);
    }

    /** float[] → pgvector 字面量 {@code [v1,v2,...]}（余弦距离 {@code <=>} 的入参形态）。 */
    private static String vectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 9).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    /** meta 序列化（null/空归一为 NULL 列，底座不解释）。 */
    private String toJson(Map<String, Object> meta) {
        if (meta == null || meta.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            // meta 是透传扩展位，序列化失败不阻断入库：降级为无 meta
            return null;
        }
    }
}
