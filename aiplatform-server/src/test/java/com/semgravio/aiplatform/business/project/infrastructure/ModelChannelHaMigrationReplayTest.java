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
 * V79 模型通道高可用迁移回放（#556，spec #552-T2，ADR-0053 ④）——测试库是
 * 迁移已应用的现态，收编形态以 Flyway 回放钉死（照 {@code AccountBindingMigration
 * ReplayTest} 先例：独立 schema 先 migrate 到 V78、手工造 pre-migration 形态，
 * 再 migrate 过 V79 断言收敛）。两条收编路径各一用例：
 *
 * <ul>
 *   <li><b>dev/test 库（V44 残留火山）</b>：volcengine 零残留（通道行、档位行）；
 *       flash/pro 收敛 deepseek 官方模型（与单价行模型名对齐）；候选种子＝
 *       flash/pro 各一现役候选；vendor 列（通道行＋计量事件行）历史同值回填。</li>
 *   <li><b>生产库（已手工切）</b>：迁移幂等 no-op——deepseek 通道行真值 key 不
 *       被覆写、已就位的档位行指向不动、候选不重复种。</li>
 * </ul>
 *
 * <p>全新库首启（V44 种火山→V79 收敛）＝第一用例的天然形态，不再单独测。
 * 回放 schema 与 Spring 上下文 public schema 同库不同派，互不触碰；随跑随弃。</p>
 */
