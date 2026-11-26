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

/**
 * V81 模型配置改名迁移回放（#554，spec 概念正名「模型档位」→「模型配置」——
 * 纯改名、数据零变化）：照 {@code ModelChannelHaMigrationReplayTest}（#556）先例，
 * 独立 schema 回放钉死两条路径：
 *
 * <ul>
 *   <li><b>存量库（V80 态）回放</b>：五张表族改名（prj_model_configs／
 *       config_candidates／config_traces／config_switch_traces／config_candidate_
 *       traces）、tier_key 列族改 config_key、约束名随表族（主键/外键）、
 *       agent_configs 引用腿三族九列（model_id→model_config_key 等）——全部到位
 *       且<b>数据行零丢失零变换</b>（flash/pro 种子行值原样＋预植覆盖行/留痕行/
 *       切换留痕行值原样）。</li>
 *   <li><b>全新库首启连续回放</b>：V1 起干净 schema 全链回放（V44 种子→V79 收敛
 *       →V80→V81 改名）成功，终态与新名断言一致。</li>
 * </ul>
 *
 * <p>边界：V44-V47/V79/V80 原文零改动（Flyway 只增不改——已应用迁移 checksum
 * 不可动，V56 事故口径）；display_name 库值（「快模型档」等）是数据不随本迁移。</p>
 */
