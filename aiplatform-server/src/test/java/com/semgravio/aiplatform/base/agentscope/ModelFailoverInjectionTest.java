package com.semgravio.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.semgravio.aiplatform.base.metering.domain.model.UsageEvent;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.exception.OpenAIException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 链式 failover·真实失败注入（#557 AC「故障注入」腿，照 {@code ModelStreamRetry
 * InjectionTest} 先例——本机双假 OpenAI 端点，真框架 {@link OpenAIChatModel} 传输栈
 * 真发 HTTP）：候选队列经<b>生产装配链</b>（{@code failoverLayer}：前缀路由平台
 * 注册工厂＋通道注入创建上下文＋逐候选 streamLayer）组装，验证票面 AC 四条：
 * <ol>
 *   <li>主候选 401 → 自动切备候选 → 生成成功（用户面无感）＋切换留痕；</li>
 *   <li>全候选耗尽（401/402/5xx 组合）→ 上抛末次真错误真因（裸、非重试机制的皮）；</li>
 *   <li>流中途（已发块后）失败 → 不切候选、中断上抛、无重复输出；</li>
 *   <li>429 → 候选内先按 #321 分类重试、耗尽才切下一候选。</li>
 * </ol>
 * 模型流层无 docker 面，真实 HTTP 失败注入即本层活体 seam（WireMock 等价的票面
 * 注入方式之一——仓库既有假端点先例）。
 */
class ModelFailoverInjectionTest {

    /** SSE 成功体：一条内容增量 + 一条收尾（usage）+ [DONE]。 */
    private static final String SSE_SUCCESS = """
            data: {"id":"chatcmpl-1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"failover接续成功"},"finish_reason":null}]}

            data: {"id":"chatcmpl-1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":12,"completion_tokens":7,"total_tokens":19}}

            data: [DONE]

            """;

    /**
     * 流中途失败注入：200 头＋<b>恰一块</b>后挂住不发（永不收口）。为什么不用断连/
     * 坏数据行：实测 OpenAI 传输栈把「块后断连」当正常流收口（假完成）、不可解析/
     * 类型错数据行静默跳过——传输面无真错误信号；真实生产形态的中途失败＝块后挂流
     * 被 #322 单流总时长上限掐断（{@code ModelStreamDurationException}，已发块后），
     * 本注入即该形态（挂住＋短上限），三重护栏（#321 重试护栏/#557 切换护栏/#322
     * 上限）在此交叠。
     */
    private static final String SSE_ONE_CHUNK = """
            data: {"id":"chatcmpl-1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"已发出的块"},"finish_reason":null}]}

            """;

    /** 假端点：状态脚本（末值粘住）＋请求计数＋可选「块后断连」脚本。 */
    private static final class FakeEndpoint {
        final HttpServer server;
        final AtomicInteger requests = new AtomicInteger();
        volatile int[] statusScript = new int[] {200};
        /** 与状态脚本同长：true＝该发 200 头＋单块后断连（流中途失败注入）。 */
        volatile boolean[] abortAfterChunkScript = new boolean[0];

        FakeEndpoint() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext("/", exchange -> handle(exchange));
            server.start();
        }

        private void handle(HttpExchange exchange) throws java.io.IOException {
            int attempt = requests.incrementAndGet();
            int index = Math.min(attempt - 1, statusScript.length - 1);
            int status = statusScript[index];
            boolean abort = abortAfterChunkScript.length > attempt - 1
                    && abortAfterChunkScript[attempt - 1];
            if (status == 200 && abort) {
                // 200 头 + 恰一块 + 挂住（永不收口）——块后挂流的注入形（见
                // SSE_ONE_CHUNK javadoc：传输面无真错误信号，中途失败＝挂流被
                // #322 总时长上限掐断）
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(SSE_ONE_CHUNK.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    sleepQuietly(3_000);
                }
                return;
            }
            if (status == 200) {
                byte[] body = SSE_SUCCESS.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                    out.flush();
                }
                return;
            }
            byte[] body = ("{\"error\":{\"message\":\"injected " + status + "\",\"code\":"
                    + status + "}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void stop() {
            server.stop(0);
        }

