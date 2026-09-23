package com.aieducenter.aiplatform.base.skills.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.cartisan.core.domain.BaseEnum;

import com.aieducenter.aiplatform.base.skills.domain.enums.SkillSlot;
import com.aieducenter.aiplatform.base.skills.domain.enums.SkillStatus;
import com.aieducenter.aiplatform.base.skills.domain.model.Operator;
import com.aieducenter.aiplatform.base.skills.domain.model.ParsedSkill;
import com.aieducenter.aiplatform.base.skills.domain.model.SkillRecord;
import com.aieducenter.aiplatform.base.skills.domain.model.SkillUpdateTrace;
import com.aieducenter.aiplatform.base.skills.domain.repository.SkillStore;

/**
 * 技能库存取实现（JdbcTemplate 原生 SQL，照 {@code PgvectorKnowledgeStore}
 * 先例）：条目表 {@code skl_skills} 单表读写（读＝#247 清单/详情；写＝#248
 * 安装/启停/卸载）＋指派表 {@code skl_slot_assignments}（#249 槽位读写，join
 * 回条目列共形）＋包表 {@code skl_packages}／更新留痕表 {@code skl_update_
 * traces}（#250 检查态与版本留痕）。条目读一律 LEFT JOIN 包表带上「有新版」
 * 标记（null＝未检查过）；frontmatter/resources/exclude_dirs JSONB 经字面量
 * 序列化出入（Map/List ↔ JSON 文本），不挂 JPA 映射面。
 */
@Component
public class JdbcSkillStore implements SkillStore {

    /** 条目读列（#250 起四 SELECT 共形带包表标记列；顺序即 {@link #recordOf} 下标）。 */
    private static final String SKILL_SELECT_PREFIX = """
            SELECT s.id, s.name, s.description, s.source_package, s.version, s.status,
                   s.frontmatter, s.content, s.resources, s.operator_id, s.operator_name, p.update_available
            FROM skl_skills s
            LEFT JOIN skl_packages p ON p.source_package = s.source_package
            """;

    private static final String FIND_ALL_SQL = SKILL_SELECT_PREFIX
            + " ORDER BY s.source_package, s.name";

    private static final String FIND_SQL = SKILL_SELECT_PREFIX + " WHERE s.id = ?";

    private static final String EXISTS_BY_SOURCE_SQL =
            "SELECT EXISTS(SELECT 1 FROM skl_skills WHERE source_package = ?)";

    private static final String INSERT_SQL = """
            INSERT INTO skl_skills
                (id, name, description, source_package, version, status, frontmatter, content,
                 resources, operator_id, operator_name)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?)
            """;

    private static final String UPDATE_STATUS_SQL = """
            UPDATE skl_skills
            SET status = ?, operator_id = ?, operator_name = ?, updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """;

    private static final String DELETE_SQL =
            "DELETE FROM skl_skills WHERE id = ?";

    /** 指派 join 回条目列（管理读面与装配视图共形；a join＋包表标记列同形）。 */
    private static final String FIND_ASSIGNED_SQL = SKILL_SELECT_PREFIX
            + """
            JOIN skl_slot_assignments a ON a.skill_id = s.id
            WHERE a.slot = ?
            ORDER BY s.source_package, s.name
            """;

    /** 装配合成视图：指派行 join 启用条目（停用即退出候选——状态过滤在 SQL 单点）。 */
    private static final String FIND_ENABLED_ASSIGNED_SQL = SKILL_SELECT_PREFIX
            + """
            JOIN skl_slot_assignments a ON a.skill_id = s.id
            WHERE a.slot = ? AND s.status = ?
            ORDER BY s.source_package, s.name
            """;

    private static final String REPLACE_DELETE_SQL =
            "DELETE FROM skl_slot_assignments WHERE slot = ?";

    private static final String REPLACE_INSERT_SQL = """
            INSERT INTO skl_slot_assignments (slot, skill_id, operator_id, operator_name)
            VALUES (?, ?, ?, ?)
            """;

