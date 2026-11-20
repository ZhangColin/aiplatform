package com.semgravio.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.exception.AuthenticationException;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * 内层重试关闭·装配点旋钮注入（#555 / ADR-0053 ①）：平台 {@code INNER_RETRY_DISABLED}
 * 压的 {@code maxAttempts=1} 只到模型 configuredOptions——内核 {@code ReActAgent}
 * 无条件烧 {@code modelConfig(maxRetries=3)} 进<b>每次调用的 callOptions</b>，
 * {@code ModelUtils.applyTimeoutAndRetry} 的 {@code mergeOptions(callOptions,
 * configuredOptions)} 第一参字段级优先压过之（且 retryOn=null 时内核挂恒真 filter，
 * 401 也重试、耗尽 {@code RetryExhaustedException} 裹走真错误）。修法＝两处装配点
 * （主装配 {@code buildAgent}＋子智能体工厂）{@code HarnessAgent.Builder.maxRetries(1)}
 * ——经 modelConfig→callOptions 与平台 configuredOptions 一致，内核重试面＝1。
 *
 * <p><b>本类盖住的回归缺口（既有注入测试的绿灯假象）</b>：
 * {@code ModelStreamRetryInjectionTest} 模型层直调传 <b>null callOptions</b>——
 * {@code mergeOptions(null, configured)} 短路返回 configured（maxAttempts=1）恒单发，
 * 从未复现「内核构造非 null callOptions」这一层。本类走<b>生产同款 agent 全链</b>
 * （工厂 obtain 真构建 HarnessAgent、内核 reasoning 真构造 callOptions），假 OpenAI
 * 端点（JDK HttpServer，#321 注入先例同构）恒 401——修前红（每轮 3 发＋被
 * RetryExhaustedException 裹走）、修后绿（每轮单发＋裸 AuthenticationException）。
 * 模型级「非 null callOptions 带内核修前形态 ec(maxAttempts=3)」的最小复现哨兵
 * 见 {@code ModelStreamRetryInjectionTest}（钉「该形态致命」＝旋钮存在的理由）。</p>
 *
 * <p><b>#561 压缩中间件吞错重放（2.0.1 形态，2.0.3 起上游已修——#553 升级实证）</b>：
 * 2.0.1 的 {@code CompactionMiddleware.onReasoning} 把 {@code onErrorResume} 挂在
 * 「压缩预检＋fn.apply（整轮 reasoning）」整链上且无错误分类——主模型 401 也被
 * 当「压缩失败」吞掉后<b>无条件重放一轮 reasoning</b>（端点命中 2 发）。2.0.5 的
 * {@code onErrorResume} 收窄为只挂压缩预检链、reasoning 错误默认直通上抛——401
 * 场景收敛为<b>恰 1 发</b>（见下方断言）；压缩模型自身失败的「不压缩继续」路径
 * 另见 {@link #given_compaction_model_fails_when_threshold_hit_then_continues_without_compaction}。</p>
 */
class AgentscopeInnerRetryKnobTest {

    /** 内核重试日志面（ModelUtils 的 doBeforeRetry——AC「无 Retrying model request 日志」）。 */
    private static final String MODEL_UTILS_LOGGER =
            "io.agentscope.core.model.ModelUtils";
    /** 压缩器日志面（#561 补验：「摘要调用失败」WARN 的来路——压缩器内层自catch）。 */
    private static final String COMPACTION_COMPACTOR_LOGGER =
            "io.agentscope.harness.agent.memory.compaction.ConversationCompactor";

    /** OpenAI 错误体形态（401 认证失效——#552 动因同款：key 被供应商侧删除）。 */
    private static final byte[] UNAUTHORIZED_BODY =
            ("{\"error\":{\"message\":\"The API key doesn't exist\",\"type\":\""
                    + "authentication_error\",\"code\":401}}")
                    .getBytes(StandardCharsets.UTF_8);

    /** OpenAI 流式成功体（主模型成功端点——#557 注入测试同构形态）。 */
    private static final String SSE_SUCCESS = """
            data: {"id":"chatcmpl-ok","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"压缩失败续跑OK"},"finish_reason":null}]}

            data: {"id":"chatcmpl-ok","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":12,"completion_tokens":7,"total_tokens":19}}

            data: [DONE]

            """;

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private ListAppender<ILoggingEvent> kernelModelLog;

    @BeforeEach
    void startEndpointAndLogCapture() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> handle(exchange));
        server.start();
        // surefire 串行跑类，attach/detach 干净无串扰
        kernelModelLog = attachTo(MODEL_UTILS_LOGGER);
    }

    private static ListAppender<ILoggingEvent> attachTo(String loggerName) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(loggerName)).addAppender(appender);
        return appender;
    }

    private static void detachFrom(String loggerName, ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(loggerName)).detachAppender(appender);
    }

    @AfterEach
    void stopEndpointAndLogCapture() {
        if (server != null) {
            server.stop(0);
        }
        detachFrom(MODEL_UTILS_LOGGER, kernelModelLog);
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(401, UNAUTHORIZED_BODY.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(UNAUTHORIZED_BODY);
        }
    }

    private ModelChannel channel() {
        return new ModelChannel(
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-key", false);
    }

    /** 生产同款主装配（obtain 全链：resolve＋streamLayer＋内核旋钮），无需环境变量。 */
    private HarnessAgent mainAssemblyAgent() {
        return assemblyAgent(new AgentscopeProperties(), channel(), new AgentWorkspace.Local(null));
    }

    /**
     * 主装配参数化（401 用例缺省形／#561 补验形共用）：主模型通道与压缩档位参数化，
     * 压缩/预检档位恒指 401 端点（两个用例的压缩模型都「不可用」——401 用例钉主模型
     * 错误直通、补验用例钉压缩失败续跑）。
     */
    private HarnessAgent assemblyAgent(AgentscopeProperties properties, ModelChannel mainChannel,
            AgentWorkspace agentWorkspace) {
        VolcengineModelRegistrar.register();
        EdgeModelSupplier edges = new EdgeModelSupplier() {
            @Override
            public EdgeModel compactionModel() {
                return new EdgeModel("volcengine:deepseek-v4-flash", channel());
            }

            @Override
            public EdgeModel precheckModel() {
                return new EdgeModel("volcengine:deepseek-v4-flash", channel());
            }

            @Override
            public EdgeModel subagentModel() {
                return null;
            }
        };
        AgentscopeHarnessAgentFactory factory = new AgentscopeHarnessAgentFactory(
                new InMemoryAgentStateStore(), (agentKey, workspace, toolSpec) -> new Toolkit(),
                (agentKey, workspace) -> List.of(), (agentKey, workspace) -> List.of(),
                (agentKey, workspace) -> List.of(), edges, properties, null);
        return factory.obtain("inner-retry-knob", "sys", "volcengine:deepseek-v4-flash",
                mainChannel, null, agentWorkspace, null, null);
    }

    /** 异常链展平（内核/外层尝试环可能各包一层——裸与裹的判定看全链）。 */
    private static List<Throwable> chainOf(Throwable error) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable t = error; t != null && !chain.contains(t); t = t.getCause()) {
            chain.add(t);
        }
        return chain;
    }

    @Test
    @Timeout(90)
    void given_401_when_main_assembly_call_then_bare_authentication_error_without_any_retry() {
        HarnessAgent agent = mainAssemblyAgent();
        try {
            assertThatThrownBy(() -> agent
                    .call(List.of(Msg.builder().role(MsgRole.USER)
                            .content(TextBlock.builder().text("你好").build()).build()))
                    .block(Duration.ofSeconds(60)))
                    .satisfies(error -> {
                        List<Throwable> chain = chainOf(error);
                        // 裸 AuthenticationException：真错误在链上可见（内核按 401 映射
                        // 的具体子类）
                        assertThat(chain).anySatisfy(t -> assertThat(t)
                                .isInstanceOf(AuthenticationException.class));
                        // 不被 RetryExhaustedException 裹走（内核重试面＝1：零重试即无
                        // 耗尽皮；reactor 的该类是 Exceptions 包私有内部类，按类名断言）
                        assertThat(chain.stream().map(t -> t.getClass().getName()))
                                .noneMatch(name -> name.contains("RetryExhaustedException"));
                        // 错误文案带 401 与认证错误语义（错误现场可读——#221 外层
                        // 尝试环要真错误不要重试机制的皮；响应体 authentication_error
                        // 串在 message 里）
                        assertThat(chain.stream().map(Throwable::getMessage)
                                .filter(Objects::nonNull).toList())
                                .anySatisfy(message -> assertThat(message)
                                        .contains("401")
                                        .contains("authentication_error"));
                    });
            // 恰一发（#553 升级 2.0.5 后从 2 收敛为 1——#561 修复实证）：内核 callOptions
            // 不再把 maxAttempts=3 烧过平台关闭（旋钮语义＝每轮 reasoning 单发），且
            // 压缩中间件不再吞主模型错误重放（onErrorResume 收窄为只挂压缩预检链）。
            // 无任何重试层参与：内核 retryWhen 零重试（下方日志断言）＋平台
            // RetryingModel 分类拒 401。
            assertThat(requests.get()).isEqualTo(1);
            // 无内核重试日志（doBeforeRetry 只在真重试时打）
            assertThat(kernelModelLog.list.stream()
                    .map(ILoggingEvent::getFormattedMessage))
                    .noneMatch(message -> message.contains("Retrying model request"));
        }
        finally {
            agent.close();
        }
    }

    /**
     * #561 验收补验（#553 升级 2.0.5）：压缩模型自身失败仍「不压缩继续」——2.0.5 的
     * 实形是<b>压缩器内层自catch</b>：{@code ConversationCompactor.summarizePrefix} 的
     * {@code onErrorResume} 把摘要调用失败（本用例 401）降为 WARN「Summarization LLM
     * call failed」＋空 {@code Optional} → 轻量结果（不压缩、对话原样），本轮 reasoning
     * 照常继续（主模型成功、正常收口）；中间件层 {@code CompactionMiddleware} 的
     * {@code onErrorResume}（2.0.3 收窄）只护从压缩器逃逸的错误。2.0.1 形态下同一场景
     * 重放整轮 reasoning（主模型被重调）——本断言组钉住升级后的分界：<b>压缩失败只
     * 损失压缩、不损失本轮对话</b>。
     *
     * <p>触发设计：压缩阈值 triggerTokens=1（任何对话都过阈）＋超长历史消息（估测
     * ~16800 tokens ＞ 动态 keepTokens 上限 8000——deepseek 窗口 1M 的 25% 截到
     * {@code keepTokensMax}）＋短末尾消息（cutoff 安全点要求留下「当前要回答的消息」
     * ——单条超长会被判 safe cutoff 0 走不到摘要）→ cutoff=1 走真摘要路径（压缩模型
     * 被调）；主模型走独立成功端点（SSE 流式收口），与压缩 401 端点分立，两计数器
     * 互证来路。</p>
     */
    @Test
    @Timeout(120)
    void given_compaction_model_fails_when_threshold_hit_then_continues_without_compaction(
            @TempDir Path tempDir)
            throws Exception {
        // 显式工作区根（守卫断言要扫落盘面；主用例 Local(null) 无落点可扫）
        AgentWorkspace workspace = new AgentWorkspace.Local(tempDir);
        HttpServer successServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger mainRequests = new AtomicInteger();
        successServer.setExecutor(Executors.newCachedThreadPool());
        successServer.createContext("/", exchange -> {
            mainRequests.incrementAndGet();
            byte[] body = SSE_SUCCESS.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
                out.flush();
            }
        });
        successServer.start();
        ListAppender<ILoggingEvent> compactorLog = attachTo(COMPACTION_COMPACTOR_LOGGER);
        try {
            AgentscopeProperties properties = new AgentscopeProperties();
            properties.setCompactionTriggerTokens(1);
            ModelChannel mainChannel = new ModelChannel(
                    "http://127.0.0.1:" + successServer.getAddress().getPort(), "test-key",
                    false);
            HarnessAgent agent = assemblyAgent(properties, mainChannel, workspace);
            try {
                // 两条消息：超长历史消息（7 字 × 6000 = 42000 字符 → 估测 16800 tokens，
                // 越过 keepTokens 8000 → cutoff=1 走真摘要路径）＋短末尾消息（cutoff
                // 安全点要求留下「当前要回答的消息」——单条超长会被判 safe cutoff 0）
                String big = "压缩触发语料段".repeat(6000);
                Msg reply = agent
                        .call(List.of(
                                Msg.builder().role(MsgRole.USER)
                                        .content(TextBlock.builder().text(big).build()).build(),
                                Msg.builder().role(MsgRole.USER)
                                        .content(TextBlock.builder().text("总结一下").build())
                                        .build()))
                        .block(Duration.ofSeconds(90));
                // 不压缩继续：本轮对话正常收口
                assertThat(reply).isNotNull();
                // 压缩模型确实被调且失败（401 端点——flush 记忆抽取与摘要都走压缩模型）
                assertThat(requests.get()).isGreaterThanOrEqualTo(1);
                // 2.0.5 压缩器内层自catch实证：恰一次「摘要调用失败」WARN（来路＝压缩
                // 模型 401，非主模型——两端点分立互证）
                assertThat(compactorLog.list.stream()
                        .map(ILoggingEvent::getFormattedMessage)
                        .filter(message -> message.contains("Summarization LLM call failed")))
                        .hasSize(1);
                // 主模型恰一次（2.0.1 形态会重放整轮 reasoning 即重调主模型）
                assertThat(mainRequests.get()).isEqualTo(1);
                // disableTranscript 症状面守护（#553，镜像 #379 守卫先例）：工作区根下
                // 零 jsonl 落盘——若装配丢失 disableTranscript，2.0.5 恒开的
                // TranscriptMiddleware 每轮收尾写 agents/<id>/sessions/*.jsonl 即红
                try (var jsonlFiles = java.nio.file.Files.walk(tempDir)
                        .filter(path -> path.toString().endsWith(".jsonl"))) {
                    assertThat(jsonlFiles.toList()).isEmpty();
                }
            }
            finally {
                agent.close();
            }
        }
        finally {
            detachFrom(COMPACTION_COMPACTOR_LOGGER, compactorLog);
            successServer.stop(0);
        }
    }

    /**
     * 内核旋钮读面（两处装配点接线守护）：{@code ReActAgent.assembleModelConfig} 的
     * {@code modelConfig.maxRetries} 经 {@code buildGenerateOptions} 烧进每次调用的
     * callOptions——子智能体工厂（subagentFactory）与主装配（buildAgent）是平台仅有的
     * 两个构建点，旋钮必须在两处都接线（#555 修法：内核缺省 3）。读公开链
     * {@code getDelegate().getModelConfig().maxRetries()}（行为级 401 断言见主装配
     * 用例；子级委派驱动成本过高——WaitBarrierSchemaTest 缝 D 同款权衡，但本用例
     * 读公开面不用反射）。
     */
    @Test
    @Timeout(60)
    void given_assembly_points_when_built_then_kernel_model_config_max_retries_one(
            @TempDir Path tempDir) {
        // 子智能体工厂构建点（mock 模型——本用例只读装配旋钮，不发请求）
        SubagentDeclaration declaration = SubagentDeclaration.builder()
                .name("verify")
                .description("probe")
                .inlineAgentsBody("probe body")
                .build();
        HarnessAgent child = (HarnessAgent) AgentscopeHarnessAgentFactory.subagentFactory(
                declaration, new AgentWorkspace.Local(tempDir), null,
                Mockito.mock(Model.class), Mockito.mock(Model.class),
                new Toolkit(), (agentKey, workspace, toolSpec) -> new Toolkit(),
                new InMemoryAgentStateStore(), (agentKey, workspace) -> List.of(), List.of())
                .apply("verify");
        try {
            assertThat(kernelMaxRetries(child)).as("子智能体工厂旋钮接线").isEqualTo(1);
        }
        finally {
            child.close();
        }

        // 主装配构建点（工厂 obtain 真构建——同主用例装配面）
        HarnessAgent main = mainAssemblyAgent();
        try {
            assertThat(kernelMaxRetries(main)).as("主装配旋钮接线").isEqualTo(1);
        }
        finally {
            main.close();
        }
    }

    /** 读 {@code ReActAgent.modelConfig.maxRetries}（公开链）。 */
    private static int kernelMaxRetries(HarnessAgent agent) {
        return agent.getDelegate().getModelConfig().maxRetries();
    }
}
