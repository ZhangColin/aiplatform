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
import com.aieducenter.aiplatform.base.skills.domain.model.SkillRecord;
import com.aieducenter.aiplatform.base.skills.domain.repository.SkillStore;

/**
 * 技能库存取实现（JdbcTemplate 原生 SQL，照 {@code PgvectorKnowledgeStore}
 * 先例）：条目表 {@code skl_skills} 单表读（T1 只读侧；写口 #248）。frontmatter
 * JSONB 经字面量序列化读入（getString → JSON 文本 → Map），不挂 JPA 映射面。
 */
@Component
public class JdbcSkillStore implements SkillStore {

    /** 条目读列（清单/详情共形全列；顺序即 {@link #recordOf} 下标）。 */
    private static final String SKILL_COLUMNS =
            "id, name, description, source_package, version, status, frontmatter, content";

    private static final String FIND_ALL_SQL =
            "SELECT " + SKILL_COLUMNS + " FROM skl_skills ORDER BY source_package, name";

    private static final String FIND_SQL =
            "SELECT " + SKILL_COLUMNS + " FROM skl_skills WHERE id = ?";

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

    private SkillRecord recordOf(ResultSet rs) throws SQLException {
        return new SkillRecord(
                rs.getLong(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                BaseEnum.requireByCode(SkillStatus.class, rs.getInt(6)),
                frontmatterOf(rs.getString(7)),
                rs.getString(8));
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
}