    private static final String EXISTS_ASSIGNMENT_SQL =
            "SELECT EXISTS(SELECT 1 FROM skl_slot_assignments WHERE skill_id = ?)";

    // ========== #250：包表／更新留痕表 ==========

    private static final String FIND_INSTALLED_VERSIONS_SQL = """
            SELECT source_package, MAX(version) FROM skl_skills GROUP BY source_package
            """;

    private static final String FIND_EXCLUDE_DIRS_SQL = """
            SELECT exclude_dirs FROM skl_packages WHERE source_package = ?
            """;

    /** 安装落包行：upsert 整体重置（孤儿包行重装时排除名单与检查态一并重写）。 */
    private static final String INSTALL_PACKAGE_SQL = """
            INSERT INTO skl_packages (source_package, exclude_dirs, remote_head, update_available, checked_at)
            VALUES (?, ?::jsonb, ?, FALSE, CURRENT_TIMESTAMP)
            ON CONFLICT (source_package) DO UPDATE SET
                exclude_dirs = EXCLUDED.exclude_dirs,
                remote_head = EXCLUDED.remote_head,
                update_available = FALSE,
                checked_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            """;

    /** 检查结果 upsert：冲突分支只动检查三列——存量排除名单不被动（扫描不装不卸）。 */
    private static final String RECORD_CHECK_RESULT_SQL = """
            INSERT INTO skl_packages (source_package, exclude_dirs, remote_head, update_available, checked_at)
            VALUES (?, '[]', ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (source_package) DO UPDATE SET
                remote_head = EXCLUDED.remote_head,
                update_available = EXCLUDED.update_available,
                checked_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            """;

    private static final String DELETE_PACKAGE_IF_NO_SKILLS_SQL = """
            DELETE FROM skl_packages p
            WHERE p.source_package = ?
              AND NOT EXISTS (SELECT 1 FROM skl_skills s WHERE s.source_package = p.source_package)
            """;

    private static final String FIND_BY_SOURCE_PACKAGE_SQL = SKILL_SELECT_PREFIX
            + " WHERE s.source_package = ? ORDER BY s.name";

    private static final String REFRESH_FROM_SNAPSHOT_SQL = """
            UPDATE skl_skills
            SET description = ?, frontmatter = ?::jsonb, content = ?, resources = ?::jsonb,
                version = ?, operator_id = ?, operator_name = ?, updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """;

