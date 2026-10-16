package com.aieducenter.aiplatform.business.identity.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.aieducenter.aiplatform.business.identity.domain.aggregate.Guest;
import com.aieducenter.aiplatform.business.identity.domain.repository.GuestRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 访客用例（#326）：凭证发放（高熵唯一）、无状态直查（行存在且未作废且在保留期内
 * 即有效）、滑动续期写节流（阈值内零写、到期一写）。
 */
class GuestAppServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");

    /** MutableTestClock 同形（identity BC 既有可测时钟），固定起点按需推进。 */
    private static final class FixedClock extends Clock {
        private Instant now = NOW;

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    private final GuestRepository guestRepository = mock(GuestRepository.class);
    private final FixedClock clock = new FixedClock();
    private final com.aieducenter.aiplatform.business.identity.infrastructure.oidc.SsoProperties
            ssoProperties = new com.aieducenter.aiplatform.business.identity.infrastructure.oidc.SsoProperties();

    private GuestAppService service(Duration retention, Duration threshold) {
        GuestProperties properties = new GuestProperties();
        properties.setRetentionPeriod(retention);
        properties.setRenewThreshold(threshold);
        return new GuestAppService(guestRepository, properties, ssoProperties, clock);
    }

    @Test
    void given_register_when_issue_then_high_entropy_unique_credential_and_row_saved() {
        GuestAppService service = service(Duration.ofDays(30), Duration.ofDays(15));
        when(guestRepository.save(any(Guest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        GuestAppService.IssuedGuest first = service.registerNew();
        GuestAppService.IssuedGuest second = service.registerNew();

        assertThat(first.credential()).isNotBlank();
        assertThat(second.credential()).isNotBlank();
        assertThat(first.credential()).isNotEqualTo(second.credential());
        assertThat(first.guestId()).isNotNull();
        verify(guestRepository, org.mockito.Mockito.times(2)).save(any(Guest.class));
    }

    @Test
    void given_valid_credential_when_resolve_then_guest_id_returned_without_write() {
        GuestAppService service = service(Duration.ofDays(30), Duration.ofDays(15));
        Guest guest = Guest.register("cred-1", LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        when(guestRepository.findByCredential("cred-1")).thenReturn(Optional.of(guest));

        Optional<GuestAppService.ResolvedGuest> resolved = service.resolve("cred-1");

        assertThat(resolved).isPresent();
        assertThat(resolved.get().guestId()).isEqualTo(guest.getId());
        assertThat(resolved.get().renewed()).isFalse(); // 刚活跃，节流窗口内零写
        verify(guestRepository, never()).save(any(Guest.class));
    }

    @Test
    void given_unknown_or_blank_credential_when_resolve_then_empty() {
        GuestAppService service = service(Duration.ofDays(30), Duration.ofDays(15));
        when(guestRepository.findByCredential("unknown")).thenReturn(Optional.empty());

        assertThat(service.resolve("unknown")).isEmpty();
        assertThat(service.resolve("")).isEmpty();
        assertThat(service.resolve(null)).isEmpty();
    }

    @Test
    void given_expired_or_revoked_row_when_resolve_then_empty_and_row_kept() {
        // 过保留期不认、作废不认——数据不删（不触发任何写，行原样保留）
        GuestAppService service = service(Duration.ofDays(30), Duration.ofDays(15));
        Guest expired = Guest.register("cred-expired",
                LocalDateTime.ofInstant(NOW.minus(Duration.ofDays(31)), ZoneOffset.UTC));
        Guest revoked = Guest.register("cred-revoked",
                LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        revoked.revoke(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        when(guestRepository.findByCredential("cred-expired")).thenReturn(Optional.of(expired));
        when(guestRepository.findByCredential("cred-revoked")).thenReturn(Optional.of(revoked));

        assertThat(service.resolve("cred-expired")).isEmpty();
        assertThat(service.resolve("cred-revoked")).isEmpty();
        verify(guestRepository, never()).save(any(Guest.class));
    }

    @Test
    void given_activity_reached_threshold_when_resolve_then_renew_written_once_and_flagged() {
        GuestAppService service = service(Duration.ofDays(30), Duration.ofDays(15));
        Guest guest = Guest.register("cred-1",
                LocalDateTime.ofInstant(NOW.minus(Duration.ofDays(16)), ZoneOffset.UTC));
        when(guestRepository.findByCredential("cred-1")).thenReturn(Optional.of(guest));

        Optional<GuestAppService.ResolvedGuest> resolved = service.resolve("cred-1");

        assertThat(resolved).isPresent();
        assertThat(resolved.get().renewed()).isTrue();
        // 续期写：活跃锚前移到当前时刻（滑动窗口随节流写前移）
        verify(guestRepository).save(argThat(saved ->
                saved.getLastActiveAt().equals(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC))));
    }

    @Test
    void given_credential_cookie_when_build_then_contract_shape() {
        GuestAppService service = service(Duration.ofDays(30), Duration.ofDays(15));

        var cookie = service.credentialCookie("cred-1");

        assertThat(cookie.getName()).isEqualTo("aiplatform_guest");
        assertThat(cookie.getValue()).isEqualTo("cred-1");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Lax");
        assertThat(cookie.getPath()).isEqualTo("/");
        // 浏览器侧寿命对齐保留期（服务端滑续时随节流写刷新，双端同窗）
        assertThat(cookie.getMaxAge().toSeconds()).isEqualTo(Duration.ofDays(30).toSeconds());
    }
}
