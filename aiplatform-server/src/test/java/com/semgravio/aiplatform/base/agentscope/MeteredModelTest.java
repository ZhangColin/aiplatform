package com.semgravio.aiplatform.base.agentscope;

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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import com.semgravio.aiplatform.base.metering.domain.model.UsageEvent;
import com.semgravio.aiplatform.base.metering.domain.port.UsageEventSink;

/**
 * {@link MeteredModel}（#109 模型边界计量 / #324 对账级修复）：每次 {@code stream()}
 * 收口把该次调用的 usage 直报当前轮 {@link MeteringScope}——主循环/压缩/记忆抽取/
 * 委派子智能体共用同一模型实例、各自直调，在此单点收口（取代只认
 * ModelCallEndEvent 的旧源）。计量上下文经订阅链传递（订阅态读取——跨线程/跨迭代
 * 的模型直调装配线程不定，#324）。usage 取末个非空 usage 胜出（同框架
 * ReasoningContext 覆盖式累积口径）；失败轮已耗 token 如实计量（doFinally）；
 * 订阅链无计量上下文不报（不发明归属）。
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

    private static ChatResponse response(ChatUsage usage) {
        return ChatResponse.builder().usage(usage).build();
    }

    /** 当前轮计量上下文（幂等键前缀 agent-usage-{runId}）。 */
    private MeteringScope scopeOf(String runId, Map<String, String> dims) {
        return new MeteringScope(runId, "s-1", new UsageContext("prj-1", dims),
                CLOCK, sink, "agent-usage-" + runId);
    }

    /** 有界等待 doFinally 收口首条上报（跨线程收口消竞态；captor 在 verify 时才填充，此处直查 mock 调用计数）。 */
    private void awaitReported() {
        long deadline = System.currentTimeMillis() + 2000;
        while (org.mockito.Mockito.mockingDetails(sink).getInvocations().isEmpty()
                && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(5);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 订阅链带计量上下文的订阅（runTurn 订阅点的传递形态）。 */
    private void subscribeWithScope(String runId, Map<String, String> dims,
            Flux<ChatResponse> stream) {
        stream.contextWrite(ctx -> ctx.put(MeteringScope.CONTEXT_KEY, scopeOf(runId, dims)))
                .blockLast();
    }

    @Test
    void given_scope_when_stream_with_usage_then_reports_event_with_model_attribution() {
        when(delegate.stream(any(), any(), any()))
                .thenReturn(Flux.just(response(new ChatUsage(100, 40, 20, 0.5))));

        subscribeWithScope("run-1", Map.of("agentKind", "ba"),
                MeteredModel.wrap(delegate, "deepseek:deepseek-v4-flash").stream(List.of(), null, null));

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

        subscribeWithScope("run-1", Map.of(),
                MeteredModel.wrap(delegate, "deepseek:deepseek-v4-flash").stream(List.of(), null, null));

        verify(sink).report(usageCaptor.capture());
        assertThat(usageCaptor.getValue().tokens().input()).isEqualTo(80);
        assertThat(usageCaptor.getValue().tokens().output()).isEqualTo(40);
    }

    @Test
    void given_scope_in_context_when_stream_signals_on_other_thread_then_still_reported() {
        // #324 传递形态：计量上下文随订阅链传播（订阅态读取）——模型流信号线程与
        // 订阅发起线程不同（真实 HTTP 异步边界形态），计量不因跨线程丢
        when(delegate.stream(any(), any(), any()))
                .thenReturn(Flux.just(response(new ChatUsage(100, 40, 0, 0.5)))
                        .subscribeOn(Schedulers.boundedElastic()));

        subscribeWithScope("run-1", Map.of(),
                MeteredModel.wrap(delegate, "deepseek:deepseek-v4-flash").stream(List.of(), null, null));

        // doFinally 计量收口跑在信号线程、与下游终端信号（主线程 verify 处）无 happens-before
        // ——等待收口再断言（超时未收口则 captor 断言自然红，同 #321 awaitCondition 口径）
        awaitReported();
        verify(sink).report(usageCaptor.capture());
        assertThat(usageCaptor.getValue().eventId()).isEqualTo("agent-usage-run-1-1");
    }

    @Test
    void given_no_scope_when_stream_then_no_report() {
        // 不在计量订阅树上的直调（fire-and-forget 记忆 flush 等）——不报不发明归属
        // （其来源已在工厂侧关闭）
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

        MeteredModel.wrap(delegate, "deepseek:deepseek-v4-flash")
                .stream(List.of(), null, null)
                .contextWrite(ctx -> ctx.put(MeteringScope.CONTEXT_KEY,
                        scopeOf("run-1", Map.of())))
                .onErrorComplete()
                .blockLast();

        // 同跨线程收口竞态（错误流同走 doFinally）——等收口再断言
        awaitReported();
        verify(sink).report(usageCaptor.capture());
        assertThat(usageCaptor.getValue().tokens().input()).isEqualTo(100);
        assertThat(usageCaptor.getValue().tokens().output()).isEqualTo(40);
    }

    @Test
    void given_scope_when_stream_zero_or_null_usage_then_no_report() {
        // 零用量不报（不发明零事件）
        when(delegate.stream(any(), any(), any()))
                .thenReturn(Flux.just(response(null)));

        subscribeWithScope("run-1", Map.of(),
                MeteredModel.wrap(delegate, "deepseek:deepseek-v4-flash").stream(List.of(), null, null));

        verify(sink, never()).report(any());
    }
}