        private static void sleepQuietly(long millis) {
            try {
                Thread.sleep(millis);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 平台注册工厂前缀（volcengine 工厂同款接线——baseUrl/key 全由创建上下文带入）。 */
    private static final String PROBE_PREFIX = "fofail";

    /** 备账号通道键（同 vendor 不同账号——deepseek/deepseek-2 主备形态的测试形）。 */
    private static final String PROBE_PREFIX_2 = "fofail2";

    @BeforeAll
    static void registerProbeFactory() {
        ModelRegistry.registerFactory(PROBE_PREFIX + ":(.+)", (modelId, context) -> build(
                modelId.substring(PROBE_PREFIX.length() + 1), context));
        ModelRegistry.registerFactory(PROBE_PREFIX_2 + ":(.+)", (modelId, context) -> build(
                modelId.substring(PROBE_PREFIX_2.length() + 1), context));
    }

    private static Model build(String modelId, ModelCreationContext context) {
        return OpenAIChatModel.builder()
                .apiKey(context.getApiKey())
                .modelName(modelId)
                .baseUrl(context.getBaseUrl())
                .stream(true)
                // 生产 resolve 路径同源注入（INNER_RETRY_DISABLED context 组件）
                .generateOptions(AgentscopeHarnessAgentFactory.innerRetryDisabledOptions())
                .build();
    }

    private FakeEndpoint primary;
    private FakeEndpoint backup;
    private FakeEndpoint third;
    private final AgentscopeProperties properties = new AgentscopeProperties();
    private final List<ModelSwitchTraceSink.Trace> traces = new ArrayList<>();

    @BeforeEach
    void startEndpoints() throws Exception {
        primary = new FakeEndpoint();
        backup = new FakeEndpoint();
        third = new FakeEndpoint();
        properties.setModelStreamRetryMaxAttempts(3);
        properties.setModelStreamRetryInitialBackoff(Duration.ofMillis(10));
        properties.setModelStreamRetryMaxBackoff(Duration.ofMillis(50));
    }

    @AfterEach
    void stopEndpoints() {
        primary.stop();
        backup.stop();
        third.stop();
    }

    /** 生产装配链同形：failoverLayer（前缀路由＋通道注入＋逐候选 streamLayer）。 */
    private Model failoverModel(FakeEndpoint... endpoints) {
        List<ModelCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < endpoints.length; i++) {
            candidates.add(new ModelCandidate(PROBE_PREFIX, "model-" + i,
                    new ModelChannel(endpoints[i].baseUrl(), "sk-" + i, false)));
        }
        return AgentscopeHarnessAgentFactory.failoverLayer(
                new ModelCandidateQueue("pro", candidates), properties, traces::add);
    }

    /** 订阅链写入计量上下文（runTurn 同形态——留痕的 run 上下文腿）。 */
    private List<ChatResponse> streamWithScope(Model model) {
        return model.stream(List.of(), null, null)
                .contextWrite(ctx -> ctx.put(MeteringScope.CONTEXT_KEY, new MeteringScope(
                        "run-fo", "s-fo", new UsageContext("prj-fo", Map.of()),
                        Clock.fixed(Instant.parse("2026-11-19T12:00:00Z"), ZoneOffset.UTC),
                        events -> { }, "prefix-fo")))
                .collectList()
                .block(Duration.ofSeconds(30));
    }

    private static String textOf(List<ChatResponse> received) {
        return received.stream()
                .flatMap(r -> r.getContent().stream())
                .filter(TextBlock.class::isInstance)
                .map(block -> ((TextBlock) block).getText())
                .reduce("", String::concat);
    }

    @Test
    @Timeout(60)
    void given_primary_401_when_stream_then_switch_to_backup_and_succeed_with_trace() {
        // AC1：主候选 401 → 自动切备候选 → 生成成功（用户面无感）；留痕有记录
        primary.statusScript = new int[] {401};
        backup.statusScript = new int[] {200};

        List<ChatResponse> received = streamWithScope(failoverModel(primary, backup));

        // 产出恰一次、全部来自备候选（401 零发射，无重复/倒带）
        assertThat(textOf(received)).isEqualTo("failover接续成功");
        // 401 立即切：首候选恰一发（认证失效不重试）、备候选一发接续
        assertThat(primary.requests.get()).isEqualTo(1);
        assertThat(backup.requests.get()).isEqualTo(1);
        // 切换留痕：何时/从哪到哪/触发错误/run 上下文
        assertThat(traces).hasSize(1);
        ModelSwitchTraceSink.Trace trace = traces.get(0);
        assertThat(trace.tierKey()).isEqualTo("pro");
        assertThat(trace.fromModelName()).isEqualTo("model-0");
        assertThat(trace.toModelName()).isEqualTo("model-1");
        assertThat(trace.triggerError()).contains("401");
        assertThat(trace.runId()).isEqualTo("run-fo");
    }

    @Test
    @Timeout(60)
    void given_primary_401_when_switch_then_usage_event_attributed_to_hit_account_and_vendor() {
        // #558 AC1：failover 命中备账号通道行后，用量事件按实际命中通道行归因——
        // 账号级（provider＝备账号键）＋供应商级（vendor 随通道行，跨账号合并键）
        // 双维度；主备两候选同 vendor（deepseek/deepseek-2 形）
        primary.statusScript = new int[] {401};
        backup.statusScript = new int[] {200};
        List<UsageEvent> reported =
                new ArrayList<>();
        Model model = AgentscopeHarnessAgentFactory.failoverLayer(new ModelCandidateQueue("pro",
                List.of(
                        new ModelCandidate(PROBE_PREFIX, "model-0",
                                new ModelChannel(primary.baseUrl(), "sk-0", false, "fovendor")),
                        new ModelCandidate(PROBE_PREFIX_2, "model-1",
                                new ModelChannel(backup.baseUrl(), "sk-1", false, "fovendor")))),
                properties, traces::add);

        model.stream(List.of(), null, null)
                .contextWrite(ctx -> ctx.put(MeteringScope.CONTEXT_KEY, new MeteringScope(
                        "run-fo", "s-fo", new UsageContext("prj-fo", Map.of()),
                        Clock.fixed(Instant.parse("2026-11-19T12:00:00Z"), ZoneOffset.UTC),
                        reported::add, "prefix-fo")))
                .collectList()
                .block(Duration.ofSeconds(30));

        // 计量收口在信号线程（doFinally）——与下游终端信号无 happens-before，有界
        // 等待收口再断言（同 MeteredModelTest.awaitReported 口径）
        awaitReported(reported);
        // 恰一条事件（401 候选零 usage 不报），归因＝备账号＋供应商
        assertThat(reported).hasSize(1);
        assertThat(reported.get(0).provider()).isEqualTo(PROBE_PREFIX_2);
        assertThat(reported.get(0).vendor()).isEqualTo("fovendor");
        assertThat(reported.get(0).model()).isEqualTo("model-1");
    }

    /** 有界等待 doFinally 计量收口（跨线程收口消竞态；超时后断言自然红）。 */
    private static void awaitReported(
            List<UsageEvent> reported) {
        long deadline = System.currentTimeMillis() + 2000;
        while (reported.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(5);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Test
    @Timeout(60)
    void given_all_candidates_exhausted_when_stream_then_last_real_error_bare() {
        // AC2：全候选耗尽（401/402/5xx 组合）→ 报中断、末次真错误真因裸露
        primary.statusScript = new int[] {401};
        backup.statusScript = new int[] {402};
        third.statusScript = new int[] {500 };

        assertThatThrownBy(() -> streamWithScope(failoverModel(primary, backup, third)))
                // 末候选的真错误（500）裸上抛——非 RetryExhaustedException 的皮
                .isInstanceOf(OpenAIException.class)
                .satisfies(e -> assertThat(((OpenAIException) e).getStatusCode()).isEqualTo(500))
                .hasMessageContaining("injected 500");
        // 「可重发」语义＝错误如实上抛（前端既有中断文案面吃真因）

        // 401/402 单发即切；500 候选内重试耗尽（3 发）才切——队列尽
        assertThat(primary.requests.get()).isEqualTo(1);
        assertThat(backup.requests.get()).isEqualTo(1);
        assertThat(third.requests.get()).isEqualTo(3);
        // 逐切换一痕：0→1（401）、1→2（402）
        assertThat(traces).extracting(ModelSwitchTraceSink.Trace::triggerError)
                .anySatisfy(t -> assertThat(t).contains("401"))
                .anySatisfy(t -> assertThat(t).contains("402"));
    }

    @Test
    @Timeout(60)
    void given_emitted_chunk_then_stream_broken_when_stream_then_no_switch_no_duplicate() {
        // AC3：流中途（已发块后）失败 → 不切候选、中断上抛、无重复输出。注入形＝
        // 块后挂流＋短单流总时长上限（#322 真生产形态，见 SSE_ONE_CHUNK javadoc）
        properties.setModelStreamMaxDuration(Duration.ofMillis(300));
        primary.statusScript = new int[] {200};
        primary.abortAfterChunkScript = new boolean[] {true};

        List<ChatResponse> received = new ArrayList<>();
        assertThatThrownBy(() -> failoverModel(primary, backup)
                .stream(List.of(), null, null)
                .contextWrite(ctx -> ctx.put(MeteringScope.CONTEXT_KEY, new MeteringScope(
                        "run-fo", "s-fo", new UsageContext("prj-fo", Map.of()),
                        Clock.fixed(Instant.parse("2026-11-19T12:00:00Z"), ZoneOffset.UTC),
                        events -> { }, "prefix-fo")))
                .doOnNext(received::add)
                .collectList().block(Duration.ofSeconds(30)))
                // 受检异常经 block 传播被 reactor 裹 ReactiveException——真因是
                // #322 时长上限错误（TimeoutException 族）
                .hasCauseInstanceOf(ModelStreamDurationException.class);

        // 已发出的块如实到达恰一次（无重复/倒带），备候选零调用、零留痕——
        // 时长上限错误虽是可重试分类（#321），两道已发块护栏（重试/切换）都拦下
        assertThat(textOf(received)).isEqualTo("已发出的块");
        assertThat(backup.requests.get()).isZero();
        assertThat(traces).isEmpty();
    }

    @Test
    @Timeout(60)
    void given_retryable_429_until_exhausted_when_stream_then_retried_in_candidate_then_switched()
            throws InterruptedException {
        // AC4：429 候选内先按 #321 分类重试（3 发＝首试＋2 重试）、耗尽才切下一候选
        primary.statusScript = new int[] {429, 429, 429, 429};
        backup.statusScript = new int[] {200};

        List<ChatResponse> received = streamWithScope(failoverModel(primary, backup));
        // doFinally 计量收口跨线程——有界等待无碍断言（此处不查计量，仅消竞态收尾）
        Thread.sleep(50);

        assertThat(textOf(received)).isEqualTo("failover接续成功");
        assertThat(primary.requests.get()).isEqualTo(3);
        assertThat(backup.requests.get()).isEqualTo(1);
        assertThat(traces).hasSize(1);
        assertThat(traces.get(0).triggerError()).contains("429");
    }
}
