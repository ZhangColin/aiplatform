package com.semgravio.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.openai.exception.OpenAIException;
import reactor.core.publisher.Flux;

/**
 * {@link FailoverModel} 链式切换核心（#557，ADR-0053 ③）——候选队列/懒装配/
 * 分类切换/已发块护栏/切换留痕的公共面观测：fake Model 组合逐行为钉死。真实
 * 传输栈的故障注入（真 HTTP 假端点）另见 {@code ModelFailoverInjectionTest}
 * ——本类只钉切换决策语义。
 */
class FailoverModelTest {

    /** 假候选模型：脚本化 stream() 行为（逐次出队），并计数调用。 */
    private static final class ScriptedModel implements Model {

        private final Queue<Supplier<Flux<ChatResponse>>> script = new ConcurrentLinkedQueue<>();
        final AtomicInteger streams = new AtomicInteger();

        void thenEmit(String text) {
            script.add(() -> Flux.just(text(text)));
        }

        void thenFail(Throwable error) {
            script.add(() -> Flux.error(error));
        }

        /** 流中途失败：先发一块再错（已发块护栏的注入形）。 */
        void thenFailAfterBlock() {
            script.add(() -> Flux.just(text("已发出的块"))
                    .concatWith(Flux.error(MID_STREAM_ERROR)));
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                GenerateOptions options) {
            streams.incrementAndGet();
            Supplier<Flux<ChatResponse>> next = script.poll();
            return next == null ? Flux.error(new IllegalStateException("脚本耗尽"))
                    : next.get();
        }

        @Override
        public String getModelName() {
            return "scripted";
        }
    }

    /** 真实 SDK 401 形（OpenAIException.create 按状态码映射的具体子类）。 */
    private static OpenAIException authError(int status, String message) {
        return OpenAIException.create(status, message, "auth", "{}");
    }

    private static ChatResponse text(String value) {
        return new ChatResponse("resp-1", List.of(TextBlock.builder().text(value).build()),
                null, Map.of(), "stop");
    }

    /** 订阅链写入计量上下文（runTurn 同形态——runId/sessionId 是留痕的 run 上下文腿）。 */
    private static <T> Flux<T> withScope(Flux<T> source) {
        return source.contextWrite(ctx -> ctx.put(MeteringScope.CONTEXT_KEY, new MeteringScope(
                "run-fo", "s-fo", new UsageContext("prj-fo", Map.of()),
                Clock.fixed(Instant.parse("2026-11-19T10:00:00Z"), ZoneOffset.UTC),
                events -> { }, "prefix-fo")));
    }

