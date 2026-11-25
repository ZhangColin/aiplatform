package com.semgravio.aiplatform.base.knowledge.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.cartisan.core.domain.BaseEnum;

import com.semgravio.aiplatform.base.knowledge.domain.enums.ExperienceDistillationStatus;
import com.semgravio.aiplatform.base.knowledge.domain.model.ExperienceDistillationRecord;
import com.semgravio.aiplatform.base.knowledge.domain.model.ProjectDistillationStats;
import com.semgravio.aiplatform.base.knowledge.domain.repository.ExperienceDistillationStore;

/**
 * 事后提炼处理记录存取实现（#641，JdbcTemplate 原生 SQL）：单表
 * {@code knw_experience_distillations} 读写；写入走 upsert（project_id + version_no
 * 唯一键，重复触发幂等、失败重试原地刷新）。updated_at 每次写入刷新。
 */
@Component
public class JdbcExperienceDistillationStore implements ExperienceDistillationStore {

    private static final String LATEST_NON_FAILED_SQL = """
            SELECT MAX(version_no) FROM knw_experience_distillations
            WHERE project_id = ? AND status IN (1, 2)
            """;

    private static final String FIND_SQL = """
            SELECT id, project_id, project_name, version_no, commit_hash, status,
                   opinion_count, missed_count, candidate_count, batch_id, error_summary,
                   created_at
            FROM knw_experience_distillations
            WHERE project_id = ? AND version_no = ?
            """;

    private static final String UPSERT_SQL = """
            INSERT INTO knw_experience_distillations
                (id, project_id, project_name, version_no, commit_hash, status,
                 opinion_count, missed_count, candidate_count, batch_id, error_summary)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (project_id, version_no) DO UPDATE SET
                project_name = EXCLUDED.project_name,
                commit_hash = EXCLUDED.commit_hash,
                status = EXCLUDED.status,
                opinion_count = EXCLUDED.opinion_count,
                missed_count = EXCLUDED.missed_count,
                candidate_count = EXCLUDED.candidate_count,
                batch_id = EXCLUDED.batch_id,
                error_summary = EXCLUDED.error_summary,
                updated_at = CURRENT_TIMESTAMP
            """;

    /** 飞轮提炼统计（#643）：项目名取最新版本记录的快照（array_agg 按版本倒序首元素）。 */
    private static final String PROJECT_STATS_SQL = """
            SELECT project_id,
                   (ARRAY_AGG(project_name ORDER BY version_no DESC))[1] AS project_name,
                   COUNT(*) FILTER (WHERE version_no > 1) AS modification_rounds,
                   COUNT(*) FILTER (WHERE version_no > 1 AND missed_count > 0) AS missed_rounds
            FROM knw_experience_distillations
            GROUP BY project_id
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcExperienceDistillationStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Integer latestNonFailedVersionNo(long projectId) {
        return jdbcTemplate.queryForObject(LATEST_NON_FAILED_SQL, Integer.class, projectId);
    }

    @Override
    public ExperienceDistillationRecord find(long projectId, int versionNo) {
        List<ExperienceDistillationRecord> records = jdbcTemplate.query(FIND_SQL,
                (rs, rowNum) -> recordOf(rs), projectId, versionNo);
        return records.isEmpty() ? null : records.get(0);
    }

    @Override
    public void upsert(ExperienceDistillationRecord record) {
        jdbcTemplate.update(UPSERT_SQL,
                record.id(), record.projectId(), record.projectName(), record.versionNo(),
                record.commitHash(), record.status().getCode(), record.opinionCount(),
                record.missedCount(), record.candidateCount(), record.batchId(),
                record.errorSummary());
    }

    @Override
    public List<ProjectDistillationStats> projectStats() {
        return jdbcTemplate.query(PROJECT_STATS_SQL, (rs, rowNum) -> new ProjectDistillationStats(
                rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getInt(4)));
    }

    private ExperienceDistillationRecord recordOf(ResultSet rs) throws SQLException {
        return new ExperienceDistillationRecord(
                rs.getLong(1),
                rs.getLong(2),
                rs.getString(3),
                rs.getInt(4),
                rs.getString(5),
                BaseEnum.requireByCode(ExperienceDistillationStatus.class, rs.getInt(6)),
                rs.getInt(7),
                rs.getInt(8),
                rs.getInt(9),
                rs.getString(10),
                rs.getString(11),
                rs.getTimestamp(12).toLocalDateTime());
    }
}
