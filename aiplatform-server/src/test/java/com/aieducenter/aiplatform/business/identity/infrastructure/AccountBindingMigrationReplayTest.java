package com.aieducenter.aiplatform.business.identity.infrastructure;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
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

import com.aieducenter.aiplatform.IntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V31 账户绑定表拆分迁移回放（#327）：测试库是空库（无存量 external_id 可搬），
 * 搬迁正确性以 Flyway 回放钉死——独立 schema 里先 migrate 到 V31 前一版、手工造
 * pre-migration 形态存量（idn_accounts 带 external_id），再 migrate 过 V31，断言：
 *
 * <ul>
 *   <li>存量 external_id 全量搬为 provider=oidc 行、account_id 对得上、显示名原样</li>
 *   <li>accounts 瘦身（external_id 列真删，非留着不用）</li>
 *   <li>回填语句幂等——把迁移里的 backfill INSERT 原文再执行一遍零重复零报错
 *       （无 ON CONFLICT 兜底时此步红：撞 unique）</li>
 *   <li>unique(provider, identifier) 兜底——同 provider 同 identifier 直插必被库拒</li>
 * </ul>
 *
 * <p>回放 schema（{@value #REPLAY_SCHEMA}）与 Spring 上下文的 public schema 同库不同
 * 派，互不触碰；每次跑前 DROP 重建，可重复执行。dev 真库的搬迁走活体走查，不在此测。</p>
 */
@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountBindingMigrationReplayTest {

    /** 本迁移版本号（#327 专属，迁移号分配见 spec-320 编排） */
    private static final int MIGRATION_VERSION = 31;

    /** 回放专用 schema（随跑随弃，不碰 public） */
    private static final String REPLAY_SCHEMA = "t327_replay";

    /** backfill 语句在迁移文件中的提取锚（两注释行之间） */
    private static final String BACKFILL_START = "-- backfill-oidc-start";
    private static final String BACKFILL_END = "-- backfill-oidc-end";

    private static final Pattern VERSIONED_FILE = Pattern.compile("V(\\d+)__");

    @Autowired
    private DataSourceProperties dataSourceProperties;

    private DataSource replayDataSource;
    private Flyway flyway;

    @BeforeAll
    void setUpReplaySchema() {
        replayDataSource = new DriverManagerDataSource(dataSourceProperties.getUrl(),
                dataSourceProperties.getUsername(), dataSourceProperties.getPassword());
        recreateSchema();
        // 配置镜像 application.yml 的 spring.flyway（表名/baseline 口径），仅 schema 不同；
        // target 由各用例自带（先 pre 后最新）——V1 起全链在干净 schema 上重放
        flyway = Flyway.configure()
                .dataSource(replayDataSource)
                .schemas(REPLAY_SCHEMA)
                .locations("classpath:db/migration")
                .table("aiplatform-server_flyway_schema_history")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                // V44 模型通道种子 placeholder（镜像 spring.flyway.placeholders——回放
                // schema 种子 key 落空即可，identity 域回放不消费档位）
                .placeholders(java.util.Map.of("volcengineApiKey", "", "deepseekApiKey", ""))
                .load();
    }

    @AfterAll
    void dropReplaySchema() {
        statementExecute("DROP SCHEMA IF EXISTS " + REPLAY_SCHEMA + " CASCADE");
    }

    /** 每用例独立回放：schema 连历史一起重置，用例间不残留 */
    @BeforeEach
    void resetReplaySchema() {
        recreateSchema();
    }

    private void recreateSchema() {
        statementExecute("DROP SCHEMA IF EXISTS " + REPLAY_SCHEMA + " CASCADE");
        statementExecute("CREATE SCHEMA " + REPLAY_SCHEMA);
    }

    @Test
    void given_legacy_external_id_accounts_when_migrate_v31_then_moved_to_oidc_bindings_and_accounts_slimmed() {
        migrateTo(preVersion());
        insertLegacyAccounts("sub-alpha", "张三", "sub-beta", "李四", "sub-gamma", "王五");

        flyway.migrate(); // 过 V31

        // 存量全量搬为 provider=oidc（1）行，account_id 对得上、标识逐字一致
        List<Long> accountIds = queryLongs("SELECT account_id FROM " + REPLAY_SCHEMA
                + ".idn_account_bindings WHERE provider = 1 ORDER BY identifier");
        List<String> identifiers = queryStrings("SELECT identifier FROM " + REPLAY_SCHEMA
                + ".idn_account_bindings WHERE provider = 1 ORDER BY identifier");
        List<Long> expectedAccountIds = queryLongs("SELECT id FROM " + REPLAY_SCHEMA
                + ".idn_accounts WHERE display_name = '张三' OR display_name = '李四'"
                + " OR display_name = '王五' ORDER BY id");
        assertThat(identifiers).containsExactlyInAnyOrder("sub-alpha", "sub-beta", "sub-gamma");
        assertThat(accountIds).containsExactlyInAnyOrderElementsOf(expectedAccountIds);
        assertThat(countBindings()).isEqualTo(3);

        // accounts 瘦身：external_id 列真删
        assertThat(columnExists("idn_accounts", "external_id")).isFalse();
        // 显示名与 id 原样保留（瘦身只删凭据列）
        assertThat(queryStrings("SELECT display_name FROM " + REPLAY_SCHEMA
                + ".idn_accounts ORDER BY display_name"))
                .containsExactly("张三", "李四", "王五");
    }

    @Test
    void given_migrated_state_when_backfill_statement_replayed_then_no_duplicates() throws Exception {
        migrateTo(preVersion());
        insertLegacyAccounts("sub-alpha", "张三", "sub-beta", "李四", "sub-gamma", "王五");
        flyway.migrate();

        // 重放险境复原：搬迁后源列已删，回填语句本无处重放——临时把 external_id
        // 列与旧值补回（模拟「绑定行已在、旧凭据值再现」的人工重跑/squash 重放
        // 现场），再执行迁移原文的 backfill INSERT
        statementExecute("ALTER TABLE " + REPLAY_SCHEMA + ".idn_accounts"
                + " ADD COLUMN external_id VARCHAR(100)");
        statementExecute("UPDATE " + REPLAY_SCHEMA + ".idn_accounts a"
                + " SET external_id = b.identifier FROM " + REPLAY_SCHEMA
                + ".idn_account_bindings b WHERE b.account_id = a.id AND b.provider = 1");

        try (Connection connection = replayDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            // 迁移原文无 schema 前缀（Flyway schema 机制内执行）——重放时把
            // search_path 指到回放 schema，语句落点与迁移执行时一致
            statement.execute("SET search_path TO " + REPLAY_SCHEMA);
            statement.execute(extractedBackfillSql());
        }
        assertThat(countBindings()).isEqualTo(3);
    }

    @Test
    void given_oidc_binding_exists_when_insert_same_provider_identifier_then_rejected_by_unique() {
        migrateTo(preVersion());
        insertLegacyAccounts("sub-alpha", "张三");
        flyway.migrate();

        Long accountId = queryLongs("SELECT id FROM " + REPLAY_SCHEMA + ".idn_accounts").get(0);
        assertThatThrownBy(() -> {
            try (Connection connection = replayDataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "INSERT INTO " + REPLAY_SCHEMA + ".idn_account_bindings"
                                 + " (id, account_id, provider, identifier) VALUES (?, ?, 1, 'sub-alpha')")) {
                statement.setLong(1, 42424242424242L);
                statement.setLong(2, accountId);
                statement.executeUpdate();
            }
        }).isInstanceOf(SQLException.class);
        assertThat(countBindings()).isEqualTo(1);
    }

    // -------- 回放脚手架 --------

    /** V31 前一版（按 classpath 现存迁移文件动态取——并行票并入更高版本号时不用改本测试） */
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
                .orElseThrow(() -> new IllegalStateException("V31 之前无迁移文件"));
    }

    private void migrateTo(int version) {
        Flyway.configure()
                .dataSource(replayDataSource)
                .schemas(REPLAY_SCHEMA)
                .locations("classpath:db/migration")
                .table("aiplatform-server_flyway_schema_history")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .placeholders(java.util.Map.of("volcengineApiKey", "", "deepseekApiKey", ""))
                .target(String.valueOf(version))
                .load()
                .migrate();
    }

    /** 造 pre-migration 形态存量：idn_accounts 带 external_id（V1 基线形） */
    private void insertLegacyAccounts(String... pairs) {
        try (Connection connection = replayDataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO " + REPLAY_SCHEMA + ".idn_accounts"
                             + " (id, external_id, display_name) VALUES (?, ?, ?)")) {
            for (int i = 0; i < pairs.length; i += 2) {
                statement.setLong(1, 7000000000000L + i);
                statement.setString(2, pairs[i]);
                statement.setString(3, pairs[i + 1]);
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException("存量造数失败", e);
        }
    }

    private String extractedBackfillSql() throws Exception {
        try (var input = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("db/migration/V" + MIGRATION_VERSION + "__account_binding_split.sql")) {
            assertThat(input).as("V31 迁移文件应在 classpath").isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            int start = sql.indexOf(BACKFILL_START);
            int end = sql.indexOf(BACKFILL_END);
            assertThat(start).as("backfill 起锚应在迁移文件里").isGreaterThan(-1);
            assertThat(end).as("backfill 止锚应在迁移文件里").isGreaterThan(start);
            return sql.substring(start + BACKFILL_START.length(), end);
        }
    }

    private long countBindings() {
        return queryLongs("SELECT COUNT(*) FROM " + REPLAY_SCHEMA + ".idn_account_bindings").get(0);
    }

    private boolean columnExists(String table, String column) {
        return queryLongs("SELECT COUNT(*) FROM information_schema.columns"
                + " WHERE table_schema = '" + REPLAY_SCHEMA + "' AND table_name = '" + table
                + "' AND column_name = '" + column + "'").get(0) > 0;
    }

    private List<Long> queryLongs(String sql) {
        try (Connection connection = replayDataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            List<Long> values = new ArrayList<>();
            while (resultSet.next()) {
                values.add(resultSet.getLong(1));
            }
            return values;
        } catch (SQLException e) {
            throw new IllegalStateException("查询失败：" + sql, e);
        }
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
