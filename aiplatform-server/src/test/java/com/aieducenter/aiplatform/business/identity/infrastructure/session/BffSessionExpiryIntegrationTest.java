package com.aieducenter.aiplatform.business.identity.infrastructure.session;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.aieducenter.aiplatform.IntegrationTest;
import com.aieducenter.aiplatform.business.identity.application.SessionProperties;
import com.aieducenter.aiplatform.business.identity.endpoints.scheduler.BffSessionCleanupScheduler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 登录会话过期与清扫集测（#329）：过期行不认（谓词拒绝）但数据留行（清理由扫描
 * 承担，读路径零写）、purgeExpired 只删过期、定时器接线到真删行。TTL 落值用
 * 固定时钟钉死（expires_at = now + ttl，创建即定）。
 */
@IntegrationTest
class BffSessionExpiryIntegrationTest {

    /** 固定时钟（TimeConfig 口径：@MockitoBean Clock 替换、快进断言不真等）。 */
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private BffSessionStore sessionStore;

    @Autowired
    private BffSessionCleanupScheduler cleanupScheduler;

    @Autowired
    private SessionProperties sessionProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    @AfterEach
    void purgeSessions() {
        jdbcTemplate.update("DELETE FROM idn_sessions");
    }

    @BeforeEach
    void fixClock() {
        whenClock().thenReturn(T0);
        // LocalDateTime.now(clock) 同时读 zone（mock 缺省 null → NPE）：一并钉 UTC
        org.mockito.Mockito.when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    // ---------- TTL 落值：创建即定 now + ttl ----------

    @Test
    void given_put_when_row_written_then_expires_at_pinned_to_now_plus_ttl() {
        sessionStore.put("sid-ttl",
                new BffSession(940101L, "过期钉值用户", "idt", "at", "rt"));

        LocalDateTime expiresAt = jdbcTemplate.queryForObject(
                "SELECT expires_at FROM idn_sessions WHERE session_id = 'sid-ttl'",
                LocalDateTime.class);
        assertThat(expiresAt).isEqualTo(LocalDateTime.ofInstant(
                T0.plus(sessionProperties.getTtl()), ZoneOffset.UTC));
    }

    // ---------- 过期不认（谓词拒绝）、行保留 ----------

    @Test
    void given_clock_past_expiry_when_get_then_empty_but_row_kept() {
        sessionStore.put("sid-expire",
                new BffSession(940102L, "过期用户", "idt", "at", "rt"));

        // 时钟快进过 TTL：会话不认（同一起点，行还在——读路径零写，清理由扫描承担）
        whenClock().thenReturn(T0.plus(sessionProperties.getTtl()).plusSeconds(1));

        assertThat(sessionStore.get("sid-expire")).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idn_sessions WHERE session_id = 'sid-expire'", Long.class))
                .isEqualTo(1L);
    }

    @Test
    void given_clock_exactly_at_expiry_when_get_then_empty() {
        sessionStore.put("sid-boundary",
                new BffSession(940103L, "边界用户", null, null, null));

        // 边界口径：expires_at > now 才有效——恰等即过期
        whenClock().thenReturn(T0.plus(sessionProperties.getTtl()));

        assertThat(sessionStore.get("sid-boundary")).isEmpty();
    }

    // ---------- 清扫：purgeExpired 只删过期 ----------

    @Test
    void given_mixed_rows_when_purge_expired_then_only_expired_deleted() {
        // 两行种入时刻错开（会话本就不同时建立）：早种的先到期
        sessionStore.put("sid-dead",
                new BffSession(940105L, "过期用户", null, null, null));
        whenClock().thenReturn(T0.plus(sessionProperties.getTtl().dividedBy(2)));
        sessionStore.put("sid-live",
                new BffSession(940104L, "在期用户", null, null, null));

        whenClock().thenReturn(T0.plus(sessionProperties.getTtl()).plusSeconds(1));
        int swept = sessionStore.purgeExpired();

        assertThat(swept).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idn_sessions", Long.class)).isEqualTo(1L);
        assertThat(sessionStore.get("sid-live")).isPresent(); // 在期行无恙
    }

    @Test
    void given_nothing_expired_when_purge_then_zero() {
        sessionStore.put("sid-only-live",
                new BffSession(940106L, "仅在期用户", null, null, null));

        assertThat(sessionStore.purgeExpired()).isZero();
    }

    // ---------- 定时器接线：sweepRound 真删过期行 ----------

    @Test
    void given_expired_rows_when_scheduler_round_then_swept() {
        sessionStore.put("sid-sched-dead",
                new BffSession(940107L, "待扫用户", null, null, null));
        whenClock().thenReturn(T0.plus(sessionProperties.getTtl().dividedBy(2)));
        sessionStore.put("sid-sched-live",
                new BffSession(940108L, "在期用户", null, null, null));

        whenClock().thenReturn(T0.plus(sessionProperties.getTtl()).plusSeconds(1));
        cleanupScheduler.sweepRound();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idn_sessions WHERE session_id = 'sid-sched-dead'",
                Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idn_sessions WHERE session_id = 'sid-sched-live'",
                Long.class)).isEqualTo(1L);
    }

    // -------- 测试工具 --------

    /** 时钟替身：固定 T0，各用例按需快进。 */
    private org.mockito.stubbing.OngoingStubbing<Instant> whenClock() {
        return org.mockito.Mockito.when(clock.instant());
    }
}
