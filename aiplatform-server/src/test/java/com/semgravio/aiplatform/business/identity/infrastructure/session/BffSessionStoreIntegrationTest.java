package com.semgravio.aiplatform.business.identity.infrastructure.session;

import java.time.Clock;
import java.time.LocalDateTime;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.business.identity.application.SessionProperties;
import com.semgravio.aiplatform.business.identity.domain.model.AuthCookies;
import com.semgravio.aiplatform.business.identity.domain.repository.LoginSessionRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登录会话持久化集测（#329 集测 seam）：store 换 PG 实现（{@code idn_sessions}）后
 * 的重启语义——行在库不在进程内存，第二 store 实例（模拟另一进程/实例）与旧进程
 * SQL 遗留行皆可直读；put/remove 幂等；M01（token 三件套全空）与 OIDC 形状同表
 * 同契约。e2e 段走真过滤链（cookie → store → RequestContext → 拦截器 → 端点）：
 * 旧进程遗留行照常装配、登出删行即失效。过期/清扫面见
 * {@code BffSessionExpiryIntegrationTest}。
 */
@IntegrationTest(properties = {
        "sso.issuer=http://identity.localhost:10001",
        "sso.client-id=test-client",
        "sso.client-secret=test-secret",
        "sso.redirect-uri=http://localhost:3333/auth/callback",
        "sso.app-base-url=http://localhost:3333",
        "sso.cookie-secure=false"})
@AutoConfigureMockMvc
class BffSessionStoreIntegrationTest {

    @Autowired
    private BffSessionStore sessionStore;

    @Autowired
    private LoginSessionRepository sessionRepository;

    @Autowired
    private SessionProperties sessionProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    @AfterEach
    void purgeSessions() {
        jdbcTemplate.update("DELETE FROM idn_sessions");
    }

    // ---------- 重启语义：行在库不在内存 ----------

    @Test
    void given_session_put_when_second_store_instance_reads_then_present() {
        sessionStore.put("sid-restart-1",
                new BffSession(940001L, "重启后用户", "idt-1", "at-1", "rt-1"));

        // 第二 store 实例＝零共享内存的另一进程/实例：只共享 PG（多实例前置条件）
        BffSessionStore secondProcess =
                new BffSessionStore(sessionRepository, sessionProperties, Clock.systemUTC());

        assertThat(secondProcess.get("sid-restart-1")).hasValueSatisfying(session -> {
            assertThat(session.accountId()).isEqualTo(940001L);
            assertThat(session.displayName()).isEqualTo("重启后用户");
            assertThat(session.idToken()).isEqualTo("idt-1");
            assertThat(session.accessToken()).isEqualTo("at-1");
            assertThat(session.refreshToken()).isEqualTo("rt-1");
        });
        // 行确实落库（expires_at 自创建即定：now + ttl）
        LocalDateTime expiresAt = jdbcTemplate.queryForObject(
                "SELECT expires_at FROM idn_sessions WHERE session_id = 'sid-restart-1'",
                LocalDateTime.class);
        assertThat(expiresAt).isAfter(LocalDateTime.now(Clock.systemUTC()));
    }

    @Test
    void given_row_left_by_former_process_when_store_reads_then_present_and_remove_deletes_row() {
        // 旧进程遗留行（raw SQL，模拟服务重启前的写入）：新进程直读
        jdbcTemplate.update(
                "INSERT INTO idn_sessions (session_id, account_id, display_name, id_token, "
                        + "expires_at, created_at, updated_at) "
                        + "VALUES ('sid-former-1', 940002, '旧进程用户', 'idt-old', ?, NOW(), NOW())",
                LocalDateTime.now(Clock.systemUTC()).plusDays(1));

        assertThat(sessionStore.get("sid-former-1")).hasValueSatisfying(session -> {
            assertThat(session.accountId()).isEqualTo(940002L);
            assertThat(session.displayName()).isEqualTo("旧进程用户");
            assertThat(session.idToken()).isEqualTo("idt-old");
        });

        sessionStore.remove("sid-former-1");

        assertThat(sessionStore.get("sid-former-1")).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idn_sessions WHERE session_id = 'sid-former-1'", Long.class))
                .isZero();
    }

