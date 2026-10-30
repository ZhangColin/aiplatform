package com.semgravio.aiplatform.business.identity.infrastructure.code;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.semgravio.aiplatform.business.identity.domain.enums.AccountBindingProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 在途验证码存储（#327）：冷却窗/有效期/试错上限的边界语义——时钟可拨、不真等。
 * 边界口径：冷却窗到点即放行（exclusive）、有效期到点即过期（inclusive）、
 * 验码成功即消费（一次性）、错码记账超次作废。
 */
class VerificationCodeStoreTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-16T08:00:00Z"));

    private VerificationCodeStore store(long cooldownSeconds) {
        return new VerificationCodeStore(config(300, cooldownSeconds, 5));
    }

    private com.semgravio.aiplatform.business.identity.application.VerificationCodeProperties config(
            long ttlSeconds, long cooldownSeconds, int maxAttempts) {
        var properties = new com.semgravio.aiplatform.business.identity.application.VerificationCodeProperties();
        properties.setCodeTtlSeconds(ttlSeconds);
        properties.setSendCooldownSeconds(cooldownSeconds);
        properties.setMaxVerifyAttempts(maxAttempts);
        return properties;
    }

    @Test
    void given_issued_code_when_verify_with_exact_code_then_true_and_consumed() {
        VerificationCodeStore store = store(60);
        store.issue(AccountBindingProvider.EMAIL, "a@example.com", "123456", clock.instant());

        assertThat(store.verifyAndConsume(AccountBindingProvider.EMAIL, "a@example.com",
                "123456", clock.instant())).isTrue();
        // 一次性：验过的码立刻作废（防同一码二次登录）
        assertThat(store.verifyAndConsume(AccountBindingProvider.EMAIL, "a@example.com",
                "123456", clock.instant())).isFalse();
    }

    @Test
    void given_two_channels_same_identifier_when_operate_then_independent_entries() {
        VerificationCodeStore store = store(60);
        store.issue(AccountBindingProvider.EMAIL, "shared@example.com", "111111", clock.instant());
        store.issue(AccountBindingProvider.PHONE, "shared@example.com", "222222", clock.instant());

        assertThat(store.verifyAndConsume(AccountBindingProvider.EMAIL, "shared@example.com",
                "222222", clock.instant())).isFalse(); // 通道间不串码
        assertThat(store.verifyAndConsume(AccountBindingProvider.PHONE, "shared@example.com",
                "222222", clock.instant())).isTrue();
    }

    @Test
    void given_cooldown_window_when_send_again_then_blocked_until_boundary() {
        VerificationCodeStore store = store(60);
        store.issue(AccountBindingProvider.EMAIL, "a@example.com", "123456", clock.instant());

        assertThat(store.inCooldown(AccountBindingProvider.EMAIL, "a@example.com", clock.instant()))
                .isTrue(); // 发码即刻起冷却
        clock.advanceSeconds(59);
        assertThat(store.inCooldown(AccountBindingProvider.EMAIL, "a@example.com", clock.instant()))
                .isTrue(); // 窗内仍拦
        clock.advanceSeconds(1);
        assertThat(store.inCooldown(AccountBindingProvider.EMAIL, "a@example.com", clock.instant()))
                .isFalse(); // 到点即放行（exclusive）
    }

    @Test
    void given_one_second_before_ttl_when_verify_then_still_valid() {
        VerificationCodeStore store = store(60);
        store.issue(AccountBindingProvider.EMAIL, "a@example.com", "123456", clock.instant());

        clock.advanceSeconds(299); // 有效期内最后一秒：仍可验
        assertThat(store.verifyAndConsume(AccountBindingProvider.EMAIL, "a@example.com",
                "123456", clock.instant())).isTrue();
    }

    @Test
    void given_ttl_boundary_when_verify_then_expired_at_exact_expiry() {
        VerificationCodeStore store = store(60);
        store.issue(AccountBindingProvider.EMAIL, "a@example.com", "123456", clock.instant());

        clock.advanceSeconds(300); // 恰在有效期终点：inclusive 过期
        assertThat(store.verifyAndConsume(AccountBindingProvider.EMAIL, "a@example.com",
                "123456", clock.instant())).isFalse();
    }

    @Test
    void given_max_attempts_exhausted_when_verify_with_correct_code_then_rejected() {
        VerificationCodeStore store = store(60);
        store.issue(AccountBindingProvider.EMAIL, "a@example.com", "123456", clock.instant());

        for (int i = 0; i < 4; i++) { // max=5：前四次错码记账
            assertThat(store.verifyAndConsume(AccountBindingProvider.EMAIL, "a@example.com",
                    "000000", clock.instant())).isFalse();
        }
        // 第五次是最后机会：错即作废
        assertThat(store.verifyAndConsume(AccountBindingProvider.EMAIL, "a@example.com",
                "000000", clock.instant())).isFalse();
        assertThat(store.verifyAndConsume(AccountBindingProvider.EMAIL, "a@example.com",
                "123456", clock.instant())).isFalse(); // 已作废，正确码也不认
    }

    @Test
    void given_reissue_after_cooldown_when_verify_old_code_then_rejected_new_accepted() {
        VerificationCodeStore store = store(60);
        store.issue(AccountBindingProvider.EMAIL, "a@example.com", "111111", clock.instant());
        clock.advanceSeconds(60);

        store.issue(AccountBindingProvider.EMAIL, "a@example.com", "222222", clock.instant());

        assertThat(store.verifyAndConsume(AccountBindingProvider.EMAIL, "a@example.com",
                "111111", clock.instant())).isFalse(); // 旧码随重发作废
        assertThat(store.verifyAndConsume(AccountBindingProvider.EMAIL, "a@example.com",
                "222222", clock.instant())).isTrue();
    }

    /** 可拨时钟（时间语义边界测试，不真等） */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
