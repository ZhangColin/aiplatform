package com.aieducenter.aiplatform.base.agentscope;

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

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ModelHttpException;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.exception.OpenAIException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.aieducenter.aiplatform.base.metering.domain.model.UsageEvent;
import com.aieducenter.aiplatform.base.metering.domain.port.UsageEventSink;

/**
 * 模型流断流自动重试·真实失败注入（#321 AC「容器活体」腿）：本机起假模型端点
 * （JDK HttpServer，票面明示的注入方式之一），真框架 {@link OpenAIChatModel}
 * 传输栈真发 HTTP——注入真实 429/5xx 响应，验两路：①重试接续成功（429 后
 * 200 SSE 正常收流、内容不重放、计量照常入账）；②最终失败上抛（恒 5xx 有界
 * 重试后末次原始错误如实上抛、非 RetryExhaustedException、计量面零事件）。
 * 模型流层无 docker 面（docker 是工作区侧），真实 HTTP 失败注入即本层活体
 * seam（DockerEnvironmentBackendTest 先例的等价真实失败注入缝）。
 */
class ModelStreamRetryInjectionTest {

    /** SSE 成功体：一条内容增量 + 一条收尾（usage）+ [DONE]。 */
    private static final String SSE_SUCCESS = """
            data: {"id":"chatcmpl-1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"重试接续成功"},"finish_reason":null}]}

            data: {"id":"chatcmpl-1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":12,"completion_tokens":7,"total_tokens":19}}

            data: [DONE]

            """;

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int[] statusScript;

    @BeforeEach
    void startEndpoint() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> handle(exchange));
        server.start();
    }

    @AfterEach
    void stopEndpoint() {
        if (server != null) {
            server.stop(0);
        }
        MeteringScope.exit();
    }

    private void handle(HttpExchange exchange) throws java.io.IOException {
        int attempt = requests.incrementAndGet();
        int status = statusScript[Math.min(attempt - 1, statusScript.length - 1)];
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
        byte[] body = ("{\"error\":{\"message\":\"injected " + status + "\",\"code\":" + status + "}}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /** 生产同形装配：内层重试关闭（同工厂 resolve 注入）+ streamLayer 包真模型客户端。 */
    private ModelComposition composition() {
        OpenAIChatModel raw = OpenAIChatModel.builder()
                .apiKey("test-key")
                .modelName("deepseek-v4-flash")
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .stream(true)
                // 生产 resolve 路径同源注入（INNER_RETRY_DISABLED context 组件）——
                // 关框架内建重试（缺省会经 ensureDefaultExecutionConfig 烧入
                // MODEL_DEFAULTS），重试由平台层独占
                .generateOptions(AgentscopeHarnessAgentFactory.innerRetryDisabledOptions())
                .build();
        AgentscopeProperties properties = new AgentscopeProperties();
        properties.setModelStreamRetryMaxAttempts(3);
        properties.setModelStreamRetryInitialBackoff(Duration.ofMillis(10));
        properties.setModelStreamRetryMaxBackoff(Duration.ofMillis(50));
        List<UsageEvent> events = new ArrayList<>();
        UsageEventSink sink = events::add;
        return new ModelComposition(raw, properties, events, sink);
    }

    /** 一次活体调用的脚手架（模型装配 + 计量归集）。 */
    private record ModelComposition(OpenAIChatModel raw, AgentscopeProperties properties,
            List<UsageEvent> events, UsageEventSink sink) {
    }

    private List<ChatResponse> streamWithScope(ModelComposition composition) {
        MeteringScope.enter(new MeteringScope("run-live", "s-live",
                new UsageContext("prj-live", Map.of()),
                Clock.fixed(Instant.parse("2026-10-16T10:00:00Z"), ZoneOffset.UTC),
                composition.sink(), "prefix-live"));
        return AgentscopeHarnessAgentFactory
                .streamLayer(composition.raw(), "deepseek:deepseek-v4-flash",
                        composition.properties())
                .stream(List.of(), null, null)
                .collectList()
                .block(Duration.ofSeconds(30));
    }

    @Test
    @Timeout(60)
    void given_real_429_then_200_when_stream_then_retried_over_real_http_and_metered()
            throws InterruptedException {
        statusScript = new int[] {429, 200};
        ModelComposition composition = composition();

        List<ChatResponse> received = streamWithScope(composition);
        // doFinally 计量收口与下游终端信号跨线程——有界等待消竞态（超时即后续断言自然红）
        awaitCondition(2000, () -> !composition.events().isEmpty());

        // 重试接续成功：第二发请求收流（429 那发零数据块）
        assertThat(requests.get()).isEqualTo(2);
        // 内容恰一次（重试不重放已消费块——429 在任何数据块之前，天然满足护栏）
        String text = received.stream()
                .flatMap(r -> r.getContent().stream())
                .filter(TextBlock.class::isInstance)
                .map(block -> ((TextBlock) block).getText())
                .reduce("", String::concat);
        assertThat(text).isEqualTo("重试接续成功");
        // 重试期间计量不被破坏：成功尝试的 usage 照常入账（恰一条、token 如实）
        assertThat(composition.events()).hasSize(1);
        assertThat(composition.events().get(0).tokens().input()).isEqualTo(12);
        assertThat(composition.events().get(0).tokens().output()).isEqualTo(7);
    }

    /** 有界条件等待（doFinally 收口跨线程，消计量断言竞态）。 */
    private static void awaitCondition(int timeoutMs, java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
    }

    @Test
    @Timeout(60)
    void given_real_5xx_always_when_stream_then_bounded_attempts_and_original_error_over_real_http() {
        statusScript = new int[] {500};
        ModelComposition composition = composition();

        assertThatThrownBy(() -> streamWithScope(composition))
                // 末次原始错误如实上抛（真 HTTP 500、模型层异常形态——框架按状态码
                // 映射的具体子类），不是 reactor RetryExhaustedException（非
                // OpenAIException 族，isInstanceOf 即排除）——重试面不吞真错误
                .isInstanceOf(OpenAIException.class)
                .satisfies(e -> assertThat(((ModelHttpException) e).getStatusCode()).isEqualTo(500));

        // 有界：恰 3 发（首试 + 2 重试）＝平台层次数上界；内层若未关会叠成
        // 3×3=9 发（缺省 MODEL_DEFAULTS 内建重试）——本断言同钉「内层已关」
        assertThat(requests.get()).isEqualTo(3);
        // 恒败零数据块 → 计量面零事件（无 usage 可报，不发明零事件——路径未被破坏）
        assertThat(composition.events()).isEmpty();
    }
}