    private static List<ModelCandidate> candidatesOf(ScriptedModel... models) {
        List<ModelCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < models.length; i++) {
            candidates.add(new ModelCandidate("ch" + i, "model-" + i, null));
        }
        return candidates;
    }

    @Test
    void given_primary_401_when_stream_then_switch_to_backup_and_trace() {
        ScriptedModel primary = new ScriptedModel();
        primary.thenFail(authError(401, "Error code: 401 - The API key doesn't exist"));
        ScriptedModel backup = new ScriptedModel();
        backup.thenEmit("备候选接续");
        List<ModelCandidate> candidates = candidatesOf(primary, backup);
        ModelCandidateQueue queue = new ModelCandidateQueue("pro", candidates);
        List<ModelSwitchTraceSink.Trace> traces = new ArrayList<>();

        List<ChatResponse> received = withScope(new FailoverModel(queue,
                candidate -> candidate.providerKey().equals("ch0") ? primary : backup,
                traces::add)
                .stream(List.of(), null, null))
                .collectList().block(Duration.ofSeconds(10));

        // 用户面无感：产出全部来自备候选（401 零发射，无重复/倒带）
        assertThat(received).extracting(r -> ((TextBlock) r.getContent().get(0)).getText())
                .containsExactly("备候选接续");
        // 401 立即切：首候选恰一次调用（候选内零重试——认证失效不可重试）、备候选一次
        assertThat(primary.streams.get()).isEqualTo(1);
        assertThat(backup.streams.get()).isEqualTo(1);
        // 切换留痕：何时/从哪到哪/触发错误/run 上下文
        assertThat(traces).hasSize(1);
        ModelSwitchTraceSink.Trace trace = traces.get(0);
        assertThat(trace.tierKey()).isEqualTo("pro");
        assertThat(trace.fromProviderKey()).isEqualTo("ch0");
        assertThat(trace.fromModelName()).isEqualTo("model-0");
        assertThat(trace.toProviderKey()).isEqualTo("ch1");
        assertThat(trace.toModelName()).isEqualTo("model-1");
        assertThat(trace.triggerError()).contains("401");
        assertThat(trace.runId()).isEqualTo("run-fo");
        assertThat(trace.sessionId()).isEqualTo("s-fo");
        assertThat(trace.occurredAt()).isNotNull();
    }

    @Test
    void given_emitted_block_then_failure_when_stream_then_no_switch_and_interrupt() {
        // 流中途失败（已发块后）：不切候选——切换重发＝输出重复/倒带（ADR-0053 拒绝
        // 「已发块后整段重发」）；如实中断上抛，保「可重发」语义
        ScriptedModel primary = new ScriptedModel();
        primary.thenFailAfterBlock();
        ScriptedModel backup = new ScriptedModel();
        backup.thenEmit("不应被切到");
        ModelCandidateQueue queue = new ModelCandidateQueue("pro",
                candidatesOf(primary, backup));
        List<ModelSwitchTraceSink.Trace> traces = new ArrayList<>();

        List<ChatResponse> received = new ArrayList<>();
        assertThatThrownBy(() -> withScope(new FailoverModel(queue,
                candidate -> candidate.providerKey().equals("ch0") ? primary : backup,
                traces::add)
                .stream(List.of(), null, null))
                .doOnNext(received::add)
                .collectList().block(Duration.ofSeconds(10)))
                .isSameAs(MID_STREAM_ERROR);

        // 已发出的块如实到达下游（无重复、无倒带），备候选零调用、零留痕
        assertThat(received).extracting(r -> ((TextBlock) r.getContent().get(0)).getText())
                .containsExactly("已发出的块");
        assertThat(backup.streams.get()).isZero();
        assertThat(traces).isEmpty();
    }

    private static final RuntimeException MID_STREAM_ERROR =
            new RuntimeException("流中途失败");

    @Test
    void given_all_candidates_failed_when_stream_then_last_real_error_bare() {
        // 全队列尽（401/402/5xx 组合）：上抛末候选的末次真错误——不被重试机制的皮
        // 裹走（#555 后真错误裸露，用户面文案可见真因）
        ScriptedModel primary = new ScriptedModel();
        primary.thenFail(authError(401, "Error code: 401 - key deleted"));
        ScriptedModel middle = new ScriptedModel();
        middle.thenFail(authError(402, "Error code: 402 - insufficient balance"));
        ScriptedModel last = new ScriptedModel();
        last.thenFail(authError(500, "Error code: 500 - internal"));
        ModelCandidateQueue queue = new ModelCandidateQueue("pro",
                candidatesOf(primary, middle, last));
        List<ModelSwitchTraceSink.Trace> traces = new ArrayList<>();

        assertThatThrownBy(() -> withScope(new FailoverModel(queue,
                candidate -> switch (candidate.providerKey()) {
                    case "ch0" -> primary;
                    case "ch1" -> middle;
                    default -> last;
                }, traces::add)
                .stream(List.of(), null, null))
                .collectList().block(Duration.ofSeconds(10)))
                .isInstanceOf(OpenAIException.class)
                .satisfies(e -> assertThat(((OpenAIException) e).getStatusCode()).isEqualTo(500))
                .hasMessageContaining("Error code: 500 - internal");

        // 逐候选各一次尝试、逐切换一痕（0→1、1→2）
        assertThat(primary.streams.get()).isEqualTo(1);
        assertThat(middle.streams.get()).isEqualTo(1);
        assertThat(last.streams.get()).isEqualTo(1);
        assertThat(traces).extracting(ModelSwitchTraceSink.Trace::toProviderKey)
                .containsExactly("ch1", "ch2");
    }

    @Test
    void given_business_4xx_when_stream_then_no_switch_and_propagate() {
        // 业务 4xx（请求形问题——400 等）：换通道不解决，不切、原样上抛
        ScriptedModel primary = new ScriptedModel();
        primary.thenFail(OpenAIException.create(400, "Error code: 400 - invalid request",
                "invalid_request", "{}"));
        ScriptedModel backup = new ScriptedModel();
        backup.thenEmit("不应被切到");
        ModelCandidateQueue queue = new ModelCandidateQueue("pro",
                candidatesOf(primary, backup));
        List<ModelSwitchTraceSink.Trace> traces = new ArrayList<>();

        assertThatThrownBy(() -> withScope(new FailoverModel(queue,
                candidate -> candidate.providerKey().equals("ch0") ? primary : backup,
                traces::add)
                .stream(List.of(), null, null))
                .collectList().block(Duration.ofSeconds(10)))
                .isInstanceOf(OpenAIException.class)
                .satisfies(e -> assertThat(((OpenAIException) e).getStatusCode()).isEqualTo(400));

        assertThat(backup.streams.get()).isZero();
        assertThat(traces).isEmpty();
    }

    @Test
    void given_retryable_exhausted_in_candidate_when_stream_then_retried_then_switched() {
        // 429/超时/5xx：候选内先按 #321 分类重试（内圈 RetryingModel）、耗尽才切下一
        // 候选——候选装配即 streamLayer 包装（生产同形组合）
        ScriptedModel primary = new ScriptedModel();
        primary.thenFail(authError(429, "Error code: 429 - rate limited"));
        primary.thenFail(authError(429, "Error code: 429 - rate limited"));
        primary.thenFail(authError(429, "Error code: 429 - rate limited"));
        primary.thenFail(authError(429, "Error code: 429 - rate limited"));
        ScriptedModel backup = new ScriptedModel();
        backup.thenEmit("重试耗尽后接续");
        AgentscopeProperties properties = new AgentscopeProperties();
        properties.setModelStreamRetryMaxAttempts(3);
        properties.setModelStreamRetryInitialBackoff(Duration.ofMillis(5));
        properties.setModelStreamRetryMaxBackoff(Duration.ofMillis(20));
        ModelCandidateQueue queue = new ModelCandidateQueue("flash",
                candidatesOf(primary, backup));
        List<ModelSwitchTraceSink.Trace> traces = new ArrayList<>();

        List<ChatResponse> received = withScope(new FailoverModel(queue,
                candidate -> candidate.providerKey().equals("ch0")
                        ? RetryingModel.wrap(primary, properties)
                        : backup,
                traces::add)
                .stream(List.of(), null, null))
                .collectList().block(Duration.ofSeconds(10));

        assertThat(received).extracting(r -> ((TextBlock) r.getContent().get(0)).getText())
                .containsExactly("重试耗尽后接续");
        // 首候选恰 3 发（首试＋2 重试＝候选内耗尽）、备候选 1 发接续；一痕
        assertThat(primary.streams.get()).isEqualTo(3);
        assertThat(backup.streams.get()).isEqualTo(1);
        assertThat(traces).hasSize(1);
        assertThat(traces.get(0).triggerError()).contains("429");
    }

    @Test
    void given_backup_never_needed_when_stream_then_backup_assembly_lazy() {
        // 懒装配：备候选的模型解析（ModelRegistry.resolve＋流层包装）不发生在首候选
        // 健康运行时——备通道配置坏不阻塞主通道
        ScriptedModel primary = new ScriptedModel();
        primary.thenEmit("主候选健康");
        AtomicInteger backupAssemblies = new AtomicInteger();
        ModelCandidateQueue queue = new ModelCandidateQueue("pro",
                candidatesOf(primary, new ScriptedModel()));

        List<ChatResponse> received = new FailoverModel(queue,
                candidate -> {
                    if (!candidate.providerKey().equals("ch0")) {
                        backupAssemblies.incrementAndGet();
                    }
                    return primary;
                }, null)
                .stream(List.of(), null, null)
                .collectList().block(Duration.ofSeconds(10));

        assertThat(received).hasSize(1);
        assertThat(backupAssemblies.get()).isZero();
    }

    @Test
    void given_trace_sink_failing_when_switch_then_stream_unaffected() {
        // 留痕是观察面不是正确性腿：端口炸掉不烧穿切换与生成流
        ScriptedModel primary = new ScriptedModel();
        primary.thenFail(authError(403, "Error code: 403 - forbidden"));
        ScriptedModel backup = new ScriptedModel();
        backup.thenEmit("留痕失败照切");

        List<ChatResponse> received = withScope(new FailoverModel(
                new ModelCandidateQueue("pro", candidatesOf(primary, backup)),
                candidate -> candidate.providerKey().equals("ch0") ? primary : backup,
                trace -> { throw new IllegalStateException("留痕库故障"); })
                .stream(List.of(), null, null))
                .collectList().block(Duration.ofSeconds(10));

        assertThat(received).extracting(r -> ((TextBlock) r.getContent().get(0)).getText())
                .containsExactly("留痕失败照切");
    }
}
