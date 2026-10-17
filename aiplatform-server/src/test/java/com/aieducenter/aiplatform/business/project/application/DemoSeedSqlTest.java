package com.aieducenter.aiplatform.business.project.application;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 演示种子引擎 SQL 验收（#340，spec #320「好测试的标准」之数据库可观察结果 seam）：
 * 对真实 PG 在临时库上直跑 classpath 的 seed.sql（与镜像 /opt/seed/seed.sql 同源）。
 * 引擎面向整库 public 扫描补空表，不能落在 aiplatform_test 的 public（平台 Flyway
 * 表会被误种），故自建临时库、整库回收——断言只看落库结果，不窥引擎内部。
 *
 * <p>断言口径＝票面 AC：确定性预置（空表得通用示例行）、幂等（已有数据的表不碰、
 * 重复施用不重复注入）、通用示例集（列形合成：名称/电话/邮箱/金额/状态等）、
 * best-effort（单表异常只跳过不炸整体）。</p>
 */
class DemoSeedSqlTest {

    private static final String HOST = "localhost:5432";
    private static final String ADMIN_DB = "postgres";
    private static final String SEED_DB = "aiplatform_seed_test";
    private static final String USER = "postgres";
    private static final String PASSWORD = "truth";

    private Connection conn;

    @BeforeAll
    static void createScratchDatabase() throws Exception {
        try (Connection admin = DriverManager.getConnection(
                "jdbc:postgresql://" + HOST + "/" + ADMIN_DB, USER, PASSWORD);
                Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + SEED_DB);
            st.execute("CREATE DATABASE " + SEED_DB);
        }
    }

    @AfterAll
    static void dropScratchDatabase() throws Exception {
        try (Connection admin = DriverManager.getConnection(
                "jdbc:postgresql://" + HOST + "/" + ADMIN_DB, USER, PASSWORD);
                Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + SEED_DB);
        }
    }

    @AfterEach
    void closeAndClean() throws Exception {
        if (conn != null) {
            // 测试内建对象随连接清（含枚举类型）；失败也不留脏给下一测
            exec("DROP TABLE IF EXISTS customers, orders, plain_pk, short_title,"
                    + " constrained, sibling, schema_migrations, moody, uuidy");
            exec("DROP TYPE IF EXISTS mood");
            conn.close();
        }
    }

    @Test
    void given_empty_library_when_seed_then_no_error() throws Exception {
        // 空库（首建卷自愈/全新工作区的常态）：无表可种，干跑不炸
        open();
        runSeed();
    }

    @Test
    void given_empty_business_table_when_seed_then_generic_rows_for_every_column_shape()
            throws Exception {
        open();
        exec("""
                CREATE TABLE customers (
                    id serial PRIMARY KEY,
                    customer_name varchar(50) NOT NULL,
                    company_name varchar(60),
                    phone varchar(20) NOT NULL,
                    email varchar(60),
                    city_addr varchar(120),
                    amount numeric(10,2) NOT NULL,
                    status varchar(20) NOT NULL,
                    active boolean NOT NULL,
                    remark text,
                    created_at timestamptz NOT NULL DEFAULT now()
                )""");
        runSeed();

        assertThat(queryLong("SELECT count(*) FROM customers")).isEqualTo(10);
        // 名称类列：全部落值且十行互不相同（通用示例集不循环到撞值）
        assertThat(queryLong(
                "SELECT count(DISTINCT customer_name) FROM customers")).isEqualTo(10);
        assertThat(queryLong(
                "SELECT count(DISTINCT company_name) FROM customers")).isEqualTo(10);
        // 电话/邮箱：通用示例形态（独立判据，不镜像引擎值池）
        assertThat(queryLong(
                "SELECT count(*) FROM customers WHERE phone ~ '^1[0-9]{10}$'")).isEqualTo(10);
        assertThat(queryLong(
                "SELECT count(*) FROM customers WHERE email LIKE '%@example.com'")).isEqualTo(10);
        assertThat(queryLong("""
                SELECT count(*) FROM customers
                WHERE amount BETWEEN 1 AND 100000 AND status <> '' AND city_addr <> ''
                """)).isEqualTo(10);
        // 默认列交给默认值（引擎不代填），非空布尔列有值
        assertThat(queryLong(
                "SELECT count(*) FROM customers WHERE created_at IS NOT NULL")).isEqualTo(10);
        assertThat(queryLong(
                "SELECT count(*) FROM customers WHERE active IN (true, false)")).isEqualTo(10);
        // 可空且无语义匹配的文本列（remark 命中说明类池）也有内容
        assertThat(queryLong(
                "SELECT count(*) FROM customers WHERE remark IS NOT NULL AND remark <> ''"))
                .isEqualTo(10);
    }

    @Test
    void given_nonempty_table_when_seed_then_untouched() throws Exception {
        // 幂等另一面：模型已产数据的表（或用户已操作的表）一概不动
        open();
        exec("CREATE TABLE customers (id serial PRIMARY KEY, customer_name text NOT NULL)");
        exec("INSERT INTO customers (customer_name) VALUES ('用户自建'), ('用户自建乙')");
        runSeed();
        assertThat(queryLong("SELECT count(*) FROM customers")).isEqualTo(2);
        assertThat(queryLong(
                "SELECT count(*) FROM customers WHERE customer_name LIKE '用户自建%'"))
                .isEqualTo(2);
    }

    @Test
    void given_seed_twice_when_seed_then_no_duplicates() throws Exception {
        open();
        exec("CREATE TABLE customers (id serial PRIMARY KEY, customer_name text NOT NULL)");
        runSeed();
        runSeed();
        assertThat(queryLong("SELECT count(*) FROM customers")).isEqualTo(10);
    }

    @Test
    void given_fk_child_table_when_seed_then_parent_seeded_first_with_real_keys()
            throws Exception {
        open();
        exec("""
                CREATE TABLE customers (
                    id serial PRIMARY KEY, customer_name text NOT NULL)
                """);
        exec("""
                CREATE TABLE orders (
                    id serial PRIMARY KEY,
                    customer_id integer NOT NULL REFERENCES customers(id),
                    title text NOT NULL,
                    amount numeric(10,2))
                """);
        runSeed();
        assertThat(queryLong("SELECT count(*) FROM customers")).isEqualTo(10);
        assertThat(queryLong("SELECT count(*) FROM orders")).isEqualTo(10);
        // 子表外键全部指向父表真实键（依赖深度排序：父先种）
        assertThat(queryLong("""
                SELECT count(*) FROM orders o
                WHERE EXISTS (SELECT 1 FROM customers c WHERE c.id = o.customer_id)
                """)).isEqualTo(10);
    }

    @Test
    void given_int_pk_without_default_when_seed_then_filled_unique() throws Exception {
        // 无默认值整型主键：引擎代填且不重
        open();
        exec("CREATE TABLE plain_pk (id integer PRIMARY KEY, title text NOT NULL)");
        runSeed();
        assertThat(queryLong("SELECT count(*) FROM plain_pk")).isEqualTo(10);
        assertThat(queryLong("SELECT count(DISTINCT id) FROM plain_pk")).isEqualTo(10);
        assertThat(queryLong("SELECT count(*) FROM plain_pk WHERE id IS NULL")).isZero();
    }

    @Test
    void given_varchar_limit_when_seed_then_truncated() throws Exception {
        open();
        exec("CREATE TABLE short_title (title varchar(2) NOT NULL)");
        runSeed();
        assertThat(queryLong("SELECT count(*) FROM short_title")).isEqualTo(10);
        assertThat(queryLong(
                "SELECT count(*) FROM short_title WHERE length(title) <= 2")).isEqualTo(10);
    }

    @Test
    void given_constrained_table_when_seed_then_skipped_without_killing_others()
            throws Exception {
        // 负向用例（解析类防线带负例钉死）：CHECK 域约束挡住通用值 → 该表跳过、
        // 同批其它表照种（best-effort 不炸整体）
        open();
        exec("""
                CREATE TABLE constrained (
                    id serial PRIMARY KEY,
                    status text NOT NULL CHECK (status IN ('A', 'B')))
                """);
        exec("CREATE TABLE sibling (id serial PRIMARY KEY, title text NOT NULL)");
        runSeed();
        assertThat(queryLong("SELECT count(*) FROM constrained")).isZero();
        assertThat(queryLong("SELECT count(*) FROM sibling")).isEqualTo(10);
    }

    @Test
    void given_migrations_named_table_when_seed_then_excluded() throws Exception {
        open();
        exec("CREATE TABLE schema_migrations (version text NOT NULL)");
        exec("CREATE TABLE sibling (id serial PRIMARY KEY, title text NOT NULL)");
        runSeed();
        assertThat(queryLong("SELECT count(*) FROM schema_migrations")).isZero();
        assertThat(queryLong("SELECT count(*) FROM sibling")).isEqualTo(10);
    }

    @Test
    void given_enum_column_when_seed_then_valid_labels() throws Exception {
        open();
        exec("CREATE TYPE mood AS ENUM ('happy', 'neutral', 'sad')");
        exec("CREATE TABLE moody (id serial PRIMARY KEY, mood mood NOT NULL)");
        runSeed();
        assertThat(queryLong("""
                SELECT count(*) FROM moody
                WHERE mood IN ('happy', 'neutral', 'sad')
                """)).isEqualTo(10);
    }

    @Test
    void given_uuid_pk_without_default_when_seed_then_distinct_uuids() throws Exception {
        open();
        exec("CREATE TABLE uuidy (id uuid PRIMARY KEY, title text NOT NULL)");
        runSeed();
        assertThat(queryLong("SELECT count(*) FROM uuidy")).isEqualTo(10);
        assertThat(queryLong("SELECT count(DISTINCT id) FROM uuidy")).isEqualTo(10);
    }

    // ---------- 脚手架 ----------

    private void open() throws SQLException {
        conn = DriverManager.getConnection(
                "jdbc:postgresql://" + HOST + "/" + SEED_DB, USER, PASSWORD);
    }

    /** 直跑 classpath 的种子引擎（与镜像内 /opt/seed/seed.sql 同一资源）。 */
    private void runSeed() throws Exception {
        String sql = new String(getClass().getClassLoader()
                .getResourceAsStream("docker/workspace/seed/seed.sql")
                .readAllBytes(), StandardCharsets.UTF_8);
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private void exec(String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private long queryLong(String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
