package com.semgravio.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.agentscope.core.model.ChatUsage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import com.semgravio.aiplatform.base.metering.domain.model.UsageEvent;
import com.semgravio.aiplatform.base.metering.domain.port.UsageEventSink;

/**
 * {@link MeteringScope} 判死后收口面（#374 可观测兜底）：判死（超时/异常）后内核
 * 阻塞段仍可能跑完并收口（SSE 读循环等下一行、非流式调用自然完成——取消盲区），
 * 这些「僵尸调用」的计量<b>如实上报不回收</b>（多烧的 token 是真实用量），但必须
 * WARN 显式化（runId + 判死后序次）供计量对账（判死时刻恰有在途调用 vs 取消失效，
 * 两者在日志面同形可见，对账时与判死 WARN 时间线拼合即可定位）。
 */
@ExtendWith(MockitoExtension.class)
class MeteringScopeTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-25T10:00:00Z"), ZoneOffset.UTC);

    @Mock
    private UsageEventSink sink;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        Logger scopeLogger = (Logger) LoggerFactory.getLogger(MeteringScope.class);
        appender = new ListAppender<>();
        appender.start();
        scopeLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        ((Logger) LoggerFactory.getLogger(MeteringScope.class)).detachAppender(appender);
    }

    private MeteringScope scope() {
        return new MeteringScope("run-1", "s-1", new UsageContext("prj-1", Map.of()),
                CLOCK, sink, "agent-usage-run-1");
    }

    @Test
    void given_closed_when_report_then_usage_still_reported_with_zombie_warn() {
        // 判死后收口（僵尸调用）：计量照报（如实计量不回收）+ WARN 带 runId 与
        // 判死后序次（可验证日志样例，AC 口径）
        MeteringScope scope = scope();
        scope.markClosed();

        scope.report(ModelRef.parse("deepseek:deepseek-v4-flash"), new ChatUsage(100, 40, 0, 0.5));

        verify(sink).report(any(UsageEvent.class));
        assertThat(appender.list)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                    assertThat(event.getFormattedMessage())
                            .contains("run-1")
                            .contains("判死")
                            .contains("第 1 次");
                });
    }

    @Test
    void given_closed_when_multiple_reports_then_warn_counts_increment() {
        // 多次僵尸收口按序次计数（对账面可数：判死后第 N 次收口）
        MeteringScope scope = scope();
        scope.markClosed();

        scope.report(ModelRef.parse("deepseek:deepseek-v4-flash"), new ChatUsage(1, 1, 0, 0));
        scope.report(ModelRef.parse("deepseek:deepseek-v4-flash"), new ChatUsage(2, 2, 0, 0));

        assertThat(appender.list).hasSize(2);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("第 1 次");
        assertThat(appender.list.get(1).getFormattedMessage()).contains("第 2 次");
    }

    @Test
    void given_open_when_report_then_no_zombie_warn() {
        // 对照钉子：正常轮收口（未判死）不产 WARN——判死 WARN 是判死路径专属的可
        // 观察面，不进正常生命周期
        scope().report(ModelRef.parse("deepseek:deepseek-v4-flash"), new ChatUsage(100, 40, 0, 0.5));

        verify(sink).report(any(UsageEvent.class));
        assertThat(appender.list).isEmpty();
    }

    @Test
    void given_closed_when_zero_usage_then_no_report_and_no_warn() {
        // 零用量不报口径不因判死漂移（不发明零事件——WARN 也只在真实收口时产）
        MeteringScope scope = scope();
        scope.markClosed();

        scope.report(ModelRef.parse("deepseek:deepseek-v4-flash"), null);

        verify(sink, never()).report(any());
        assertThat(appender.list).isEmpty();
    }
}
