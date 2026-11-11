package com.semgravio.aiplatform.base.knowledge.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cn.hutool.core.collection.CollUtil;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.id.TsidGenerator;

import com.semgravio.aiplatform.base.knowledge.domain.enums.MaterialStatus;
import com.semgravio.aiplatform.base.knowledge.domain.model.KnowledgeHit;
import com.semgravio.aiplatform.base.knowledge.domain.model.KnowledgeSpec;
import com.semgravio.aiplatform.base.knowledge.domain.model.MaterialRecord;
import com.semgravio.aiplatform.base.knowledge.domain.model.MaterialSearchResult;
import com.semgravio.aiplatform.base.knowledge.domain.model.Operator;
import com.semgravio.aiplatform.base.knowledge.domain.repository.KnowledgeStore;

/**
 * 知识存取实现（JdbcTemplate 原生 SQL，Phase A 弱化形态——B0 蓝图 §3：数据量
 * 或检索质量需求到了再换腾讯云向量库，接口不动）。vector 列超出 JPA 映射面，
 * 入参/出参经字面量序列化（{@code ?::vector} / {@code ?::jsonb} 服务端转型）。
 *
 * <p>素材登记表 {@code knw_materials} 是身份/状态/操作者的单一事实源（#153）；
 * 块表 {@code knw_chunks} 只存内容与向量。沉淀幂等替换时登记行 upsert——保
 * id/状态/操作者/首沉淀时间，只刷新展示字段（被停用素材重沉淀不复活）。</p>
 *
 * <p>检索＝混检（#465）：向量通道（HNSW 余弦）＋关键词通道（pg_trgm 三元组）
 * 各取候选池后 RRF 融合——替代纯向量；孤儿沉淀素材（project_id NULL，激活
 * 升格脱钩）落库与检索均无特判，级联清理 WHERE 永不命中 NULL。</p>
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

    /**
     * 混检·向量通道候选（#465）：余弦距离升序取候选池（HNSW 索引承载）；
     * 仅启用素材参与（JOIN 登记表 status = 1，MaterialStatus.ENABLED——SQL 无法
     * 引用 Java 常量，靠注释对齐）。
     */
    private static final String FIND_VECTOR_SQL = """
            SELECT c.kind, c.source_ref, c.seq, c.project_name, c.title, c.chunk
            FROM knw_chunks c
            JOIN knw_materials m ON m.kind = c.kind AND m.source_ref = c.source_ref
            WHERE m.status = 1
            ORDER BY c.embedding <=> ?::vector LIMIT ?
            """;

    /**
     * 混检·关键词通道候选（#465）：pg_trgm 三元组相似度降序取候选池——中文无
     * 分词依赖的库内原生模糊匹配。零重叠闸（{@code similarity > 0}）：与 query
     * 无共享三元组的块不进候选（无查询词命中时通道零贡献，不硬塞噪音）；同分
     * 以 (kind, source_ref, seq) 兜底定序（确定性）。停用过滤与向量通道同开。
     */
    private static final String FIND_KEYWORD_SQL = """
            SELECT c.kind, c.source_ref, c.seq, c.project_name, c.title, c.chunk
            FROM knw_chunks c
            JOIN knw_materials m ON m.kind = c.kind AND m.source_ref = c.source_ref
            WHERE m.status = 1 AND similarity(c.chunk, ?) > 0
            ORDER BY similarity(c.chunk, ?) DESC, c.kind, c.source_ref, c.seq
            LIMIT ?
            """;

    /** RRF 融合阻尼常数（倒数秩融合惯例值 60）。 */
    private static final double RRF_K = 60.0;

    /** 各通道候选池倍数（topK 的倍数——池大于出参才谈得上融合互补）。 */
    private static final int POOL_FACTOR = 2;

    /** 登记行读列（素材清单/详情/回执共形；顺序即 {@link #materialOf} 下标）。 */
    private static final String MATERIAL_COLUMNS =
            "id, kind, source_ref, project_id, project_name, title, status, operator_id, "
                    + "operator_name, created_at";

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

    /**
     * 混检检索（#465）：向量/关键词两通道各取 {@code topK × POOL_FACTOR} 候选，
     * RRF（k={@link #RRF_K}）融合——每块得分＝各通道 {@code 1/(k+秩)} 之和，
     * 双通道同中块叠加进位；同分以最优秩、kind/sourceRef/seq 兜底定序。
     */
    @Override
    public List<KnowledgeHit> findRelevant(String query, float[] queryVector, int topK) {
        int pool = topK * POOL_FACTOR;
        List<Candidate> vectorCandidates = candidates(FIND_VECTOR_SQL, pool, vectorLiteral(queryVector));
        List<Candidate> keywordCandidates = candidates(FIND_KEYWORD_SQL, pool, query, query);
        return fuse(vectorCandidates, keywordCandidates, topK);
    }

    /** 通道候选行（kind/sourceRef/seq＝融合去重键；hit＝展示面）。 */
    private record Candidate(String kind, String sourceRef, int seq, KnowledgeHit hit) {
    }

    /** 融合去重键（同素材的不同块是不同命中，键必须落到块级）。 */
    private record ChunkKey(String kind, String sourceRef, int seq) {
    }

    /** 融合中的块：去重键＋展示面＋累计 RRF 得分＋最优通道秩（同分定序用）。 */
    private record Fused(ChunkKey key, KnowledgeHit hit, double score, int bestRank) {
    }

    /** 单通道候选查询（行序即通道内秩，1 基）。 */
    private List<Candidate> candidates(String sql, int pool, Object... args) {
        Object[] full = new Object[args.length + 1];
        System.arraycopy(args, 0, full, 0, args.length);
        full[args.length] = pool;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new Candidate(
                rs.getString(1), rs.getString(2), rs.getInt(3),
                new KnowledgeHit(rs.getString(1), rs.getString(4), rs.getString(5),
                        rs.getString(6))),
                full);
    }

    /** RRF 融合：两通道候选按块去重叠加得分，降序取 topK。 */
    private static List<KnowledgeHit> fuse(List<Candidate> vectorCandidates,
            List<Candidate> keywordCandidates, int topK) {
        Map<ChunkKey, Fused> fused = new HashMap<>();
        accumulate(fused, vectorCandidates);
        accumulate(fused, keywordCandidates);
        return fused.values().stream()
                .sorted(Comparator.<Fused>comparingDouble(f -> -f.score())
                        .thenComparingInt(Fused::bestRank)
                        .thenComparing(f -> f.hit().kind())
                        .thenComparing(f -> f.key().sourceRef())
                        .thenComparingInt(f -> f.key().seq()))
                .limit(topK)
                .map(Fused::hit)
                .toList();
    }

    private static void accumulate(Map<ChunkKey, Fused> fused, List<Candidate> candidates) {
        for (int i = 0; i < candidates.size(); i++) {
            Candidate candidate = candidates.get(i);
            ChunkKey key = new ChunkKey(candidate.kind(), candidate.sourceRef(), candidate.seq());
            int rank = i + 1;
            fused.merge(key, new Fused(key, candidate.hit(), 1.0 / (RRF_K + rank), rank),
                    (existing, single) -> new Fused(existing.key(), existing.hit(),
                            existing.score() + 1.0 / (RRF_K + rank),
                            Math.min(existing.bestRank(), rank)));
        }
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

    @Override
    public MaterialRecord findMaterial(long id) {
        List<MaterialRecord> rows = jdbcTemplate.query(
                "SELECT " + MATERIAL_COLUMNS + " FROM knw_materials WHERE id = ?",
                (rs, rowNum) -> materialOf(rs), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 素材清单检索（#166）：动态条件拼接（null 维不参与过滤），全量计数与当页行
     * 两查共用同一 where/args（同 {@code UsageEventAggregationsImpl} 时间窗先例）。
     * 沉淀时间＝created_at（首沉淀，管理面正口径），闭区间含两端。
     */
    @Override
    public MaterialSearchResult searchMaterials(MaterialStatus status, Instant sunkFrom,
                                                Instant sunkTo, String projectId, int offset,
                                                int limit) {
        List<String> conditions = CollUtil.newArrayList();
        List<Object> args = CollUtil.newArrayList();
        if (status != null) {
            conditions.add("status = ?");
            args.add(status.getCode());
        }
        if (sunkFrom != null) {
            conditions.add("created_at >= ?");
            args.add(Timestamp.from(sunkFrom));
        }
        if (sunkTo != null) {
            conditions.add("created_at <= ?");
            args.add(Timestamp.from(sunkTo));
        }
        if (projectId != null && !projectId.isBlank()) {
            conditions.add("project_id = ?");
            args.add(projectId);
        }
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knw_materials" + where, Long.class, args.toArray());
        args.add(limit);
        args.add(offset);
        List<MaterialRecord> items = jdbcTemplate.query(
                "SELECT " + MATERIAL_COLUMNS + " FROM knw_materials" + where
                        + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> materialOf(rs), args.toArray());
        return new MaterialSearchResult(items, total == null ? 0 : total);
    }

    @Override
    public List<String> chunksOf(String kind, String sourceRef) {
        return jdbcTemplate.query(
                "SELECT chunk FROM knw_chunks WHERE kind = ? AND source_ref = ? ORDER BY seq",
                (rs, rowNum) -> rs.getString(1), kind, sourceRef);
    }

    /**
     * 治理删除（#166）：块表以 (kind, source_ref) 为柄（无素材 id 列）——先取身份
     * 再两删，同事务保证「块与登记行同生共死」；素材不存在不动任何行。
     */
    @Override
    @Transactional
    public boolean deleteMaterial(long id) {
        List<Object[]> identity = jdbcTemplate.query(
                "SELECT kind, source_ref FROM knw_materials WHERE id = ?",
                (rs, rowNum) -> new Object[]{rs.getString(1), rs.getString(2)}, id);
        if (identity.isEmpty()) {
            return false;
        }
        Object[] keys = identity.get(0);
        jdbcTemplate.update("DELETE FROM knw_chunks WHERE kind = ? AND source_ref = ?",
                keys[0], keys[1]);
        jdbcTemplate.update("DELETE FROM knw_materials WHERE id = ?", id);
        return true;
    }

    /** 登记行 → 读模型（列序＝{@link #MATERIAL_COLUMNS}）。 */
    private static MaterialRecord materialOf(ResultSet rs) throws SQLException {
        return new MaterialRecord(
                rs.getLong(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                BaseEnum.requireByCode(MaterialStatus.class, rs.getInt(7)),
                rs.getTimestamp(10).toInstant(),
                rs.getString(8),
                rs.getString(9));
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
