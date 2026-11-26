package com.semgravio.aiplatform.business.order.infrastructure;

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
 * V86 订单交付物类型收窄迁移回放（#611，设计线整条退役-2；父票 #609 裁决：
 * 商业面设计单／设计交付物包一并退场）——测试库是迁移已应用的现态，收编形态
 * 以 Flyway 回放钉死（照 {@code ModelChannelHaMigrationReplayTest} 先例：独立
 * schema 先 migrate 到 V85、手工造 pre-migration 形态，再 migrate 过 V86 断言
 * 收敛）。
 *
 * <p>断言面：存量 1（设计）／3（系统＋设计）行迁移为 2（系统）→ 零 1／3 残留；
 * 收窄后的 CHECK 约束拒绝再写入 1／3；码值 2 与缺省值保持可写（新单系统单）。</p>
 *
 * <p>回放 schema 与 Spring 上下文 public schema 同库不同派，互不触碰；随跑随弃。</p>
 */
@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderDeliverableTypeMigrationReplayTest {

    /** 本迁移版本号（#611）。 */
    private static final int MIGRATION_VERSION = 86;

    /** 回放专用 schema（随跑随弃，不碰 public）。 */
    private static final String REPLAY_SCHEMA = "t611_replay";

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
    void given_legacy_design_rows_when_migrate_v86_then_all_system_and_constraint_narrowed() {
        migrateTo(preVersion());
        // pre 形态留证：存量设计／系统＋设计／系统三行（V29 约束允许 1/2/3）
        insertOrder(1L, 1);
        insertOrder(2L, 3);
        insertOrder(3L, 2);

        migrate();

        // 存量 1／3 按裁决迁移为 2（系统单），码值 2 原样
        assertThat(queryStrings("SELECT deliverable_type FROM " + REPLAY_SCHEMA
                + ".ord_orders ORDER BY id")).containsExactly("2", "2", "2");
        // 收窄后的 CHECK 拒绝再写入设计值（1／3），系统值 2 与缺省值可写
        assertThatThrownBy(() -> insertOrder(4L, 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> insertOrder(5L, 3)).isInstanceOf(IllegalStateException.class);
        insertOrder(6L, 2);
        statementExecute("INSERT INTO " + REPLAY_SCHEMA + ".ord_orders"
                + " (id, project_id, status, prd_snapshot) VALUES (7, 7, 4, '# PRD')");
        assertThat(queryStrings("SELECT deliverable_type FROM " + REPLAY_SCHEMA
                + ".ord_orders WHERE id IN (6, 7) ORDER BY id")).containsExactly("2", "2");
    }

    // -------- 回放脚手架（照 ModelChannelHaMigrationReplayTest） --------

    /** V86 前一版（按 classpath 现存迁移文件动态取）。 */
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
                .orElseThrow(() -> new IllegalStateException("V86 之前无迁移文件"));
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

    /** 造 pre-V86 形态订单行（终态 status=4 避开未终结唯一索引）。 */
    private void insertOrder(long id, int deliverableType) {
        statementExecute("INSERT INTO " + REPLAY_SCHEMA + ".ord_orders"
                + " (id, project_id, status, prd_snapshot, deliverable_type)"
                + " VALUES (" + id + ", " + id + ", 4, '# PRD', " + deliverableType + ")");
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
