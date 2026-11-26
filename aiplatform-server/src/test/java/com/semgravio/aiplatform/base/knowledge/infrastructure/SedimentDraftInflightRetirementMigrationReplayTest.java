package com.semgravio.aiplatform.base.knowledge.infrastructure;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.semgravio.aiplatform.IntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * V94 拆沉淀草稿在途机制迁移回放（#642，阶段六-4；父票 #637，ADR-0058）。测试库是
 * 迁移已应用的现态，收编形态以 Flyway 回放钉死（照
 * {@code ExperienceDistillationMigrationReplayTest} 先例：独立 schema）。
 *
 * <p>断言面：V93 形态仍在途序号列＋检查约束；迁过 V94 后列与检查约束拆除、部分
 * 唯一索引消失；迁移后同项目同类别可并存多条待审行（无名额限制）；干净库
 * （V0→V94 一次迁完）同样可回放。</p>
 */
@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SedimentDraftInflightRetirementMigrationReplayTest {

    /** 本迁移版本号（#642）。 */
    private static final int MIGRATION_VERSION = 94;

    /** 回放专用 schema（随跑随弃，不碰 public）。 */
    private static final String REPLAY_SCHEMA = "t642_replay";

    @Autowired
    private DataSourceProperties dataSourceProperties;

    private DataSource replayDataSource;

    @BeforeAll
    void setUpReplaySchema() {
        replayDataSource = new DriverManagerDataSource(dataSourceProperties.getUrl(),
                dataSourceProperties.getUsername(), dataSourceProperties.getPassword());
    }

    @AfterAll
    void dropReplaySchema() {
        statementExecute("DROP SCHEMA IF EXISTS " + REPLAY_SCHEMA + " CASCADE");
    }

    /** 每用例独立回放：schema 连历史一起重置，用例间不残留。 */
    @BeforeEach
    void resetReplaySchema() {
        statementExecute("DROP SCHEMA IF EXISTS " + REPLAY_SCHEMA + " CASCADE");
        statementExecute("CREATE SCHEMA " + REPLAY_SCHEMA);
    }

    @Test
    void given_v93_schema_when_migrate_v94_then_inflight_column_constraint_and_index_gone() {
        migrateTo(93);
        // pre 形态留证：V93 仍有在途序号列与检查约束
        assertThat(inflightColumnCount()).isEqualTo(1);
        assertThat(inflightConstraintCount()).isEqualTo(1);

        migrate();

        assertThat(inflightColumnCount()).isZero();
        assertThat(inflightConstraintCount()).isZero();
        assertThat(pendingSlotIndexCount()).isZero();
    }

    @Test
    void given_migrated_schema_when_insert_same_project_kind_twice_then_both_pending_rows_accepted() {
        migrate();

        // #642：同项目同类别两条待审行并存不再撞在途唯一索引/上限
        assertThatCode(() -> {
            insertPending(91);
            insertPending(92);
        }).doesNotThrowAnyException();

        assertThat(count("SELECT count(*) FROM " + REPLAY_SCHEMA
                + ".knw_sediment_drafts WHERE status = 1")).isEqualTo(2);
    }

    @Test
    void given_fresh_schema_when_migrate_all_then_no_inflight_artifacts() {
        migrate();

        assertThat(inflightColumnCount()).isZero();
        assertThat(inflightConstraintCount()).isZero();
        assertThat(pendingSlotIndexCount()).isZero();
        // 来源五列与提炼处理记录表仍在（#641 形态未被破坏）
        assertThat(count("SELECT count(*) FROM information_schema.columns"
                + " WHERE table_schema = '" + REPLAY_SCHEMA + "'"
                + " AND table_name = 'knw_sediment_drafts'"
                + " AND column_name IN ('source_project_name', 'source_version_no',"
                + " 'source_commit_hash', 'source_sentence', 'distill_batch_id')"))
                .isEqualTo(5);
    }

    // -------- 回放脚手架（照 ExperienceDistillationMigrationReplayTest） --------

    private int inflightColumnCount() {
        return count("SELECT count(*) FROM information_schema.columns"
                + " WHERE table_schema = '" + REPLAY_SCHEMA + "'"
                + " AND table_name = 'knw_sediment_drafts'"
                + " AND column_name = 'inflight_slot'");
    }

    private int inflightConstraintCount() {
        return count("SELECT count(*) FROM information_schema.table_constraints"
                + " WHERE table_schema = '" + REPLAY_SCHEMA + "'"
                + " AND table_name = 'knw_sediment_drafts'"
                + " AND constraint_name = 'ck_knw_sediment_drafts_inflight_slot'");
    }

    private int pendingSlotIndexCount() {
        return count("SELECT count(*) FROM pg_indexes WHERE schemaname = '"
                + REPLAY_SCHEMA + "' AND indexname = 'uq_knw_sediment_drafts_pending_slot'");
    }

    private void insertPending(long id) {
        statementExecute("INSERT INTO " + REPLAY_SCHEMA + ".knw_sediment_drafts"
                + " (id, kind, title, summary, content, project_id, run_id, slot,"
                + " scan_verdict, scan_findings, status)"
                + " VALUES (" + id + ", 'CASE', '同名候选 " + id + "', '摘要', '正文', 1,"
                + " 'batch-1', 'refinement', 'SAFE', '[]'::jsonb, 1)");
    }

    private void migrateTo(int version) {
        configureFlyway(String.valueOf(version)).migrate();
    }

    private void migrate() {
        configureFlyway(null).migrate();
    }

    private Flyway configureFlyway(String target) {
        var configure = Flyway.configure()
                .dataSource(replayDataSource)
                .schemas(REPLAY_SCHEMA)
                .locations("classpath:db/migration")
                .table("aiplatform-server_flyway_schema_history")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .placeholders(Map.of("volcengineApiKey", "", "deepseekApiKey", ""));
        if (target != null) {
            configure.target(target);
        }
        return configure.load();
    }

    private int count(String sql) {
        try (Connection connection = replayDataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException("查询失败：" + sql, e);
        }
    }

    private void statementExecute(String sql) {
        try (Connection connection = replayDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("执行失败：" + sql, e);
        }
    }
}
