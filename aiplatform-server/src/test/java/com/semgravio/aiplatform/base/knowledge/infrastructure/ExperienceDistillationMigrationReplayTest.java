package com.semgravio.aiplatform.base.knowledge.infrastructure;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.semgravio.aiplatform.IntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V93 事后提炼与候选经验落库迁移回放（#641，阶段六-3；父票 #637）。
 * 测试库是迁移已应用的现态，收编形态以 Flyway 回放钉死（照
 * {@code LegacyVersionRetirementMigrationReplayTest} 先例：独立 schema 先 migrate
 * 到 V92、手工造旧草稿行，再 migrate 过 V93 断言收敛）。
 *
 * <p>断言面：旧草稿行清空；草稿表来源五列在场；在途部分唯一索引拆除；提炼处理
 * 记录表建成（含 project+version 唯一约束）；干净库（V0→V93 一次迁完）同样可回放。</p>
 */
@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExperienceDistillationMigrationReplayTest {

    /** 本迁移版本号（#641）。 */
    private static final int MIGRATION_VERSION = 93;

    /** 回放专用 schema（随跑随弃，不碰 public）。 */
    private static final String REPLAY_SCHEMA = "t641_replay";

    private static final Pattern VERSIONED_FILE = Pattern.compile("V(\\d+)__");

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
    void given_old_drafts_when_migrate_v93_then_cleared_columns_added_and_index_dropped() {
        migrateTo(preVersion());
        // pre 形态留证：旧自荐草稿两行（无来源三件）
        insertDraft(11, "CASE", "旧草稿一", 1);
        insertDraft(12, "CASE", "旧草稿二", 2);

        migrate();

        // 旧草稿行清空
        assertThat(count("SELECT count(*) FROM " + REPLAY_SCHEMA
                + ".knw_sediment_drafts")).isZero();
        // 来源五列在场
        assertThat(count("SELECT count(*) FROM information_schema.columns"
                + " WHERE table_schema = '" + REPLAY_SCHEMA + "'"
                + " AND table_name = 'knw_sediment_drafts'"
                + " AND column_name IN ('source_project_name', 'source_version_no',"
                + " 'source_commit_hash', 'source_sentence', 'distill_batch_id')"))
                .isEqualTo(5);
        // 在途部分唯一索引拆除（新行固定占序号 1 不再被旧上限挡住）
        assertThat(count("SELECT count(*) FROM pg_indexes WHERE schemaname = '"
                + REPLAY_SCHEMA + "' AND indexname = 'uq_knw_sediment_drafts_pending_slot'"))
                .isZero();
        // 提炼处理记录表建成（含唯一约束）
        assertThat(count("SELECT count(*) FROM information_schema.tables"
                + " WHERE table_schema = '" + REPLAY_SCHEMA + "'"
                + " AND table_name = 'knw_experience_distillations'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM information_schema.table_constraints"
                + " WHERE table_schema = '" + REPLAY_SCHEMA + "'"
                + " AND table_name = 'knw_experience_distillations'"
                + " AND constraint_name = 'uq_knw_experience_distillations_version'"))
                .isEqualTo(1);
    }

    @Test
    void given_fresh_schema_when_migrate_all_to_v93_then_new_shape_present() {
        migrate();

        assertThat(count("SELECT count(*) FROM information_schema.tables"
                + " WHERE table_schema = '" + REPLAY_SCHEMA + "'"
                + " AND table_name = 'knw_experience_distillations'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM information_schema.columns"
                + " WHERE table_schema = '" + REPLAY_SCHEMA + "'"
                + " AND table_name = 'knw_sediment_drafts'"
                + " AND column_name = 'distill_batch_id'")).isEqualTo(1);
    }

    // -------- 回放脚手架（照 LegacyVersionRetirementMigrationReplayTest） --------

    private void insertDraft(long id, String kind, String title, int inflightSlot) {
        statementExecute("INSERT INTO " + REPLAY_SCHEMA + ".knw_sediment_drafts"
                + " (id, kind, title, summary, content, project_id, run_id, slot,"
                + " inflight_slot, scan_verdict, scan_findings, status)"
                + " VALUES (" + id + ", '" + kind + "', '" + title + "', '摘要', '正文', 1,"
                + " 'run-old', 'executor', " + inflightSlot + ", 'SAFE', '[]'::jsonb, 1)");
    }

    /** V93 前一版（按 classpath 现存迁移文件动态取）。 */
    private int preVersion() {
        List<Integer> versions = new ArrayList<>();
        try {
            for (var resource : new PathMatchingResourcePatternResolver()
                    .getResources("classpath:db/migration/*.sql")) {
                Matcher matcher = VERSIONED_FILE.matcher(resource.getFilename());
                if (matcher.find() && Integer.parseInt(matcher.group(1)) < MIGRATION_VERSION) {
                    versions.add(Integer.parseInt(matcher.group(1)));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("迁移文件枚举失败", e);
        }
        return versions.stream().mapToInt(Integer::intValue).max()
                .orElseThrow(() -> new IllegalStateException("V93 之前无迁移文件"));
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
