package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

/**
 * {@link TtlCache}（#453-2 每迭代配置读降频件）：TTL 窗口内零查库直返缓存、
 * 过期刷新、读失败不毒化缓存（异常上抛、下次窗口重取）、正时长校验。
 */
class TtlCacheTest {

    /** 可变时钟（测试推进墙钟越过 TTL 窗口）。 */
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        private MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
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
            return now.get();
        }

        private void advance(Duration delta) {
            now.updateAndGet(instant -> instant.plus(delta));
        }
    }

    private static final Duration TTL = Duration.ofSeconds(5);

    @Test
    void given_reads_within_ttl_when_get_then_loader_called_once() {
        TtlCache<String> cache = new TtlCache<>(TTL, new MutableClock(Instant.EPOCH));
        AtomicInteger loads = new AtomicInteger();
        Supplier<String> loader = () -> {
            loads.incrementAndGet();
            return "v1";
        };

        String first = cache.get(loader);
        String second = cache.get(loader);

        assertThat(first).isEqualTo("v1");
        assertThat(second).isEqualTo("v1");
        assertThat(loads).hasValue(1);
    }

    @Test
    void given_ttl_passed_when_get_then_refreshed() {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        TtlCache<String> cache = new TtlCache<>(TTL, clock);
        AtomicInteger loads = new AtomicInteger();

        cache.get(() -> {
            loads.incrementAndGet();
            return "v" + loads.get();
        });
        clock.advance(Duration.ofSeconds(6)); // 越过 5s 窗口
        String refreshed = cache.get(() -> {
            loads.incrementAndGet();
            return "v" + loads.get();
        });

        assertThat(refreshed).isEqualTo("v2");
        assertThat(loads).hasValue(2);
    }

    @Test
    void given_loader_failure_when_get_then_propagates_and_not_cached() {
        TtlCache<String> cache = new TtlCache<>(TTL, new MutableClock(Instant.EPOCH));
        AtomicInteger loads = new AtomicInteger();
        Supplier<String> failing = () -> {
            loads.incrementAndGet();
            throw new IllegalStateException("db down");
        };

        assertThatThrownBy(() -> cache.get(failing))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cache.get(failing))
                .isInstanceOf(IllegalStateException.class);
        // 失败不毒化缓存：每次都重试读腿（fail-open 归中间件，本类不吞）
        assertThat(loads).hasValue(2);
    }

    @Test
    void given_success_after_failure_when_get_then_success_cached() {
        TtlCache<String> cache = new TtlCache<>(TTL, new MutableClock(Instant.EPOCH));
        AtomicInteger loads = new AtomicInteger();

        try {
            cache.get(() -> {
                loads.incrementAndGet();
                throw new IllegalStateException("db down");
            });
        }
        catch (IllegalStateException ignored) {
            // 预期失败
        }
        String recovered = cache.get(() -> {
            loads.incrementAndGet();
            return "ok";
        });
        String cached = cache.get(() -> {
            loads.incrementAndGet();
            return "ok";
        });

        assertThat(recovered).isEqualTo("ok");
        assertThat(cached).isEqualTo("ok");
        assertThat(loads).hasValue(2); // 失败后一次成功即缓存，窗口内零查库
    }

    @Test
    void given_non_positive_ttl_when_construct_then_rejected() {
        assertThatThrownBy(() -> new TtlCache<>(Duration.ZERO, new MutableClock(Instant.EPOCH)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                () -> new TtlCache<>(Duration.ofSeconds(-1), new MutableClock(Instant.EPOCH)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