@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModelConfigRenameMigrationReplayTest {

    /** 本迁移版本号（#554）。 */
    private static final int MIGRATION_VERSION = 81;

    /** 回放专用 schema（随跑随弃，不碰 public）。 */
    private static final String REPLAY_SCHEMA = "t554_replay";

    private static final Pattern VERSIONED_FILE = Pattern.compile("V(\\d+)__");

    @Autowired
    private DataSourceProperties dataSourceProperties;

    private DataSource replayDataSource;

    @BeforeAll
    void setUpReplaySchema() {
        replayDataSource = new DriverManagerDataSource(dataSourceProperties.getUrl(),
                dataSourceProperties.getUsername(), dataSourceProperties.getPassword());
        // 配置镜像 application.yml 的 spring.flyway（表名/baseline 口径），仅 schema
        // 不同；target 由 migrateTo 自带。V44 种子 placeholder 落空（NULLIF→NULL＝
        // 未配置态）不影响本迁移断言（V81 纯改名，不依赖 key 值）。
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
    void given_v80_state_when_migrate_v81_then_renamed_and_data_intact() {
        migrateTo(preVersion());
        // V80 态造数（改名前旧名下落值——数据零丢失断言的锚）：
        // 覆盖行（三族引用腿各带值）＋配置留痕行（三族 old/new 腿）＋切换留痕行
        statementExecute("INSERT INTO " + REPLAY_SCHEMA + ".prj_agent_configs"
                + " (agent_key, model_id, routing_strong_model_id, verify_model_id,"
                + " operator_id, operator_name) VALUES ('t554-seat', 'pro', 'flash', 'pro',"
                + " '700554', 't554-运营')");
        statementExecute("INSERT INTO " + REPLAY_SCHEMA + ".prj_agent_config_traces"
                + " (id, agent_key, old_model_id, old_web_search_enabled,"
                + " old_fetch_url_enabled, old_routing_strong_model_id, old_verify_model_id,"
                + " new_model_id, new_web_search_enabled, new_fetch_url_enabled,"
                + " new_routing_strong_model_id, new_verify_model_id, operator_id, operator_name)"
                + " VALUES (5541, 't554-seat', 'flash', TRUE, TRUE, NULL, NULL,"
                + " 'pro', TRUE, TRUE, 'flash', 'pro', '700554', 't554-运营')");
        statementExecute("INSERT INTO " + REPLAY_SCHEMA + ".prj_model_tier_switch_traces"
                + " (id, tier_key, from_provider_key, from_model_name, to_provider_key,"
                + " to_model_name, trigger_error, run_id, session_id)"
                + " VALUES (5542, 'flash', 'deepseek', 'deepseek-flash', 'deepseek-2',"
                + " 'deepseek-flash', 't554 drill: 401 AuthenticationException',"
                + " 'run-t554', 'sess-t554')");

        // 本用例钉 V81 改名终态：后续迁移（V82–V84 拆除旧机制列）不参与本断言；
        // 全新库全链回放终态由下一用例覆盖
        migrateTo(MIGRATION_VERSION);

        // ① 表族改名到位：新名在、旧名零残留
        assertThat(modelConfigFamilyTables()).containsExactlyInAnyOrder(
                "prj_model_config_candidates", "prj_model_config_candidate_traces",
                "prj_model_configs", "prj_model_config_switch_traces",
                "prj_model_config_traces");
        // ② 列改名到位：config_key 落在全部五张表族、agent_configs 引用腿三族九列
        assertThat(columnsOf("prj_model_configs")).contains("config_key")
                .doesNotContain("tier_key");
        assertThat(columnsOf("prj_model_config_candidates")).contains("config_key")
                .doesNotContain("tier_key");
        assertThat(columnsOf("prj_model_config_traces")).contains("config_key")
                .doesNotContain("tier_key");
        assertThat(columnsOf("prj_model_config_switch_traces")).contains("config_key")
                .doesNotContain("tier_key");
        assertThat(columnsOf("prj_model_config_candidate_traces")).contains("config_key")
                .doesNotContain("tier_key");
        assertThat(columnsOf("prj_agent_configs"))
                .contains("model_config_key", "routing_strong_model_config_key",
                        "verify_model_config_key")
                .doesNotContain("model_id", "routing_strong_model_id", "verify_model_id");
        assertThat(columnsOf("prj_agent_config_traces"))
                .contains("old_model_config_key", "new_model_config_key",
                        "old_routing_strong_model_config_key",
                        "new_routing_strong_model_config_key",
                        "old_verify_model_config_key", "new_verify_model_config_key")
                .doesNotContain("old_model_id", "new_model_id",
                        "old_routing_strong_model_id", "new_routing_strong_model_id",
                        "old_verify_model_id", "new_verify_model_id");
        // ③ 约束名随表族（主键/外键——Postgres 表改名不带约束，须显式 RENAME）
        assertThat(constraintsOf("prj_model_configs")).contains(
                "prj_model_configs_pkey", "fk_model_config_provider");
        assertThat(constraintsOf("prj_model_config_candidates")).contains(
                "pk_model_config_candidates", "fk_config_candidate_config",
                "fk_config_candidate_provider");
        // 三张留痕表行内主键的隐式约束名随表族（V81 显式 RENAME——Postgres 表改名不带
        // 约束；contains 而非 containsExactly：PG17 目录还列系统 NOT NULL 约束项）
        assertThat(constraintsOf("prj_model_config_traces"))
                .contains("prj_model_config_traces_pkey")
                .doesNotContain("prj_model_tier_traces_pkey");
        assertThat(constraintsOf("prj_model_config_switch_traces"))
                .contains("prj_model_config_switch_traces_pkey")
                .doesNotContain("prj_model_tier_switch_traces_pkey");
        assertThat(constraintsOf("prj_model_config_candidate_traces"))
                .contains("prj_model_config_candidate_traces_pkey")
                .doesNotContain("prj_model_tier_candidate_traces_pkey");
        // ④ 数据零丢失零变换：flash/pro 种子行值原样（含 display_name 数据面）
        assertThat(queryStrings("SELECT config_key || ':' || provider_key || ':'"
                + " || model_name || ':' || display_name || ':' || enabled::text"
                + " FROM " + REPLAY_SCHEMA + ".prj_model_configs ORDER BY config_key"))
                .containsExactly("flash:deepseek:deepseek-flash:快模型档:true",
                        "pro:deepseek:deepseek-v4-pro:强模型档:true");
        assertThat(queryStrings("SELECT config_key || '#' || priority || ' '"
                + " || provider_key || ':' || model_name || ' enabled=' || enabled"
                + " FROM " + REPLAY_SCHEMA + ".prj_model_config_candidates"
                + " ORDER BY config_key"))
                .containsExactly("flash#1 deepseek:deepseek-flash enabled=true",
                        "pro#1 deepseek:deepseek-v4-pro enabled=true");
        // 预植行值原样落位（引用腿三族＋留痕 old/new 腿＋切换留痕行）
        assertThat(queryStrings("SELECT model_config_key || '/'"
                + " || routing_strong_model_config_key || '/' || verify_model_config_key"
                + " FROM " + REPLAY_SCHEMA + ".prj_agent_configs"
                + " WHERE agent_key = 't554-seat'"))
                .containsExactly("pro/flash/pro");
        assertThat(queryStrings("SELECT old_model_config_key || '→'"
                + " || new_model_config_key || '，strong '"
                + " || new_routing_strong_model_config_key || '，verify '"
                + " || new_verify_model_config_key"
                + " FROM " + REPLAY_SCHEMA + ".prj_agent_config_traces WHERE id = 5541"))
                .containsExactly("flash→pro，strong flash，verify pro");
        assertThat(queryStrings("SELECT config_key || ' ' || from_provider_key || '→'"
                + " || to_provider_key || '（' || trigger_error || '）'"
                + " FROM " + REPLAY_SCHEMA + ".prj_model_config_switch_traces"
                + " WHERE id = 5542"))
                .containsExactly("flash deepseek→deepseek-2（t554 drill: 401 AuthenticationException）");
    }

    @Test
    void given_fresh_schema_when_replay_full_chain_then_final_state_consistent() {
        // 全新库首启：V1 起干净 schema 连续回放（V44 种子→V79 收敛→V81 改名）成功，
        // 终态与新名一致（数据面与存量腿同形——改名迁移对新库同样零值变化）
        migrate();

        assertThat(modelConfigFamilyTables()).containsExactlyInAnyOrder(
                "prj_model_config_candidates", "prj_model_config_candidate_traces",
                "prj_model_configs", "prj_model_config_switch_traces",
                "prj_model_config_traces");
        assertThat(queryStrings("SELECT config_key || ':' || provider_key || ':'"
                + " || model_name FROM " + REPLAY_SCHEMA + ".prj_model_configs"
                + " ORDER BY config_key"))
                .containsExactly("flash:deepseek:deepseek-flash",
                        "pro:deepseek:deepseek-v4-pro");
        assertThat(queryStrings("SELECT config_key || '#' || priority FROM "
                + REPLAY_SCHEMA + ".prj_model_config_candidates ORDER BY config_key"))
                .containsExactly("flash#1", "pro#1");
        // 全链终态含 V88 模型路由列拆除（#606）：配置列与留痕镜像列零残留
        assertThat(columnsOf("prj_agent_configs"))
                .doesNotContain("routing_enabled", "routing_strong_model_config_key",
                        "routing_file_count_threshold", "routing_escalating_size_labels",
                        "routing_file_count_signal_enabled", "routing_size_label_signal_enabled");
        assertThat(columnsOf("prj_agent_config_traces"))
                .doesNotContain("old_routing_enabled", "new_routing_enabled",
                        "old_routing_strong_model_config_key", "new_routing_strong_model_config_key",
                        "old_routing_file_count_threshold", "new_routing_file_count_threshold",
                        "old_routing_escalating_size_labels", "new_routing_escalating_size_labels",
                        "old_routing_file_count_signal_enabled", "new_routing_file_count_signal_enabled",
                        "old_routing_size_label_signal_enabled", "new_routing_size_label_signal_enabled");
    }

    // -------- 回放脚手架（照 ModelChannelHaMigrationReplayTest） --------

    /** V81 前一版（按 classpath 现存迁移文件动态取——并行票并入更高版本号时不用改本测试）。 */
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
                .orElseThrow(() -> new IllegalStateException("V81 之前无迁移文件"));
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

    /** 模型配置表族清单（新旧名一并捞——断言新名在、旧名零残留）。 */
    private List<String> modelConfigFamilyTables() {
        return queryStrings("SELECT table_name FROM information_schema.tables"
                + " WHERE table_schema = '" + REPLAY_SCHEMA + "'"
                + " AND (table_name LIKE 'prj_model_tier%' OR table_name LIKE 'prj_model_config%')"
                + " ORDER BY table_name");
    }

    private List<String> columnsOf(String table) {
        return queryStrings("SELECT column_name FROM information_schema.columns"
                + " WHERE table_schema = '" + REPLAY_SCHEMA + "' AND table_name = '" + table + "'"
                + " ORDER BY ordinal_position");
    }

    private List<String> constraintsOf(String table) {
        return queryStrings("SELECT constraint_name FROM information_schema.table_constraints"
                + " WHERE table_schema = '" + REPLAY_SCHEMA + "' AND table_name = '" + table + "'"
                + " ORDER BY constraint_name");
    }

    private List<String> queryStrings(String sql) {
        try (Connection connection = replayDataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            List<String> values = new ArrayList<>();
            while (resultSet.next()) {
                values.add(resultSet.getString(1));
            }
            return values;
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
