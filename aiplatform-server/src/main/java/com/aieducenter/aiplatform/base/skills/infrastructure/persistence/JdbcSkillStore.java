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

import com.aieducenter.aiplatform.base.skills.domain.enums.SkillStatus;
import com.aieducenter.aiplatform.base.skills.domain.model.Operator;
import com.aieducenter.aiplatform.base.skills.domain.model.SkillRecord;
import com.aieducenter.aiplatform.base.skills.domain.repository.SkillStore;

/**
 * 技能库存取实现（JdbcTemplate 原生 SQL，照 {@code PgvectorKnowledgeStore}
 * 先例）：条目表 {@code skl_skills} 单表读写（读＝#247 清单/详情；写＝#248
 * 安装/启停/卸载）。frontmatter JSONB 经字面量序列化出入（Map ↔ JSON 文本），
 * 不挂 JPA 映射面。
 */
@Component
public class JdbcSkillStore implements SkillStore {

    /** 条目读列（清单/详情共形全列；顺序即 {@link #recordOf} 下标）。 */
    private static final String SKILL_COLUMNS =
            "id, name, description, source_package, version, status, frontmatter, content, operator_id, operator_name";

    private static final String FIND_ALL_SQL =
            "SELECT " + SKILL_COLUMNS + " FROM skl_skills ORDER BY source_package, name";

    private static final String FIND_SQL =
            "SELECT " + SKILL_COLUMNS + " FROM skl_skills WHERE id = ?";

    private static final String EXISTS_BY_SOURCE_SQL =
            "SELECT EXISTS(SELECT 1 FROM skl_skills WHERE source_package = ?)";

    private static final String INSERT_SQL = """
            INSERT INTO skl_skills
                (id, name, description, source_package, version, status, frontmatter, content,
                 operator_id, operator_name)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
            """;

    private static final String UPDATE_STATUS_SQL = """
            UPDATE skl_skills
            SET status = ?, operator_id = ?, operator_name = ?, updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """;

    private static final String DELETE_SQL =
            "DELETE FROM skl_skills WHERE id = ?";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JdbcSkillStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<SkillRecord> findAll() {
        return jdbcTemplate.query(FIND_ALL_SQL, (rs, rowNum) -> recordOf(rs));
    }

    @Override
    public SkillRecord find(long id) {
        List<SkillRecord> records = jdbcTemplate.query(FIND_SQL,
                (rs, rowNum) -> recordOf(rs), id);
        return records.isEmpty() ? null : records.get(0);
    }

    @Override
    public boolean existsBySourcePackage(String sourcePackage) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(EXISTS_BY_SOURCE_SQL,
                Boolean.class, sourcePackage));
    }

    @Override
    public void insertAll(List<SkillRecord> records) {
        jdbcTemplate.batchUpdate(INSERT_SQL, records.stream().map(record -> new Object[] {
                record.id(), record.name(), record.description(), record.sourcePackage(),
                record.version(), record.status().getCode(), jsonOf(record.frontmatter()),
                record.content(), record.operatorId(), record.operatorName()
        }).toList());
    }

    @Override
    public void updateStatus(long id, SkillStatus status, Operator operator) {
        jdbcTemplate.update(UPDATE_STATUS_SQL, status.getCode(),
                operator.id(), operator.name(), id);
    }

    @Override
    public boolean delete(long id) {
        return jdbcTemplate.update(DELETE_SQL, id) > 0;
    }

    private SkillRecord recordOf(ResultSet rs) throws SQLException {
        return new SkillRecord(
                rs.getLong(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                BaseEnum.requireByCode(SkillStatus.class, rs.getInt(6)),
                frontmatterOf(rs.getString(7)),
                rs.getString(8),
                rs.getString(9),
                rs.getString(10));
    }

    private Map<String, Object> frontmatterOf(String json) throws SQLException {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        }
        catch (JsonProcessingException e) {
            // 库内 JSONB 即安装时写入的解析态，读不回是存储腐坏——如实炸出
            throw new SQLException("技能 frontmatter 解析失败", e);
        }
    }

    /** 解析态 → JSONB 字面量（装时一次性写入，序列化失败即安装失败，如实炸出）。 */
    private String jsonOf(Map<String, Object> frontmatter) {
        try {
            return objectMapper.writeValueAsString(frontmatter);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("技能 frontmatter 序列化失败", e);
        }
    }
}
