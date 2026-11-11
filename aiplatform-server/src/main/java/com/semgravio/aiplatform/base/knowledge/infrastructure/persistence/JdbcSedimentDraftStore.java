package com.semgravio.aiplatform.base.knowledge.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.cartisan.core.domain.BaseEnum;

import com.semgravio.aiplatform.base.knowledge.domain.enums.SedimentDraftStatus;
import com.semgravio.aiplatform.base.knowledge.domain.enums.SedimentKind;
import com.semgravio.aiplatform.base.knowledge.domain.model.SedimentDraftRecord;
import com.semgravio.aiplatform.base.knowledge.domain.repository.SedimentDraftStore;

/**
 * 沉淀草稿存取实现（#460 数据飞轮采集层，JdbcTemplate 原生 SQL，照
 * {@code JdbcSkillDraftStore} 先例）：单表 {@code knw_sediment_drafts} 读写；
 * payload/scan_findings JSONB 经字面量序列化出入。在途上限并发兜底＝在途部分
 * 唯一索引（DuplicateKeyException 由应用层归一为上限回执）。
 *
 * <p>#460 只含自荐写入与项目删除清理——「数据标注」审结读面（队列/详情）与
 * CAS 翻态归 #464，本实现届时扩展。</p>
 */
@Component
public class JdbcSedimentDraftStore implements SedimentDraftStore {

    /** 草稿读列（SELECT 共形；顺序即 {@link #recordOf} 下标）。 */
    private static final String DRAFT_SELECT_PREFIX = """
            SELECT id, kind, title, summary, content, payload, project_id, run_id, slot,
                   inflight_slot, scan_verdict, scan_findings, status, operator_id,
                   operator_name, reviewed_at, reject_reason, created_at
            FROM knw_sediment_drafts
            """;

    private static final String FIND_SQL = DRAFT_SELECT_PREFIX + " WHERE id = ?";

    private static final String FIND_PENDING_SLOTS_SQL = """
            SELECT inflight_slot FROM knw_sediment_drafts
            WHERE project_id = ? AND kind = ? AND status = ?
            """;

    private static final String INSERT_SQL = """
            INSERT INTO knw_sediment_drafts
                (id, kind, title, summary, content, payload, project_id, run_id, slot,
                 inflight_slot, scan_verdict, scan_findings, status)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?::jsonb, ?)
            """;

    /** 项目删除清理（#460）：只在途行随项目清，终态留档。 */
    private static final String DELETE_PENDING_BY_PROJECT_SQL =
            "DELETE FROM knw_sediment_drafts WHERE project_id = ? AND status = ?";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JdbcSedimentDraftStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<Integer> findPendingSlots(long projectId, String kindKey) {
        return jdbcTemplate.queryForList(FIND_PENDING_SLOTS_SQL, Integer.class,
                projectId, kindKey, SedimentDraftStatus.PENDING.getCode());
    }

    @Override
    public void insert(SedimentDraftRecord record) {
        jdbcTemplate.update(INSERT_SQL,
                record.id(), record.kind().key(), record.title(), record.summary(),
                record.content(), jsonOf(record.payload()), record.projectId(),
                record.runId(), record.slot(), record.inflightSlot(), record.scanVerdict(),
                jsonOf(record.scanFindings()), record.status().getCode());
    }

    @Override
    public SedimentDraftRecord find(long id) {
        List<SedimentDraftRecord> records = jdbcTemplate.query(FIND_SQL,
                (rs, rowNum) -> recordOf(rs), id);
        return records.isEmpty() ? null : records.get(0);
    }

    @Override
    public void deletePendingByProject(long projectId) {
        jdbcTemplate.update(DELETE_PENDING_BY_PROJECT_SQL,
                projectId, SedimentDraftStatus.PENDING.getCode());
    }

    private SedimentDraftRecord recordOf(ResultSet rs) throws SQLException {
        String kindKey = rs.getString(2);
        return new SedimentDraftRecord(
                rs.getLong(1),
                // 库内类别键即写入时的稳定键，读不回是存储腐坏——如实炸出
                SedimentKind.byKey(kindKey).orElseThrow(() -> new IllegalStateException(
                        "沉淀草稿类别键解析失败: " + kindKey)),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                mapOf(rs.getString(6), "沉淀草稿载荷"),
                rs.getLong(7),
                rs.getString(8),
                rs.getString(9),
                rs.getInt(10),
                rs.getString(11),
                findingsOf(rs.getString(12)),
                BaseEnum.requireByCode(SedimentDraftStatus.class, rs.getInt(13)),
                rs.getString(14),
                rs.getString(15),
                rs.getTimestamp(16) == null ? null : rs.getTimestamp(16).toLocalDateTime(),
                rs.getString(17),
                rs.getTimestamp(18).toLocalDateTime());
    }

    private List<Map<String, Object>> findingsOf(String json) throws SQLException {
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() { });
        }
        catch (JsonProcessingException e) {
            // 库内 JSONB 即写入时的扫描回执，读不回是存储腐坏——如实炸出
            throw new SQLException("沉淀草稿扫描 findings 解析失败", e);
        }
    }

    private Map<String, Object> mapOf(String json, String what) throws SQLException {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        }
        catch (JsonProcessingException e) {
            throw new SQLException(what + "解析失败", e);
        }
    }

    private String jsonOf(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("沉淀草稿 JSONB 序列化失败", e);
        }
    }
}
