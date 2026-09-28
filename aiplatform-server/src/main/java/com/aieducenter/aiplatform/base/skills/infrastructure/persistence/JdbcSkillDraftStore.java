package com.aieducenter.aiplatform.base.skills.infrastructure.persistence;

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

import com.aieducenter.aiplatform.base.skills.domain.enums.SkillDraftStatus;
import com.aieducenter.aiplatform.base.skills.domain.enums.SkillSlot;
import com.aieducenter.aiplatform.base.skills.domain.model.Operator;
import com.aieducenter.aiplatform.base.skills.domain.model.SkillDraftRecord;
import com.aieducenter.aiplatform.base.skills.domain.repository.SkillDraftStore;

/**
 * 技能草稿存取实现（#259，JdbcTemplate 原生 SQL，照 {@link JdbcSkillStore}
 * 先例）：单表 {@code skl_skill_drafts} 读写；scan_findings JSONB 经字面量
 * 序列化出入（List&lt;Map&gt; ↔ JSON 文本）。在途撞名并发兜底＝部分唯一索引
 * （DuplicateKeyException 由应用层归一为撞名回执）。
 */
@Component
public class JdbcSkillDraftStore implements SkillDraftStore {

    /** 草稿读列（两 SELECT 共形；顺序即 {@link #recordOf} 下标）。 */
    private static final String DRAFT_SELECT_PREFIX = """
            SELECT id, name, description, content, project_id, run_id, slot,
                   scan_verdict, scan_findings, status, operator_id, operator_name,
                   reviewed_at, reject_reason, created_at
            FROM skl_skill_drafts
            """;

    private static final String FIND_PENDING_SQL = DRAFT_SELECT_PREFIX
            + """
            WHERE status = ?
            ORDER BY created_at DESC, id DESC
            """;

    private static final String FIND_SQL = DRAFT_SELECT_PREFIX + " WHERE id = ?";

    private static final String EXISTS_PENDING_BY_NAME_SQL =
            "SELECT EXISTS(SELECT 1 FROM skl_skill_drafts WHERE name = ? AND status = ?)";

    private static final String INSERT_SQL = """
            INSERT INTO skl_skill_drafts
                (id, name, description, content, project_id, run_id, slot,
                 scan_verdict, scan_findings, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            """;

    /** 审结 CAS：只在途行可翻——并发审结零行命中，调用方定冲突语义。 */
    private static final String MARK_PROMOTED_SQL = """
            UPDATE skl_skill_drafts
            SET status = ?, operator_id = ?, operator_name = ?,
                reviewed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE id = ? AND status = ?
            """;

    private static final String MARK_REJECTED_SQL = """
            UPDATE skl_skill_drafts
            SET status = ?, reject_reason = ?, operator_id = ?, operator_name = ?,
                reviewed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE id = ? AND status = ?
            """;

    /** 项目删除清理（#264 T6）：只在途行随项目清，终态留档。 */
    private static final String DELETE_PENDING_BY_PROJECT_SQL =
            "DELETE FROM skl_skill_drafts WHERE project_id = ? AND status = ?";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JdbcSkillDraftStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean existsPendingByName(String name) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(EXISTS_PENDING_BY_NAME_SQL,
                Boolean.class, name, SkillDraftStatus.PENDING.getCode()));
    }

    @Override
    public void insert(SkillDraftRecord record) {
        jdbcTemplate.update(INSERT_SQL,
                record.id(), record.name(), record.description(), record.content(),
                record.projectId(), record.runId(), record.slot().key(),
                record.scanVerdict(), jsonOf(record.scanFindings()),
                record.status().getCode());
    }

    @Override
    public List<SkillDraftRecord> findPending() {
        return jdbcTemplate.query(FIND_PENDING_SQL, (rs, rowNum) -> recordOf(rs),
                SkillDraftStatus.PENDING.getCode());
    }

    @Override
    public SkillDraftRecord find(long id) {
        List<SkillDraftRecord> records = jdbcTemplate.query(FIND_SQL,
                (rs, rowNum) -> recordOf(rs), id);
        return records.isEmpty() ? null : records.get(0);
    }

    @Override
    public boolean markPromoted(long id, Operator operator) {
        return jdbcTemplate.update(MARK_PROMOTED_SQL,
                SkillDraftStatus.PROMOTED.getCode(), operator.id(), operator.name(),
                id, SkillDraftStatus.PENDING.getCode()) > 0;
    }

    @Override
    public boolean markRejected(long id, String reason, Operator operator) {
        return jdbcTemplate.update(MARK_REJECTED_SQL,
                SkillDraftStatus.REJECTED.getCode(), reason, operator.id(), operator.name(),
                id, SkillDraftStatus.PENDING.getCode()) > 0;
    }

    @Override
    public void deletePendingByProject(long projectId) {
        jdbcTemplate.update(DELETE_PENDING_BY_PROJECT_SQL,
                projectId, SkillDraftStatus.PENDING.getCode());
    }

    private SkillDraftRecord recordOf(ResultSet rs) throws SQLException {
        String slotKey = rs.getString(7);
        return new SkillDraftRecord(
                rs.getLong(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getLong(5),
                rs.getString(6),
                // 库内槽位键即写入时的稳定键，读不回是存储腐坏——如实炸出
                SkillSlot.byKey(slotKey).orElseThrow(() -> new IllegalStateException(
                        "草稿槽位键解析失败: " + slotKey)),
                rs.getString(8),
                findingsOf(rs.getString(9)),
                BaseEnum.requireByCode(SkillDraftStatus.class, rs.getInt(10)),
                rs.getString(11),
                rs.getString(12),
                rs.getTimestamp(13) == null ? null : rs.getTimestamp(13).toLocalDateTime(),
                rs.getString(14),
                rs.getTimestamp(15).toLocalDateTime());
    }

    private List<Map<String, Object>> findingsOf(String json) throws SQLException {
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() { });
        }
        catch (JsonProcessingException e) {
            // 库内 JSONB 即写入时的扫描回执，读不回是存储腐坏——如实炸出
            throw new SQLException("草稿扫描 findings 解析失败", e);
        }
    }

    private String jsonOf(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("草稿 JSONB 序列化失败", e);
        }
    }
}
