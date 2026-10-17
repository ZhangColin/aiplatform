package com.aieducenter.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.aieducenter.aiplatform.base.metering.domain.model.UsageEvent;
import com.aieducenter.aiplatform.base.metering.domain.port.UsageEventSink;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * 计量对账（#324 token 计量对账级修复）：真实 HarnessAgent（ReAct 循环、工具轮、
 * 压缩链全真）＋脚本化模型（{@link MeteredModel} 包装的最底层替身，流从
 * boundedElastic 发信号＝真实 HTTP 异步边界的形态）——converse 整链上<b>每次</b>
 * 模型调用都进当轮计量，逐条对账（usage 事件条数＝模型流数、seq 逐调用递增、
 * model 档位各自归位）。与 {@link AgentscopeAgentClientTest} 的脚本事件流形态
 * （stub agent 同线程同步吐事件）互补：本类钉「真实订阅链装配线程不定」的计量
 * 传递形态（ThreadLocal 装配态读取在该形态下静默漏计——只计首个模型流的现场
 * 取证即此形态，绿测＝能跑教训的防回归面）。
 */
@ExtendWith(MockitoExtension.class)
class AgentscopeMeteringReconcileTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T08:00:00Z"), ZoneOffset.UTC);

    @Mock
    private AgentscopeHarnessAgentFactory factory;

    @Mock
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    @Mock
    private UsageEventSink sink;

    /** 会话状态内存化（真实 HarnessAgent 的 save/恢复往返，槽位 (userId, sessionId, key)）。 */
    private final Map<String, State> stateSlots = new java.util.concurrent.ConcurrentHashMap<>();

    @Captor
    private ArgumentCaptor<UsageEvent> usageCaptor;

    @TempDir
    Path workspaceDir;

    private AgentscopeProperties properties;

    private AgentscopeAgentClient client;

    @BeforeEach
    void setUp() {
        stateSlots.clear();
        properties = new AgentscopeProperties();
        properties.setAgentName("platform-agent");
        properties.setDefaultModel("deepseek:deepseek-v4-flash");
        properties.setDefaultSystemPrompt("你是平台智能体。");
        properties.setTimeout(Duration.ofSeconds(60));
        client = new AgentscopeAgentClient(factory, properties, workspaceLifecycleAppService,
                inMemoryStateStore(), sink, new LandedFileFacts(), CLOCK);
    }

    /**
     * 验收锚点（#324 对账级判据·灵魂例）：多迭代 run（ReAct 第 1 迭代调工具 →
     * 第 2 迭代收口）的两个模型流全部计量——第 2 流的装配发生在第 1 流信号线程
     * （boundedElastic，非 converse 线程），ThreadLocal 传递形态在此漏计（修复前
     * 只计首流），订阅链传递形态两流皆计。
     */
    @Test
    void given_react_run_with_tool_iteration_when_converse_then_every_model_stream_metered() {
        ScriptedModel model = new ScriptedModel(List.of(
                List.of(toolCallResponse("echo_note", "t-1", new ChatUsage(100, 40, 0, 0.1))),
                List.of(textResponse("做完了", new ChatUsage(60, 30, 0, 0.05)))));
        mountAgent(agentBuilder(model).build());

        client.converse(command("run-1", new UsageContext("prj-1", Map.of("agentKind", "coder"))),
                event -> {
                });

        List<UsageEvent> events = reportedEvents();
        assertThat(events).hasSize(2);
        assertThat(events.get(0).eventId()).isEqualTo("agent-usage-run-1-1");
        assertThat(events.get(0).tokens().input()).isEqualTo(100);
        assertThat(events.get(0).tokens().output()).isEqualTo(40);
        assertThat(events.get(1).eventId()).isEqualTo("agent-usage-run-1-2");
        assertThat(events.get(1).tokens().input()).isEqualTo(60);
        assertThat(events.get(1).tokens().output()).isEqualTo(30);
        assertThat(events).allMatch(e -> "prj-1".equals(e.subject()));
        assertThat(events).allMatch(e -> "run-1".equals(e.runId()));
    }

    /**
     * 验收锚点（#324 失败流段照报）：第 2 模型流中途失败——run 整体转失败上抛，
     * 但两流的已耗 token 都已入账（失败 attempt 不弃 token 账，含已耗照报口径）。
     */
    @Test
    void given_second_stream_fails_when_converse_then_consumed_tokens_of_all_streams_reported() {
        ScriptedModel model = new ScriptedModel(List.of(
                List.of(toolCallResponse("echo_note", "t-1", new ChatUsage(100, 40, 0, 0.1))),
                List.of(textResponse("写到一半", new ChatUsage(60, 30, 0, 0.05)))));
        model.failFromCall(1);
        mountAgent(agentBuilder(model).build());

        assertThatThrownBy(() -> client.converse(
                command("run-2", new UsageContext("prj-1", Map.of())), event -> {
                })).isInstanceOf(IllegalStateException.class);

        // 两流的已耗 token 都已入账（幂等键逐流唯一——多流并发收口时序不定，
        // 序号按收口顺序分配，对账只核全量条数与用量，不钉序号与流序的配对）
        List<UsageEvent> events = reportedEvents();
        assertThat(events).hasSize(2);
        assertThat(events).extracting(UsageEvent::eventId)
                .containsExactlyInAnyOrder("agent-usage-run-2-1", "agent-usage-run-2-2");
        assertThat(events).extracting(e -> e.tokens().input() + "/" + e.tokens().output())
                .containsExactlyInAnyOrder("100/40", "60/30");
    }

    /**
     * 验收锚点（#324 压缩链跨线程）：工具轮后压缩触发——压缩摘要模型流（框架
     * onReasoning 中间件内直调，装配线程＝第 1 流信号线程）与主循环后续流都计量，
     * 且压缩模型与主模型档位各自归位（不并桶）。
     */
    @Test
    void given_compaction_triggered_mid_run_when_converse_then_compaction_stream_metered() {
        ScriptedModel mainModel = new ScriptedModel(List.of(
                List.of(toolCallResponse("echo_note", "t-1", new ChatUsage(100, 40, 0, 0.1))),
                List.of(textResponse("做完了", new ChatUsage(60, 30, 0, 0.05)))));
        ScriptedModel compactionModel = new ScriptedModel(List.of(
                List.of(textResponse("此前对话摘要", new ChatUsage(50, 20, 0, 0.02)))));
        // 压缩触发＝消息数阈值（第 2 迭代推理前 3 条消息即触发）、尾保留 token 预算
        // 收紧（全量消息装不下 → cutoff>0 真压）、压缩前记忆抽取关（聚焦压缩摘要
        // 单流——flush 直调计量同机制已由 MeteredModelTest 覆盖）；压缩链在框架
        // onReasoning 中间件内直调压缩模型（MeteredModel 包装，平台工厂
        // compactionConfig 同形装配）
        mountAgent(agentBuilder(mainModel)
                .compaction(io.agentscope.harness.agent.memory.compaction.CompactionConfig
                        .builder()
                        .triggerMessages(3)
                        .keepTokens(1)
                        .flushBeforeCompact(false)
                        .model(MeteredModel.wrap(compactionModel, "deepseek:deepseek-v4-flash"))
                        .build())
                .build());

        client.converse(command("run-3", new UsageContext("prj-1", Map.of()), "s-run-3"),
                event -> {
                });

        List<UsageEvent> events = reportedEvents();
        assertThat(events).hasSize(3);
        assertThat(events).extracting(UsageEvent::model)
                .containsExactly("deepseek-chat", "deepseek-v4-flash", "deepseek-chat");
        assertThat(events.get(0).tokens().input()).isEqualTo(100);
        // 压缩流：flash 档独立归属（与主循环 pro 档不并桶）
        assertThat(events.get(1).tokens().input()).isEqualTo(50);
        assertThat(events.get(1).tokens().output()).isEqualTo(20);
        assertThat(events.get(2).tokens().input()).isEqualTo(60);
        assertThat(events).allMatch(e -> "run-3".equals(e.runId()));
        assertThat(events).allMatch(e -> "prj-1".equals(e.subject()));
    }

    /** 事件逐条捕获（对账面——次数断言在各测试自身，此处只收全量清单）。 */
    private List<UsageEvent> reportedEvents() {
        verify(sink, atLeast(0)).report(usageCaptor.capture());
        return new ArrayList<>(usageCaptor.getAllValues());
    }

    private AgentCommand command(String runId, UsageContext usage) {
        return command(runId, usage, "s-" + runId);
    }

    /** 每例独立会话（会话状态不跨例累积——累积会顶到框架缺省压缩阈值、压缩链插进主推理）。 */
    private AgentCommand command(String runId, UsageContext usage, String sessionId) {
        return new AgentCommand(runId, "干活", null, "deepseek:deepseek-v4-flash", sessionId,
                "alice", usage, null, Map.of());
    }

    /** 真实 HarnessAgent 挂进工厂 stub（模型＝MeteredModel 包装的脚本模型）。 */
    private void mountAgent(HarnessAgent agent) {
        when(factory.obtain(any(), any(), any(), any(), any(), any())).thenReturn(agent);
    }

    private HarnessAgent.Builder agentBuilder(Model model) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new EchoNoteTool());
        return HarnessAgent.builder()
                .name("platform-agent")
                .sysPrompt("你是平台智能体。")
                .model(MeteredModel.wrap(model, "deepseek:deepseek-chat"))
                .stateStore(inMemoryStateStore())
                .toolkit(toolkit)
                .workspace(workspaceDir)
                // 记忆钩子/工具与平台装配同形关闭（记忆不进计量面，来源已关）；
                // 压缩默认关（多迭代/失败流两例不夹压缩链——压缩链计量另设专项例）
                .disableMemoryHooks()
                .disableMemoryTools()
                .disableDefaultWorkspaceSkills()
                .disableCompaction();
    }

    private AgentStateStore inMemoryStateStore() {
        return new AgentStateStore() {
            @Override
            public void save(String userId, String sessionId, String key, State value) {
                stateSlots.put(userId + "|" + sessionId + "|" + key, value);
            }

            @Override
            public void save(String userId, String sessionId, String key,
                    List<? extends State> values) {
                stateSlots.put(userId + "|" + sessionId + "|" + key,
                        values.isEmpty() ? null : values.get(0));
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T extends State> Optional<T> get(String userId, String sessionId,
                    String key, Class<T> type) {
                State state = stateSlots.get(userId + "|" + sessionId + "|" + key);
                return state == null ? Optional.empty() : Optional.of((T) state);
            }

            @Override
            public <T extends State> List<T> getList(String userId, String sessionId,
                    String key, Class<T> type) {
                return List.of();
            }

            @Override
            public void delete(String userId, String sessionId) {
            }

            @Override
            public boolean exists(String userId, String sessionId) {
                return false;
            }

            @Override
            public java.util.Set<String> listSessionIds(String userId) {
                return java.util.Set.of();
            }
        };
    }

    /** 最简工具（注册名 echo_note——脚本模型的 tool_use 指名调用，收口即回执）。 */
    static final class EchoNoteTool {

        @Tool(name = "echo_note", description = "回执一条笔记")
        public String run(@ToolParam(name = "text") String text) {
            return "ok: " + text;
        }
    }

    /**
     * 脚本化模型：第 n 次调用吐脚本第 n 组响应（组装 ChatResponse 流），流从
     * boundedElastic 发信号——复刻真实 HTTP 模型的异步边界（订阅/信号线程 ≠
     * converse 调用线程，后续 ReAct 迭代的模型装配即落在该信号线程上）；
     * {@code failFromCall(k)} 让第 k 次起的调用在响应之后转错误（失败流段形态）。
     */
    static final class ScriptedModel implements Model {

        private final List<List<ChatResponse>> script;
        private final AtomicInteger calls = new AtomicInteger();
        private volatile int failFrom = Integer.MAX_VALUE;

        ScriptedModel(List<List<ChatResponse>> script) {
            this.script = script;
        }

        void failFromCall(int callNo) {
            this.failFrom = callNo;
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                GenerateOptions options) {
            int callNo = calls.getAndIncrement();
            List<ChatResponse> responses = script.get(Math.min(callNo, script.size() - 1));
            Flux<ChatResponse> flow = Flux.fromIterable(responses);
            if (callNo >= failFrom) {
                flow = Flux.concat(flow, Flux.error(new RuntimeException("mid-stream boom")));
            }
            return flow.subscribeOn(Schedulers.boundedElastic());
        }

        @Override
        public String getModelName() {
            return "scripted";
        }

        @Override
        public boolean supportsNativeStructuredOutput() {
            return false;
        }

        @Override
        public boolean supportsNativeStructuredOutputWithTools() {
            return false;
        }

        @Override
        public int getContextWindowSize() {
            return 128_000;
        }
    }

    private static ChatResponse textResponse(String text, ChatUsage usage) {
        return ChatResponse.builder()
                .id("resp-" + text.hashCode())
                .content(List.of(TextBlock.builder().text(text).build()))
                .usage(usage)
                .build();
    }

    private static ChatResponse toolCallResponse(String toolName, String callId, ChatUsage usage) {
        return ChatResponse.builder()
                .id("resp-" + callId)
                .content(List.of(ToolUseBlock.builder()
                        .name(toolName).id(callId)
                        .input(Map.of("text", "一条")).build()))
                .usage(usage)
                .build();
    }
}