@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModelChannelHaMigrationReplayTest {

    /** 本迁移版本号（#556）。 */
    private static final int MIGRATION_VERSION = 79;

    /** 回放专用 schema（随跑随弃，不碰 public）。 */
    private static final String REPLAY_SCHEMA = "t556_replay";

    private static final Pattern VERSIONED_FILE = Pattern.compile("V(\\d+)__");

    @Autowired
    private DataSourceProperties dataSourceProperties;

    private DataSource replayDataSource;

    @BeforeAll
    void setUpReplaySchema() {
        replayDataSource = new DriverManagerDataSource(dataSourceProperties.getUrl(),
                dataSourceProperties.getUsername(), dataSourceProperties.getPassword());
        // 配置镜像 application.yml 的 spring.flyway（表名/baseline 口径），仅 schema
        // 不同；target 由 migrateTo 自带——V1 起全链在干净 schema 上重放。
        // V44 种子 placeholder 落空（NULLIF→NULL＝未配置态，V79 不依赖其值）
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
    void given_v44_volcengine_residue_when_migrate_v79_then_zero_residue_and_deepseek_converged() {
        migrateTo(preVersion());
        // pre 形态留证：V44 种下的火山主用通道＋三档位行
        assertThat(queryStrings("SELECT provider_key FROM " + REPLAY_SCHEMA
                + ".prj_model_providers ORDER BY provider_key"))
                .containsExactly("deepseek", "volcengine");
        insertUsageEvent("evt-volc-legacy", "volcengine", "deepseek-v4-flash-ga-260731");
        insertUsageEvent("evt-ds-legacy", "deepseek", "deepseek-chat");

        migrate();

        // volcengine 零残留（通道行、档位行）；doubao-flagship 随火山档位行消亡
        assertThat(queryStrings("SELECT provider_key FROM " + REPLAY_SCHEMA
                + ".prj_model_providers")).containsExactly("deepseek");
        assertThat(queryStrings("SELECT tier_key FROM " + REPLAY_SCHEMA
                + ".prj_model_tiers ORDER BY tier_key")).containsExactly("flash", "pro");
        // flash/pro 收敛 deepseek 官方模型（单价行模型名对齐）
        assertThat(queryStrings("SELECT provider_key || ':' || model_name FROM " + REPLAY_SCHEMA
                + ".prj_model_tiers WHERE tier_key = 'flash'"))
                .containsExactly("deepseek:deepseek-flash");
        assertThat(queryStrings("SELECT provider_key || ':' || model_name FROM " + REPLAY_SCHEMA
                + ".prj_model_tiers WHERE tier_key = 'pro'"))
                .containsExactly("deepseek:deepseek-v4-pro");
        // 候选种子：flash/pro 各一现役候选（主选单元素队列，与档位行现列同值）
        assertThat(queryStrings("SELECT tier_key || '#' || priority || ' ' || provider_key"
                + " || ':' || model_name || ' enabled=' || enabled FROM " + REPLAY_SCHEMA
                + ".prj_model_tier_candidates ORDER BY tier_key"))
                .containsExactly("flash#1 deepseek:deepseek-flash enabled=true",
                        "pro#1 deepseek:deepseek-v4-pro enabled=true");
        // vendor 归一回填：通道行 vendor＝provider_key
        assertThat(queryStrings("SELECT provider_key || '→' || vendor FROM " + REPLAY_SCHEMA
                + ".prj_model_providers")).containsExactly("deepseek→deepseek");
        // 计量事件历史行同值回填（volcengine 行已删、历史归因自持）
        assertThat(queryStrings("SELECT provider || '→' || vendor FROM " + REPLAY_SCHEMA
                + ".met_usage_events ORDER BY event_id"))
                .containsExactly("deepseek→deepseek", "volcengine→volcengine");
        // 切换留痕表落地（append-only 空表——写腿随 T3）
        assertThat(countRows("prj_model_tier_switch_traces")).isZero();
        // V80 后台写面 schema（#559/T5，migrate() 随跑到位）：候选编排留痕表落地
        // （append-only 空表）＋通道留痕 vendor 快照腿
        assertThat(countRows("prj_model_tier_candidate_traces")).isZero();
        assertThat(queryStrings("SELECT column_name FROM information_schema.columns"
                + " WHERE table_schema = '" + REPLAY_SCHEMA + "' AND table_name"
                + " = 'prj_model_provider_traces' AND column_name LIKE '%vendor'"))
                .containsExactly("old_vendor", "new_vendor");
    }

    @Test
    void given_production_parity_state_when_migrate_v79_then_idempotent_no_op() {
        migrateTo(preVersion());
        // 造生产手工切换后形态（2026-11-19 火山下线收尾）：火山已清、flash/pro
        // 已指 deepseek 官方、deepseek 行带真值 key
        statementExecute("DELETE FROM " + REPLAY_SCHEMA + ".prj_model_tiers"
                + " WHERE provider_key = 'volcengine'");
        statementExecute("DELETE FROM " + REPLAY_SCHEMA + ".prj_model_providers"
                + " WHERE provider_key = 'volcengine'");
        statementExecute("UPDATE " + REPLAY_SCHEMA + ".prj_model_tiers"
                + " SET provider_key = 'deepseek', model_name = 'deepseek-flash'"
                + " WHERE tier_key = 'flash'");
        statementExecute("UPDATE " + REPLAY_SCHEMA + ".prj_model_tiers"
                + " SET provider_key = 'deepseek', model_name = 'deepseek-v4-pro'"
                + " WHERE tier_key = 'pro'");
        statementExecute("DELETE FROM " + REPLAY_SCHEMA + ".prj_model_tiers"
                + " WHERE tier_key = 'doubao-flagship'");
        statementExecute("UPDATE " + REPLAY_SCHEMA + ".prj_model_providers"
                + " SET api_key = 'sk-prod-real-key-123456' WHERE provider_key = 'deepseek'");

        migrate();

        // 幂等 no-op：真值 key 不被覆写（DO NOTHING）、指向已就位不动、候选恰一份
        assertThat(queryStrings("SELECT api_key FROM " + REPLAY_SCHEMA
                + ".prj_model_providers WHERE provider_key = 'deepseek'"))
                .containsExactly("sk-prod-real-key-123456");
        assertThat(countRows("prj_model_tier_candidates")).isEqualTo(2);
        assertThat(queryStrings("SELECT provider_key FROM " + REPLAY_SCHEMA
                + ".prj_model_providers")).containsExactly("deepseek");
    }

    // -------- 回放脚手架（照 AccountBindingMigrationReplayTest） --------

    /** V79 前一版（按 classpath 现存迁移文件动态取——并行票并入更高版本号时不用改本测试）。 */
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
                .orElseThrow(() -> new IllegalStateException("V79 之前无迁移文件"));
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

    /** 造 pre-V79 形态计量事件（vendor 列尚未存在的存量行）。 */
    private void insertUsageEvent(String eventId, String provider, String model) {
        statementExecute("INSERT INTO " + REPLAY_SCHEMA + ".met_usage_events"
                + " (event_id, ts, subject, provider, model, input, output, cache_read,"
                + " cache_write, reasoning) VALUES ('" + eventId + "', CURRENT_TIMESTAMP,"
                + " 't556', '" + provider + "', '" + model + "', 1, 2, 0, 0, 0)");
    }

    private long countRows(String table) {
        List<String> rows = queryStrings(
                "SELECT COUNT(*)::text FROM " + REPLAY_SCHEMA + "." + table);
        return rows.isEmpty() ? 0 : Long.parseLong(rows.get(0));
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