    private static final String INSERT_TRACE_SQL = """
            INSERT INTO skl_update_traces
                (id, source_package, from_version, to_version, operator_id, operator_name)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND_TRACES_SQL = """
            SELECT id, source_package, from_version, to_version, operator_id, operator_name, created_at
            FROM skl_update_traces
            WHERE source_package = ?
            ORDER BY created_at DESC, id DESC
            """;

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
                record.content(), jsonOf(record.resources()),
                record.operatorId(), record.operatorName()
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

    @Override
    public List<SkillRecord> findAssigned(SkillSlot slot) {
        return jdbcTemplate.query(FIND_ASSIGNED_SQL,
                (rs, rowNum) -> recordOf(rs), slot.key());
    }

    @Override
    public List<SkillRecord> findEnabledAssigned(SkillSlot slot) {
        return jdbcTemplate.query(FIND_ENABLED_ASSIGNED_SQL,
                (rs, rowNum) -> recordOf(rs), slot.key(), SkillStatus.ENABLED.getCode());
    }

    @Override
    public void replaceAssignments(SkillSlot slot, List<Long> skillIds, Operator operator) {
        // 整包替换（PUT 全量语义）：清行＋批插，事务由应用层 @Transactional 界定
        jdbcTemplate.update(REPLACE_DELETE_SQL, slot.key());
        jdbcTemplate.batchUpdate(REPLACE_INSERT_SQL, skillIds.stream().map(skillId -> new Object[] {
                slot.key(), skillId, operator.id(), operator.name()
        }).toList());
    }

    @Override
    public boolean existsAssignmentForSkill(long skillId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(EXISTS_ASSIGNMENT_SQL,
                Boolean.class, skillId));
    }

    @Override
    public Map<String, String> findInstalledVersions() {
        Map<String, String> versions = new LinkedHashMap<>();
        jdbcTemplate.query(FIND_INSTALLED_VERSIONS_SQL, rs -> {
            versions.put(rs.getString(1), rs.getString(2));
        });
        return versions;
    }

    @Override
    public List<String> findExcludeDirs(String sourcePackage) {
        List<List<String>> rows = jdbcTemplate.query(FIND_EXCLUDE_DIRS_SQL, (rs, rowNum) -> {
            try {
                return List.of(objectMapper.readValue(rs.getString(1), String[].class));
            }
            catch (JsonProcessingException e) {
                // 库内 JSONB 即安装时写入的名单，读不回是存储腐坏——如实炸出
                throw new SQLException("技能排除名单解析失败", e);
            }
        }, sourcePackage);
        // 包行不在（T4 前存量安装）：空名单重拉（存量装时排除段无从读回的边缘，
        // 口径见接口 javadoc）
        return rows.isEmpty() ? List.of() : rows.get(0);
    }

    @Override
    public void installPackage(String sourcePackage, List<String> excludeDirs, String headVersion) {
        jdbcTemplate.update(INSTALL_PACKAGE_SQL, sourcePackage, jsonOf(excludeDirs), headVersion);
    }

    @Override
    public void recordCheckResult(String sourcePackage, String remoteHead, boolean updateAvailable) {
        // 单语句 upsert 原子——扫描轮逐包独立落库（一包失败不裹挟他包）
        jdbcTemplate.update(RECORD_CHECK_RESULT_SQL, sourcePackage, remoteHead, updateAvailable);
    }

    @Override
    public void deletePackageIfNoSkills(String sourcePackage) {
        jdbcTemplate.update(DELETE_PACKAGE_IF_NO_SKILLS_SQL, sourcePackage);
    }

    @Override
    public List<SkillRecord> findBySourcePackage(String sourcePackage) {
        return jdbcTemplate.query(FIND_BY_SOURCE_PACKAGE_SQL,
                (rs, rowNum) -> recordOf(rs), sourcePackage);
    }

    @Override
    public void refreshFromSnapshot(long id, ParsedSkill skill, String version, Operator operator) {
        jdbcTemplate.update(REFRESH_FROM_SNAPSHOT_SQL, skill.description(),
                jsonOf(skill.frontmatter()), skill.content(), jsonOf(skill.resources()),
                version, operator.id(), operator.name(), id);
    }

    @Override
    public void insertTrace(SkillUpdateTrace trace) {
        jdbcTemplate.update(INSERT_TRACE_SQL, trace.id(), trace.sourcePackage(),
                trace.fromVersion(), trace.toVersion(), trace.operatorId(), trace.operatorName());
    }

    @Override
    public List<SkillUpdateTrace> findTraces(String sourcePackage) {
        return jdbcTemplate.query(FIND_TRACES_SQL, (rs, rowNum) -> new SkillUpdateTrace(
                rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6),
                rs.getTimestamp(7).toLocalDateTime()), sourcePackage);
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
                resourcesOf(rs.getString(9)),
                rs.getString(10),
                rs.getString(11),
                // LEFT JOIN 包表：无包行即 null（未检查过），非 false
                rs.getObject(12, Boolean.class));
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

    /** scripts 资源面 JSONB → map（#253；读不回＝存储腐坏，与 frontmatter 同律炸出）。 */
    private Map<String, String> resourcesOf(String json) throws SQLException {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() { });
        }
        catch (JsonProcessingException e) {
            throw new SQLException("技能 resources 解析失败", e);
        }
    }

    /** 解析态 → JSONB 字面量（装时一次性写入，序列化失败即安装失败，如实炸出）。 */
    private String jsonOf(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("技能 JSONB 序列化失败", e);
        }
    }
}
