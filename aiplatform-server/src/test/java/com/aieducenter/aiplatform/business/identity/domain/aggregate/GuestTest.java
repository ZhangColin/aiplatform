package com.aieducenter.aiplatform.business.identity.domain.aggregate;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 访客聚合（#326 访客域）：有效性判定（行存在且未作废即有效＋保留期）、滑动续期
 * 节流判定与写、作废语义、建档校验。
 */
class GuestTest {

    private static final Duration RETENTION = Duration.ofDays(30);
    private static final Duration THRESHOLD = Duration.ofDays(15);

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 16, 12, 0);

    @Test
    void given_fresh_guest_when_active_at_then_valid() {
        Guest guest = Guest.register("cred-1", NOW);

        assertThat(guest.activeAt(NOW.plusDays(3), RETENTION)).isTrue();
    }

    @Test
    void given_last_active_plus_retention_when_active_at_then_expired() {
        // 保留期边界：lastActive + retention 即失效（过保留期不认）
        Guest guest = Guest.register("cred-1", NOW);

        assertThat(guest.activeAt(NOW.plus(RETENTION), RETENTION)).isFalse();
        assertThat(guest.activeAt(NOW.plus(RETENTION).plusSeconds(1), RETENTION)).isFalse();
    }

    @Test
    void given_revoked_guest_when_active_at_then_invalid_even_within_retention() {
        // 未作废才有效：作废（#328 绑定平移的落点）即时失效
        Guest guest = Guest.register("cred-1", NOW);
        guest.revoke(NOW.plusHours(1));

        assertThat(guest.activeAt(NOW.plusHours(2), RETENTION)).isFalse();
    }

    @Test
    void given_revoke_twice_when_revoke_then_idempotent_first_timestamp_stands() {
        Guest guest = Guest.register("cred-1", NOW);
        LocalDateTime first = NOW.plusHours(1);
        guest.revoke(first);
        guest.revoke(first.plusHours(1));

        assertThat(guest.getRevokedAt()).isEqualTo(first);
    }

    @Test
    void given_activity_below_threshold_when_renew_due_then_false() {
        // 写节流：距上次活跃未逾阈值不写（读多写少，续期按节流窗口滑动）
        Guest guest = Guest.register("cred-1", NOW);

        assertThat(guest.renewDue(NOW.plus(THRESHOLD).minusSeconds(1), THRESHOLD)).isFalse();
    }

    @Test
    void given_activity_at_or_beyond_threshold_when_renew_due_then_true_and_renew_slides() {
        Guest guest = Guest.register("cred-1", NOW);

        assertThat(guest.renewDue(NOW.plus(THRESHOLD), THRESHOLD)).isTrue();

        guest.renew(NOW.plus(THRESHOLD));
        assertThat(guest.getLastActiveAt()).isEqualTo(NOW.plus(THRESHOLD));
        // 续期后窗口随新活跃滑动：旧时刻不再触发写
        assertThat(guest.renewDue(NOW.plus(THRESHOLD).plusDays(1), THRESHOLD)).isFalse();
        // 有效性窗口同样随续期滑动（保留期自新活跃起算）
        assertThat(guest.activeAt(NOW.plus(THRESHOLD).plus(RETENTION).minusSeconds(1), RETENTION))
                .isTrue();
    }

    @Test
    void given_blank_credential_when_register_then_rejected() {
        assertThatThrownBy(() -> Guest.register(" ", NOW))
                .isInstanceOf(com.cartisan.core.exception.DomainException.class);
        assertThatThrownBy(() -> Guest.register(null, NOW))
                .isInstanceOf(com.cartisan.core.exception.DomainException.class);
    }
}
