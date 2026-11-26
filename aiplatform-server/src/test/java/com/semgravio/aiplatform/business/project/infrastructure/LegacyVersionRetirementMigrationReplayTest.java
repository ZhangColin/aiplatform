package com.semgravio.aiplatform.business.project.infrastructure;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V90 旧 Demo/PRD 版本数据与端点退场迁移回放（#630，阶段四-9；父票 #621）。
 * 测试库是迁移已应用的现态，收编形态以 Flyway 回放钉死（照
 * {@code DesignSpecCarrierRetirementMigrationReplayTest} 先例：独立 schema 先
 * migrate 到 V89、手工造 pre-migration 形态，再 migrate 过 V90 断言拆除）。
 *
 * <p>断言面：{@code prj_prd_versions}／{@code prj_demo_versions} 整表拆除；
 * {@code prj_projects} 的确认列由 {@code confirmed_demo_version_no/confirmed_demo_at}
 * 改义更名为 {@code confirmed_version_no/confirmed_version_at} 且存量值照留；旧表
 * 删除后不可再写；干净库（V0→V90 一次迁完）同样可回放。</p>
 *
 * <p>回放 schema 与 Spring 上下文 public schema 同库不同派，互不触碰；随跑随弃。</p>
 */
@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LegacyVersionRetirementMigrationReplayTest {

    /** 本迁移版本号（#630）。 */
    private static final int MIGRATION_VERSION = 90;

    /** 回放专用 schema（随跑随弃，不碰 public）。 */
    private static final String REPLAY_SCHEMA = "t630_replay";

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
    void given_legacy_version_tables_and_confirm_columns_when_migrate_v90_then_dropped_and_renamed() {
        migrateTo(preVersion());
        // pre 形态留证：项目行带旧确认列、两张旧版本表各有版本行
        statementExecute("INSERT INTO " + REPLAY_SCHEMA + ".prj_projects"
                + " (id, name, type, workspace_id, confirmed_demo_version_no, confirmed_demo_at)"
                + " VALUES (1, '存量项目', 1, 100, 3, CURRENT_TIMESTAMP)");
        statementExecute("INSERT INTO " + REPLAY_SCHEMA + ".prj_prd_versions"
                + " (id, project_id, version_no, content, confirmed_at, prd_produced_at)"
                + " VALUES (11, 1, 1, '# PRD', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        statementExecute("INSERT INTO " + REPLAY_SCHEMA + ".prj_demo_versions"
                + " (id, project_id, version_no, prd_version_no, run_id)"
                + " VALUES (21, 1, 1, 1, 'run-1')");

        migrate();

        // 两张旧版本表整表拆除
        assertThat(count("SELECT count(*) FROM information_schema.tables"
                + " WHERE table_schema = '" + REPLAY_SCHEMA + "'"
                + " AND table_name IN ('prj_prd_versions', 'prj_demo_versions')")).isZero();
        // 旧确认列拆除、新语义列更名到位
        assertThat(count("SELECT count(*) FROM information_schema.columns"
                + " WHERE table_schema = '" + REPLAY_SCHEMA + "'"
                + " AND table_name = 'prj_projects'"
                + " AND column_name IN ('confirmed_demo_version_no', 'confirmed_demo_at')")).isZero();
        assertThat(count("SELECT count(*) FROM information_schema.columns"
                + " WHERE table_schema = '" + REPLAY_SCHEMA + "'"
                + " AND table_name = 'prj_projects'"
                + " AND column_name IN ('confirmed_version_no', 'confirmed_version_at')"))
                .isEqualTo(2);
        // 存量确认指针值照留（改义更名不改写值）
        assertThat(count("SELECT confirmed_version_no FROM " + REPLAY_SCHEMA
                + ".prj_projects WHERE id = 1")).isEqualTo(3);
        // 旧版本表已不在——再写入结构性拒绝
        assertThatThrownBy(() -> statementExecute("INSERT INTO " + REPLAY_SCHEMA
                + ".prj_prd_versions (id, project_id, version_no, content, confirmed_at,"
                + " prd_produced_at) VALUES (12, 1, 2, '# PRD 2', CURRENT_TIMESTAMP,"
                + " CURRENT_TIMESTAMP)"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void given_fresh_schema_when_migrate_all_to_v90_then_tables_absent_at_end() {
        // 干净库回放：V0→V90 一次迁完（V32/V35 建表后 V90 再拆，历史链完整可跑）
        migrate();

        assertThat(count("SELECT count(*) FROM information_schema.tables"
                + " WHERE table_schema = '" + REPLAY_SCHEMA + "'"
                + " AND table_name IN ('prj_prd_versions', 'prj_demo_versions')")).isZero();
        assertThat(count("SELECT count(*) FROM information_schema.columns"
                + " WHERE table_schema = '" + REPLAY_SCHEMA + "'"
                + " AND table_name = 'prj_projects'"
                + " AND column_name IN ('confirmed_version_no', 'confirmed_version_at')"))
                .isEqualTo(2);
    }

    // -------- 回放脚手架（照 DesignSpecCarrierRetirementMigrationReplayTest） --------

    /** V90 前一版（按 classpath 现存迁移文件动态取）。 */
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
                .orElseThrow(() -> new IllegalStateException("V90 之前无迁移文件"));
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
