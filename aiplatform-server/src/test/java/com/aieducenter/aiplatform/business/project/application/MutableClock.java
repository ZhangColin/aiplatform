package com.aieducenter.aiplatform.business.project.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 可拨动时钟（#112 权限确认超时的测试缝）：封装可变时间点 + 快进，供
 * {@link RunPermissionAppServiceTest} 与 {@link IterationAppServiceTest} 共享——消除
 * 各自 {@code AtomicReference<Instant>} + {@code thenAnswer} 桩的重复。先例见 identity
 * 包 {@code MutableTestClock}（同构不同包，各服务边界内的测试缝）。
 */
class MutableClock extends Clock {

    private Instant now;

    MutableClock(Instant now) {
        this.now = now;
    }

    /** 快进一段时长（断言超时用——不真等）。 */
    void advance(Duration duration) {
        now = now.plus(duration);
    }

    @Override
    public ZoneOffset getZone() {
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
