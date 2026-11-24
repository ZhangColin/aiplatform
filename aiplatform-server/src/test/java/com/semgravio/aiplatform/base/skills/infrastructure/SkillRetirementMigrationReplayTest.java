package com.semgravio.aiplatform.base.skills.infrastructure;

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
 * V91 阶段六技能清场迁移回放（#638，父票 #637；ADR-0058）。测试库是迁移已应用的
 * 现态，收编形态以 Flyway 回放钉死（照 {@code LegacyVersionRetirementMigrationReplayTest}
 * 先例：独立 schema 先 migrate 到 V90、手工造 pre-migration 形态，再 migrate 过
 * V91 断言拆除）。
 *
 * <p>断言面：{@code precheck} 边缘点配置行与留痕行删除（naming 等其余边缘点照留）；
 * {@code subagent} 存量指派行删除（main／executor 指派照留）；{@code skl_skill_drafts}
 * 整表拆除且不可再写；干净库（V0→V91 一次迁完）同样可回放。</p>
 *
 * <p>回放 schema 与 Spring 上下文 public schema 同库不同派，互不触碰；随跑随弃。</p>
 */
@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SkillRetirementMigrationReplayTest {

    /** 本迁移版本号（#638）。 */
    private static final int MIGRATION_VERSION = 91;

    /** 回放专用 schema（随跑随弃，不碰 public）。 */
    private static final String REPLAY_SCHEMA = "t638_replay";

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
    void given_precheck_config_subagent_assignment_and_drafts_when_migrate_v91_then_removed() {
        migrateTo(preVersion());
        // pre 形态留证：技能行（供指派 FK）＋subagent/main 两槽指派
        statementExecute("INSERT INTO " + REPLAY_SCHEMA + ".skl_skills"
                + " (id, name, description, source_package, version, frontmatter, content)"
                + " VALUES (501, 't638-skill', '简介', 'pkg', 'v1', '{}'::jsonb, '正文')");
        statementExecute("INSERT INTO " + REPLAY_SCHEMA + ".skl_slot_assignments"
                + " (slot, skill_id) VALUES ('subagent', 501)");
        statementExecute("INSERT INTO " + REPLAY_SCHEMA + ".skl_slot_assignments"
                + " (slot, skill_id) VALUES ('main', 501)");
        // precheck 与 naming 两条边缘点配置行＋留痕行（只应删 precheck）
        statementExecute("INSERT INTO " + REPLAY_SCHEMA + ".prj_agent_configs"
                + " (agent_key) VALUES ('precheck')");
        statementExecute("INSERT INTO " + REPLAY_SCHEMA + ".prj_agent_configs"
                + " (agent_key) VALUES ('naming')");
        insertTrace(11, "precheck");
        insertTrace(12, "naming");
        // 技能草稿行（表将整表拆除）
        statementExecute("INSERT INTO " + REPLAY_SCHEMA + ".skl_skill_drafts"
                + " (id, name, description, content, project_id, run_id, slot,"
                + "  scan_verdict, scan_findings)"
                + " VALUES (21, 't638-draft', '简介', '正文', 1, 'run-1', 'main',"
                + "  'SAFE', '[]'::jsonb)");

        migrate();

        // precheck 配置行与留痕行删除；naming 等其余边缘点照留
        assertThat(count("SELECT count(*) FROM " + REPLAY_SCHEMA
                + ".prj_agent_configs WHERE agent_key = 'precheck'")).isZero();
        assertThat(count("SELECT count(*) FROM " + REPLAY_SCHEMA
                + ".prj_agent_configs WHERE agent_key = 'naming'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM " + REPLAY_SCHEMA
                + ".prj_agent_config_traces WHERE agent_key = 'precheck'")).isZero();
        assertThat(count("SELECT count(*) FROM " + REPLAY_SCHEMA
                + ".prj_agent_config_traces WHERE agent_key = 'naming'")).isEqualTo(1);
        // subagent 指派行删除；main 指派照留（槽位收窄只删 subagent）
        assertThat(count("SELECT count(*) FROM " + REPLAY_SCHEMA
                + ".skl_slot_assignments WHERE slot = 'subagent'")).isZero();
        assertThat(count("SELECT count(*) FROM " + REPLAY_SCHEMA
                + ".skl_slot_assignments WHERE slot = 'main'")).isEqualTo(1);
        // 技能草稿表整表拆除、不可再写
        assertThat(count("SELECT count(*) FROM information_schema.tables"
                + " WHERE table_schema = '" + REPLAY_SCHEMA + "'"
                + " AND table_name = 'skl_skill_drafts'")).isZero();
        assertThatThrownBy(() -> statementExecute("INSERT INTO " + REPLAY_SCHEMA
                + ".skl_skill_drafts (id, name, description, content, project_id, run_id,"
                + " slot, scan_verdict, scan_findings) VALUES (22, 'x', 'y', 'z', 1,"
                + " 'run-2', 'main', 'SAFE', '[]'::jsonb)"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void given_fresh_schema_when_migrate_all_to_v91_then_draft_table_absent_at_end() {
        // 干净库回放：V0→V91 一次迁完（V21 建草稿表后 V91 再拆，历史链完整可跑）
        migrate();

        assertThat(count("SELECT count(*) FROM information_schema.tables"
                + " WHERE table_schema = '" + REPLAY_SCHEMA + "'"
                + " AND table_name = 'skl_skill_drafts'")).isZero();
    }

    // -------- 回放脚手架（照 LegacyVersionRetirementMigrationReplayTest） --------

    /** V91 前一版（按 classpath 现存迁移文件动态取）。 */
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
                .orElseThrow(() -> new IllegalStateException("V91 之前无迁移文件"));
    }

    private void insertTrace(long id, String agentKey) {
        statementExecute("INSERT INTO " + REPLAY_SCHEMA + ".prj_agent_config_traces"
                + " (id, agent_key, old_web_search_enabled, old_fetch_url_enabled,"
                + "  new_web_search_enabled, new_fetch_url_enabled)"
                + " VALUES (" + id + ", '" + agentKey + "', true, true, true, true)");
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