    // ---------- e2e：真过滤链装配旧进程遗留行＋登出删行 ----------

    @Test
    void given_former_process_row_when_me_then_authenticated_and_logout_deletes_row()
            throws Exception {
        // 旧进程遗留行（raw SQL）：重启后同 cookie 照常装配登录态（AC「新进程读旧会话」）
        jdbcTemplate.update(
                "INSERT INTO idn_sessions (session_id, account_id, display_name, id_token, "
                        + "expires_at, created_at, updated_at) "
                        + "VALUES ('sid-e2e-1', 940109, '重启直通用户', 'idt-e2e', ?, NOW(), NOW())",
                LocalDateTime.now(Clock.systemUTC()).plusDays(1));

        mockMvc.perform(get("/api/me")
                        .cookie(new Cookie(AuthCookies.SESSION_COOKIE_NAME, "sid-e2e-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountId").value("940109"))
                .andExpect(jsonPath("$.data.displayName").value("重启直通用户"));

        // 登出删行（302 到 identity RP-Initiated Logout；业务 cookie 同步清）
        mockMvc.perform(post("/auth/logout")
                        .cookie(new Cookie(AuthCookies.SESSION_COOKIE_NAME, "sid-e2e-1")))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("http://identity.localhost:10001/logout?*"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idn_sessions WHERE session_id = 'sid-e2e-1'", Long.class))
                .isZero();
        mockMvc.perform(get("/api/me")
                        .cookie(new Cookie(AuthCookies.SESSION_COOKIE_NAME, "sid-e2e-1")))
                .andExpect(status().isUnauthorized());
    }

    // ---------- put/get/remove 基本语义 ----------

    @Test
    void given_unknown_or_null_session_id_when_get_then_empty() {
        assertThat(sessionStore.get("never-stored")).isEmpty();
        assertThat(sessionStore.get(null)).isEmpty();
    }

    @Test
    void given_re_put_same_session_id_when_get_then_single_row_with_latest_values() {
        // 同 id 重 put＝覆盖（upsert 全列跟随输入）：行不翻倍、值取最新
        sessionStore.put("sid-overwrite",
                new BffSession(940003L, "旧显示名", "idt-a", "at-a", "rt-a"));
        sessionStore.put("sid-overwrite",
                new BffSession(940009L, "新显示名", "idt-b", "at-b", "rt-b"));

        assertThat(sessionStore.get("sid-overwrite")).hasValueSatisfying(session -> {
            assertThat(session.accountId()).isEqualTo(940009L);
            assertThat(session.displayName()).isEqualTo("新显示名");
            assertThat(session.idToken()).isEqualTo("idt-b");
        });
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM idn_sessions", Long.class))
                .isEqualTo(1L);
    }

    @Test
    void given_tokenless_session_when_put_then_roundtrip_with_null_tokens() {
        // M01 验证码登录形状：无 token 语义（三件套全空）与 OIDC 形状同表同契约
        sessionStore.put("sid-m01",
                new BffSession(940004L, "验证码用户", null, null, null));

        assertThat(sessionStore.get("sid-m01")).hasValueSatisfying(session -> {
            assertThat(session.idToken()).isNull();
            assertThat(session.accessToken()).isNull();
            assertThat(session.refreshToken()).isNull();
        });
    }

    @Test
    void given_removed_session_when_get_then_empty() {
        sessionStore.put("sid-remove",
                new BffSession(940005L, "登出用户", "idt", "at", "rt"));

        sessionStore.remove("sid-remove");

        assertThat(sessionStore.get("sid-remove")).isEmpty();
    }
}
