package com.aieducenter.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.Model;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import com.aieducenter.aiplatform.base.metering.domain.model.UsageEvent;
import com.aieducenter.aiplatform.base.metering.domain.port.UsageEventSink;

/**
 * {@link MeteredModel}（#109 模型边界计量）：每次 {@code stream()} 收口把该次调用的
 * usage 直报当前轮 {@link MeteringScope}——主循环/压缩/记忆抽取共用同一模型实例、
 * 各自直调，在此单点收口（取代只认 ModelCallEndEvent 的旧源）。usage 取末个非空
 * usage 胜出（同框架 ReasoningContext 覆盖式累积口径）；失败轮已耗 token 如实计量
 * （doFinally）；无活跃轮（fire-and-forget）不报。
 */
@ExtendWith(MockitoExtension.class)
class MeteredModelTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-25T10:00:00Z"), ZoneOffset.UTC);

    @Mock
    private Model delegate;

    @Mock
    private UsageEventSink sink;

    @Captor
    private ArgumentCaptor<UsageEvent> usageCaptor;

    @AfterEach
    void tearDown() {
        MeteringScope.exit();
    }

    private static ChatResponse response(ChatUsage usage) {
        return ChatResponse.builder().usage(usage).build();
    }

    @Test
    void given_scope_when_stream_with_usage_then_reports_event_with_model_attribution() {
        when(delegate.stream(any(), any(), any()))
                .thenReturn(Flux.just(response(new ChatUsage(100, 40, 20, 0.5))));
        MeteringScope.enter(new MeteringScope("run-1", "s-1",
                new UsageContext("prj-1", Map.of("agentKind", "ba")), CLOCK, sink,
                "agent-usage-run-1"));
        try {
            MeteredModel.wrap(delegate, "deepseek:deepseek-v4-flash")
                    .stream(List.of(), null, null).blockLast();
        }
        finally {
            MeteringScope.exit();
        }

        verify(sink).report(usageCaptor.capture());
        UsageEvent event = usageCaptor.getValue();
        assertThat(event.eventId()).isEqualTo("agent-usage-run-1-1");
        assertThat(event.ts()).isEqualTo(Instant.parse("2026-08-25T10:00:00Z"));
        assertThat(event.subject()).isEqualTo("prj-1");
        assertThat(event.runId()).isEqualTo("run-1");
        assertThat(event.sessionId()).isEqualTo("s-1");
        assertThat(event.provider()).isEqualTo("deepseek");
        assertThat(event.model()).isEqualTo("deepseek-v4-flash");
        assertThat(event.dims()).containsEntry("agentKind", "ba");
        // DeepSeek 协议归一化：input = prompt(100) − cacheHit(20) = 80，cacheRead = 20
        assertThat(event.tokens().input()).isEqualTo(80);
        assertThat(event.tokens().output()).isEqualTo(40);
        assertThat(event.tokens().cacheRead()).isEqualTo(20);
    }

    @Test
    void given_scope_when_stream_multiple_usage_chunks_then_last_non_null_wins_not_summed() {
        // 框架 ReasoningContext 是覆盖式累积（= 而非 +=）：末个非空 usage = 全量，
        // 逐 chunk 累加会重复计费——此处对齐覆盖口径
        when(delegate.stream(any(), any(), any()))
                .thenReturn(Flux.just(
                        response(new ChatUsage(10, 5, 0, 0.1)),
                        response(new ChatUsage(100, 40, 20, 0.5))));
        MeteringScope.enter(new MeteringScope("run-1", "s-1",
                new UsageContext("prj-1", Map.of()), CLOCK, sink, "agent-usage-run-1"));
        try {
            MeteredModel.wrap(delegate, "deepseek:deepseek-v4-flash")
                    .stream(List.of(), null, null).blockLast();
        }
        finally {
            MeteringScope.exit();
        }

        verify(sink).report(usageCaptor.capture());
        assertThat(usageCaptor.getValue().tokens().input()).isEqualTo(80);
        assertThat(usageCaptor.getValue().tokens().output()).isEqualTo(40);
    }

    @Test
    void given_no_scope_when_stream_then_no_report() {
        // fire-and-forget 记忆 flush（boundedElastic 线程）看不到本轮 ThreadLocal——
        // 不报不发明归属（其来源已在工厂侧关闭）
        when(delegate.stream(any(), any(), any()))
                .thenReturn(Flux.just(response(new ChatUsage(100, 40, 0, 0.5))));

        MeteredModel.wrap(delegate, "deepseek:deepseek-v4-flash")
                .stream(List.of(), null, null).blockLast();

        verifyNoInteractions(sink);
    }

    @Test
    void given_scope_when_stream_error_after_usage_then_consumed_usage_still_reported() {
        // 失败轮已耗 token 如实计量（doFinally 收口，对齐 run 收口口径）
        when(delegate.stream(any(), any(), any()))
                .thenReturn(Flux.concat(
                        Flux.just(response(new ChatUsage(100, 40, 0, 0.5))),
                        Flux.error(new RuntimeException("mid-stream boom"))));

        MeteringScope.enter(new MeteringScope("run-1", "s-1",
                new UsageContext("prj-1", Map.of()), CLOCK, sink, "agent-usage-run-1"));
        try {
            MeteredModel.wrap(delegate, "deepseek:deepseek-v4-flash")
                    .stream(List.of(), null, null).onErrorComplete().blockLast();
        }
        finally {
            MeteringScope.exit();
        }

        verify(sink).report(usageCaptor.capture());
        assertThat(usageCaptor.getValue().tokens().input()).isEqualTo(100);
        assertThat(usageCaptor.getValue().tokens().output()).isEqualTo(40);
    }

    @Test
    void given_scope_when_stream_zero_or_null_usage_then_no_report() {
        // 零用量不报（不发明零事件）
        when(delegate.stream(any(), any(), any()))
                .thenReturn(Flux.just(response(null)));

        MeteringScope.enter(new MeteringScope("run-1", "s-1",
                new UsageContext("prj-1", Map.of()), CLOCK, sink, "agent-usage-run-1"));
        try {
            MeteredModel.wrap(delegate, "deepseek:deepseek-v4-flash")
                    .stream(List.of(), null, null).blockLast();
        }
        finally {
            MeteringScope.exit();
        }

        verify(sink, never()).report(any());
    }
}
