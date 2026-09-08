package com.aieducenter.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.metering.domain.port.UsageEventSink;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.harness.agent.HarnessAgent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

/**
 * {@link AgentscopeAgentClient}：事件序（run-start → 过程事件 →
 * run-finish / error）、文本增量汇聚、RuntimeContext 组装、计量上下文挂/摘
 * （#109 模型边界计量，用量由 {@link MeteredModel} 直报）、workspaceId → 项目
 * dev 工作区、挂起语义与 resume。
 */
@ExtendWith(MockitoExtension.class)
class AgentscopeAgentClientTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-25T10:00:00Z"), ZoneOffset.UTC);

    @Mock
    private AgentscopeHarnessAgentFactory factory;

    @Mock
    private HarnessAgent agent;

    @Mock
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    @Mock
    private UsageEventSink usageEventSink;

    @Mock
    private io.agentscope.core.state.AgentStateStore stateStore;

    @Captor
    private ArgumentCaptor<RuntimeContext> contextCaptor;

    private AgentscopeProperties properties;

    private AgentscopeAgentClient client;

    @BeforeEach
    void setUp() {
        properties = new AgentscopeProperties();
        properties.setAgentName("platform-agent");
        properties.setDefaultModel("deepseek:deepseek-v4-flash");
        properties.setDefaultSystemPrompt("你是平台智能体。");
        properties.setTimeout(Duration.ofSeconds(30));
        client = new AgentscopeAgentClient(factory, properties, workspaceLifecycleAppService,
                stateStore, usageEventSink, CLOCK);
    }

    private AgentCommand command(String modelString, UsageContext usage) {
        return new AgentCommand("run-1", "你好", null, modelString, "s-1", "alice",
                usage, null, Map.of());
    }

    private void givenStream(io.agentscope.core.event.AgentEvent... events) {
        when(factory.obtain(any(), any(), any(), any(), any())).thenReturn(agent);
        when(agent.streamEvents(any(List.class), any(RuntimeContext.class)))
                .thenReturn(Flux.fromIterable(List.of(events)));
    }

    /** 确定性时间戳（#111 耗时事实断言用）：引擎事件 createdAt 注入形。 */
    private static String ts(long epochMilli) {
        return Instant.ofEpochMilli(epochMilli).toString();
    }

    @Test
    void given_streaming_text_deltas_when_converse_then_lifecycle_and_text_frames_in_order() {
        givenStream(
                new TextBlockDeltaEvent("r-1", "b-1", "你"),
                new TextBlockDeltaEvent("r-1", "b-1", "好"),
                new TextBlockDeltaEvent("r-1", "b-1", "呀"));

        List<AgentEvent> frames = new ArrayList<>();
        var reply = client.converse(command(null, null), frames::add);

        // 透传 text 事件照发，解说尾段另成 part-text 部件（收口事件前）
        assertThat(frames.stream().map(AgentEvent::type)).containsExactly(
                AgentEventTypes.RUN_START,
                "text", "text", "text", AgentEventTypes.PART_TEXT, AgentEventTypes.RUN_FINISH);
        // 开场事件形状
        assertThat(frames.get(0).payload()).containsOnly(
                Map.entry("runId", "run-1"), Map.entry("prompt", "你好"),
                Map.entry("model", "deepseek:deepseek-v4-flash"), Map.entry("engine", "agentscope"));
        // 文本增量事件：runId 锚定 + delta 在 data 内层
        assertThat(frames.get(1).payload()).containsAllEntriesOf(Map.of(
                "runId", "run-1", "sessionId", "s-1", "engine", "agentscope"));
        assertThat(frames.get(1).payload().get("data"))
                .isEqualTo(Map.of("delta", "你", "blockId", "b-1"));
        // 解说部件 = 完整段（句读/收尾切段，非增量）、扁平载荷无 data 键
        assertThat(frames.get(4).payload()).containsOnly(
                Map.entry("runId", "run-1"), Map.entry("sessionId", "s-1"),
                Map.entry("engine", "agentscope"), Map.entry("text", "你好呀"));
        // 收口事件
        assertThat(frames.get(5).payload()).containsEntry("finish", "end");
        // 回复文本 = 增量拼接
        assertThat(reply.runId()).isEqualTo("run-1");
        assertThat(reply.text()).isEqualTo("你好呀");
    }

    @Test
    void given_any_command_when_converse_then_retired_families_absent_and_parts_always_on() {
        // 收缩验收（#82）：退役五族零残留——任何命令的事件流无 live-* / role-assigned /
        // run-created / run-retrying / fix-unchanged / dispatch-stage；部件恒挂
        // （消息部件是全部智能体事件的呈现地基——主智能体对话轮也产部件）
        givenStream(
                new ModelCallStartEvent("r-1"),
                new TextBlockDeltaEvent("r-1", "b-1", "访谈自述。"),
                new ToolCallEndEvent("r-1", "tc-1", "command"));

        List<AgentEvent> frames = new ArrayList<>();
        client.converse(command(null, null), frames::add);

        assertThat(frames.stream().map(AgentEvent::type)).noneMatch(type ->
                type.startsWith("live-") || type.equals("role-assigned")
                        || type.equals("run-created") || type.equals("run-retrying")
                        || type.equals("fix-unchanged") || type.equals("dispatch-stage"));
        assertThat(frames.stream().map(AgentEvent::type))
                .contains(AgentEventTypes.PART_TEXT, AgentEventTypes.PART_ACTION);
    }

    @Test
    void given_exceed_max_iters_when_converse_then_run_finish_carries_engine_finish_token() {
        givenStream(new ExceedMaxItersEvent("r-1", 10, 10));

        List<AgentEvent> frames = new ArrayList<>();
        client.converse(command(null, null), frames::add);

        AgentEvent finish = frames.get(frames.size() - 1);
        assertThat(finish.type()).isEqualTo(AgentEventTypes.RUN_FINISH);
        assertThat(finish.payload()).containsEntry("finish", "exceed_max_iters");
    }

    @Test
    void given_converse_when_call_agent_then_runtime_context_carries_session_and_user() {
        givenStream(new TextBlockDeltaEvent("r-1", "b-1", "嗯"));

        client.converse(command(null, null), event -> {
        });

        verify(agent).streamEvents(any(List.class), contextCaptor.capture());
        assertThat(contextCaptor.getValue().getSessionId()).isEqualTo("s-1");
        assertThat(contextCaptor.getValue().getUserId()).isEqualTo("alice");
    }

    @Test
    void given_workspace_id_when_converse_then_project_dev_workspace_resolved() {
        when(workspaceLifecycleAppService.handleOf("42")).thenReturn(WorkspaceHandle.dev(
                WorkspaceId.of("42"), "ws-42-dev", "net-42", 0));
        givenStream(new TextBlockDeltaEvent("r-1", "b-1", "写"));

        client.converse(new AgentCommand("run-9", "写 PRD", null, null, "s-9", "alice",
                null, "42", Map.of()), event -> {
                });

        verify(factory).obtain(eq("platform-agent"), any(), eq("deepseek:deepseek-v4-flash"),
                eq(new AgentWorkspace.ProjectDev("42", "ws-42-dev")), any());
    }

    @Test
    void given_read_only_flag_when_converse_then_project_read_only_workspace_resolved() {
        // #86 主智能体对话姿态：workspaceReadOnly 开 → 同一容器解析为只读面（工厂据此
        // 关内核文件/shell 工具——写面结构性关闭）
        when(workspaceLifecycleAppService.handleOf("42")).thenReturn(WorkspaceHandle.dev(
                WorkspaceId.of("42"), "ws-42-dev", "net-42", 0));
        givenStream(new TextBlockDeltaEvent("r-1", "b-1", "答"));

        client.converse(new AgentCommand("run-1", "咨询", null, null, "s-1", "alice",
                null, "42", Map.of(), null, "ASSISTANT", true, null), event -> {
                });

        verify(factory).obtain(any(), any(), any(),
                eq(new AgentWorkspace.ProjectReadOnly("42", "ws-42-dev")), eq("ASSISTANT"));
    }

    @Test
    void given_no_workspace_id_when_converse_then_local_workspace_fallback() {
        givenStream(new TextBlockDeltaEvent("r-1", "b-1", "本地"));

        client.converse(command(null, null), event -> {
        });

        verify(factory).obtain(any(), any(), any(),
                eq(new AgentWorkspace.Local(properties.getWorkspace())), any());
        verifyNoInteractions(workspaceLifecycleAppService);
    }

    @Test
    void given_agent_role_when_converse_then_role_threaded_into_agent_build() {
        // #43 工具面按配置发放：命令的配置键穿透到 agent 工厂构建——主智能体与执行
        // 智能体同一工作区、不同角色 → 不同工具面的寻址腿
        when(workspaceLifecycleAppService.handleOf("42")).thenReturn(WorkspaceHandle.dev(
                WorkspaceId.of("42"), "ws-42-dev", "net-42", 0));
        givenStream(new TextBlockDeltaEvent("r-1", "b-1", "好"));

        client.converse(new AgentCommand("run-1", "梳理需求", null, null, "s-1", "alice",
                null, "42", Map.of(), null, "main", false, null), event -> {
                });

        verify(factory).obtain(any(), any(), any(),
                eq(new AgentWorkspace.ProjectDev("42", "ws-42-dev")), eq("main"));
    }

    @Test
    void given_usage_context_when_converse_then_metering_scope_entered_and_cleared() {
        // #109 模型边界计量：本轮计量上下文（ThreadLocal）在 streamEvents 期间挂起、
        // 收口摘除——主循环/压缩/记忆抽取共用同一模型实例据此归入本轮
        AtomicReference<MeteringScope> duringStream = new AtomicReference<>();
        when(factory.obtain(any(), any(), any(), any(), any())).thenReturn(agent);
        when(agent.streamEvents(any(List.class), any(RuntimeContext.class))).thenAnswer(inv -> {
            duringStream.set(MeteringScope.current());
            return Flux.just(new TextBlockDeltaEvent("r-1", "b-1", "答"));
        });

        client.converse(command("deepseek:deepseek-chat",
                        new UsageContext("prj-1", Map.of("agentKind", "ba"))),
                event -> {
                });

        assertThat(duringStream.get()).isNotNull();
        assertThat(MeteringScope.current()).isNull();
    }

    @Test
    void given_no_usage_context_when_converse_then_no_metering_scope() {
        // 无 usageContext 不挂计量上下文（底座不发明归属——不报用量）
        AtomicReference<MeteringScope> duringStream = new AtomicReference<>();
        when(factory.obtain(any(), any(), any(), any(), any())).thenReturn(agent);
        when(agent.streamEvents(any(List.class), any(RuntimeContext.class))).thenAnswer(inv -> {
            duringStream.set(MeteringScope.current());
            return Flux.just(new TextBlockDeltaEvent("r-1", "b-1", "答"));
        });

        client.converse(command(null, null), event -> {
        });

        assertThat(duringStream.get()).isNull();
    }

    @Test
    void given_model_call_end_when_converse_then_no_metering_from_events() {
        // 旧单一来源退役（#109）：ModelCallEndEvent 不再触发用量上报——改为模型边界
        // 计量（MeteredModel 每次 stream() 收口直报）
        givenStream(new ModelCallEndEvent("r-1", new ChatUsage(100, 40, 20, 0.5)));

        client.converse(command("deepseek:deepseek-chat",
                        new UsageContext("prj-1", Map.of())),
                event -> {
                });

        verifyNoInteractions(usageEventSink);
    }

    // ---------- 消息部件（parts 契约） ----------

    /**
     * 验收锚点（#77）：脚本化智能体会话——薄缝（agent 工厂 + 事件流）按剧本吐
     * 原生事件，converse 产出的事件流断言部件序列：动作卡开始/进行中/完成全生命
     * 周期（动作一开始即出部件，非调用落定才出）、同一动作 toolCallId 锚定跨状态、
     * label 自 running 起为具体对象。
     */
    @Test
    void given_scripted_coding_run_when_converse_then_part_events_full_lifecycle() {
        givenStream(
                new ModelCallStartEvent("reply-1"),
                new TextBlockDeltaEvent("reply-1", "b-1", "正在编写订单管理页面。"),
                new ToolCallStartEvent("reply-1", "tc-1", "write_file"),
                new ToolCallDeltaEvent("reply-1", "tc-1", "write_file",
                        "{\"path\":\"src/pages/订单管理.tsx\"}"),
                new ToolCallEndEvent("reply-1", "tc-1", "write_file"),
                new ToolResultEndEvent("reply-1", "tc-1", "write_file", ToolResultState.SUCCESS),
                new TextBlockDeltaEvent("reply-1", "b-2", "订单管理完成"));

        List<AgentEvent> frames = new ArrayList<>();
        client.converse(command(null, null), frames::add);

        // 部件序列（步骤分组已退役 #115：ModelCallStart 不产 part-step）：
        // 解说段 → 动作 started（参数在途，通用对象）→ 动作 running（参数落定，
        // 具体对象）→ 动作 completed → 解说尾段 → 收口
        assertThat(frames.stream().map(AgentEvent::type)).containsSubsequence(
                AgentEventTypes.PART_TEXT,
                AgentEventTypes.PART_ACTION,
                AgentEventTypes.PART_ACTION,
                AgentEventTypes.PART_ACTION,
                AgentEventTypes.PART_TEXT,
                AgentEventTypes.RUN_FINISH);

        List<AgentEvent> actions = frames.stream()
                .filter(f -> f.type().equals(AgentEventTypes.PART_ACTION)).toList();
        assertThat(actions).hasSize(3);
        // 同一动作跨状态同锚（toolCallId）
        assertThat(actions).allSatisfy(action -> {
            assertThat(action.payload()).containsEntry(
                    AgentEventTypes.PART_ACTION_TOOL_CALL_FIELD, "tc-1");
            assertThat(action.payload()).containsEntry(
                    AgentEventTypes.PART_ACTION_TOOL_NAME_FIELD, "write_file");
        });
        assertThat(actions.stream().map(a -> a.payload()
                .get(AgentEventTypes.PART_ACTION_STATE_FIELD)))
                .containsExactly("started", "running", "completed");
        // label：started 通用对象（参数在途）→ running 起具体对象（无时态——时态由 state）
        assertThat(actions.get(0).payload()).containsEntry(
                AgentEventTypes.PART_ACTION_LABEL_FIELD, "编写【代码文件】");
        assertThat(actions.get(1).payload()).containsEntry(
                AgentEventTypes.PART_ACTION_LABEL_FIELD, "编写【订单管理】");
        assertThat(actions.get(2).payload()).containsEntry(
                AgentEventTypes.PART_ACTION_LABEL_FIELD, "编写【订单管理】");
    }

    /** 动作失败态：工具结果 error → part-action state=failed（动作层状态，非 run 终态）。 */
    @Test
    void given_tool_error_result_when_converse_then_part_action_failed_state() {
        givenStream(
                new ToolCallStartEvent("reply-1", "tc-1", "command"),
                new ToolCallEndEvent("reply-1", "tc-1", "command"),
                new ToolResultEndEvent("reply-1", "tc-1", "command", ToolResultState.ERROR));

        List<AgentEvent> frames = new ArrayList<>();
        client.converse(command(null, null), frames::add);

        List<AgentEvent> actions = frames.stream()
                .filter(f -> f.type().equals(AgentEventTypes.PART_ACTION)).toList();
        assertThat(actions.stream().map(a -> a.payload()
                .get(AgentEventTypes.PART_ACTION_STATE_FIELD)))
                .containsExactly("started", "running", "failed");
    }

    /**
     * 引擎透传与部件并存（parts 契约）：同一事件流上透传（step-start / tool）照发、
     * 部件载荷扁平（无 {@code data} 键，前端透传收窄守卫不误收部件）。
     */
    @Test
    void given_scripted_coding_run_when_converse_then_passthrough_alongside_flat_parts() {
        givenStream(
                new ModelCallStartEvent("reply-1"),
                new TextBlockDeltaEvent("reply-1", "b-1", "正在编写订单管理页面。"),
                new ToolCallStartEvent("reply-1", "tc-1", "write_file"),
                new ToolCallDeltaEvent("reply-1", "tc-1", "write_file",
                        "{\"path\":\"src/pages/订单管理.tsx\"}"),
                new ToolCallEndEvent("reply-1", "tc-1", "write_file"),
                new ToolResultEndEvent("reply-1", "tc-1", "write_file", ToolResultState.SUCCESS));

        List<AgentEvent> frames = new ArrayList<>();
        client.converse(command(null, null), frames::add);

        // 引擎透传承存（开放集合），旧直播族不出现
        assertThat(frames.stream().map(AgentEvent::type)).contains(
                "step-start", "tool");
        assertThat(frames.stream().map(AgentEvent::type))
                .noneMatch(type -> type.startsWith("live-"));
        // 部件载荷扁平：无 data 键（前端未知 type + 无 data 即忽略）
        assertThat(frames.stream()
                .filter(f -> f.type().startsWith("part-"))
                .filter(f -> f.payload().containsKey("data")))
                .isEmpty();
    }

    /**
     * 脚本化委派（#95 委派位）：子智能体转发进父流的事件带引擎 source 路径——converse
     * 经双映射表（透传 + 部件）产出来源归属（值 = 末段名）；执行体自身事件缺省不携带
     * source（用户面仍无角色标签）。同一事件流上执行体与子智能体交错，各自归位。
     */
    @Test
    void given_scripted_delegation_when_converse_then_events_carry_source_attribution() {
        givenStream(
                new TextBlockDeltaEvent("reply-1", "b-1", "执行体自述。"),
                new TextBlockDeltaEvent("reply-1", "b-2", "自测通过。")
                        .withSource("platform-agent/self-test"),
                new ToolCallEndEvent("reply-1", "tc-1", "command")
                        .withSource("platform-agent/self-test"));

        List<AgentEvent> frames = new ArrayList<>();
        client.converse(command(null, null), frames::add);

        // 透传 text：执行体段无 source、子智能体段带 source（末段名）
        List<AgentEvent> texts = frames.stream().filter(f -> "text".equals(f.type())).toList();
        assertThat(texts).hasSize(2);
        assertThat(texts.get(0).payload()).doesNotContainKey(AgentEventTypes.SOURCE_FIELD);
        assertThat(texts.get(1).payload()).containsEntry(AgentEventTypes.SOURCE_FIELD, "self-test");
        // 部件事件：part-text / part-action 带来源归属（分角色播的依据）
        List<AgentEvent> parts = frames.stream()
                .filter(f -> f.type().startsWith("part-")).toList();
        assertThat(parts.stream().filter(f -> f.type().equals(AgentEventTypes.PART_ACTION)))
                .singleElement().satisfies(f ->
                        assertThat(f.payload()).containsEntry(AgentEventTypes.SOURCE_FIELD, "self-test"));
        assertThat(parts.stream().filter(f -> f.type().equals(AgentEventTypes.PART_TEXT)))
                .anySatisfy(f -> assertThat(f.payload())
                        .containsEntry(AgentEventTypes.SOURCE_FIELD, "self-test"))
                .anySatisfy(f -> assertThat(f.payload())
                        .doesNotContainKey(AgentEventTypes.SOURCE_FIELD));
    }

    /**
     * 阶段耗时事实随回复携出（#111 收口扩载 durationBreakdown 的观察面接线）：计时源 =
     * 引擎事件 createdAt（构造注入——确定性断言）——执行体模型调用配对进 llmMs、工具执行
     * 窗按工具名分桶（command 按命令归组）、委派事件记 source 委派窗。
     */
    @Test
    void given_scripted_events_when_converse_then_reply_carries_stage_durations() {
        givenStream(
                new ModelCallStartEvent("evt-1", ts(1_000), "reply-1"),
                new ModelCallEndEvent("evt-2", ts(1_300), "reply-1", null),
                new ToolCallDeltaEvent("evt-3", ts(1_300), "reply-1", "tc-1", "command",
                        "{\"command\":\"npm install\"}"),
                new ToolCallEndEvent("evt-4", ts(1_310), "reply-1", "tc-1", "command"),
                new ToolResultEndEvent("evt-5", ts(2_000), "reply-1", "tc-1", "command",
                        ToolResultState.SUCCESS),
                new ToolCallEndEvent("evt-6", ts(2_000), "reply-1", "tc-2", "write_file"),
                new ToolResultEndEvent("evt-7", ts(2_050), "reply-1", "tc-2", "write_file",
                        ToolResultState.SUCCESS),
                new ModelCallStartEvent("evt-8", ts(2_100), "sub-1")
                        .withSource("platform-agent/self-test"),
                new ModelCallEndEvent("evt-9", ts(2_400), "sub-1", null)
                        .withSource("platform-agent/self-test"));

        AgentReply reply = client.converse(command(null, null), event -> {
        });

        assertThat(reply.durations().llmMs()).isEqualTo(300);
        assertThat(reply.durations().toolsMs())
                .containsExactly(Map.entry("write_file", 50L));
        assertThat(reply.durations().commandMs())
                .containsExactly(Map.entry("install", 690L));
        assertThat(reply.durations().subagentMs())
                .containsExactly(Map.entry("self-test", 300L));
    }

    /** run-start 并入角色键（引擎信息归一）：带角色命令携带、无角色不携带——前端工作消息/对话面的锚定判据。 */
    @Test
    void given_agent_key_when_converse_then_run_start_carries_agent_key() {
        givenStream(new TextBlockDeltaEvent("r-1", "b-1", "写"));

        List<AgentEvent> frames = new ArrayList<>();
        client.converse(new AgentCommand("run-1", "做系统", null, null, "s-1", "alice",
                null, null, Map.of(), null, "CODER", false, null), frames::add);

        assertThat(frames.get(0).type()).isEqualTo(AgentEventTypes.RUN_START);
        assertThat(frames.get(0).payload()).containsEntry("agent", "CODER");

        // 无配置语境（取名等一次性调用）：run-start 不带 agent 键
        givenStream(new TextBlockDeltaEvent("r-2", "b-1", "名"));
        List<AgentEvent> plain = new ArrayList<>();
        client.converse(command(null, null), plain::add);
        assertThat(plain.get(0).payload()).doesNotContainKey("agent");
    }

    /** #118 工作消息头部标题：命令带 heading → run-start 携 slice（title + index/total）。 */
    @Test
    void given_heading_when_converse_then_run_start_carries_slice() {
        givenStream(new TextBlockDeltaEvent("r-1", "b-1", "写"));

        List<AgentEvent> frames = new ArrayList<>();
        client.converse(new AgentCommand("run-1", "做系统", null, null, "s-1", "alice",
                null, null, Map.of(), null, "CODER", false,
                RunHeading.slice("商品浏览", 2, 5)), frames::add);

        assertThat(frames.get(0).type()).isEqualTo(AgentEventTypes.RUN_START);
        assertThat(frames.get(0).payload()).containsEntry(AgentEventTypes.SLICE_FIELD,
                Map.of("title", "商品浏览", "index", 2, "total", 5));
    }

    @Test
    void given_stream_error_when_converse_then_error_frame_then_exception() {
        when(factory.obtain(any(), any(), any(), any(), any())).thenReturn(agent);
        when(agent.streamEvents(any(List.class), any(RuntimeContext.class)))
                .thenReturn(Flux.error(new RuntimeException("boom")));

        List<AgentEvent> frames = new ArrayList<>();
        assertThatThrownBy(() -> client.converse(command(null, null), frames::add))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("智能体调用失败");

        assertThat(frames.stream().map(AgentEvent::type)).containsExactly(
                AgentEventTypes.RUN_START, AgentEventTypes.ERROR);
        assertThat(frames.get(1).payload()).containsEntry("message", "boom");
    }

    @Test
    void given_startup_failure_when_converse_then_error_frame_then_exception_reraised() {
        // 起跑失败（如缺 API key 致模型客户端构建抛 IllegalStateException）原是
        // runTurn 前的零事件区（异步轨道吞异常，用户只见死寂）——前段失败也经
        // sink 发 error 事件（runId 锚定 = command 的），异常照常上抛
        when(factory.obtain(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("DeepSeek API key 未配置"));

        List<AgentEvent> frames = new ArrayList<>();
        assertThatThrownBy(() -> client.converse(command(null, null), frames::add))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("API key");

        assertThat(frames.stream().map(AgentEvent::type))
                .containsExactly(AgentEventTypes.ERROR);
        assertThat(frames.get(0).payload()).containsEntry("runId", "run-1");
        assertThat(frames.get(0).payload())
                .containsEntry("message", "DeepSeek API key 未配置");
    }

    @Test
    void given_stream_error_when_converse_then_metering_scope_cleared() {
        // 失败轮也摘计量上下文（finally）——不残留跨轮污染；失败轮已耗 token 由
        // MeteredModel 在 stream() 收口如实计量（见 MeteredModelTest）
        when(factory.obtain(any(), any(), any(), any(), any())).thenReturn(agent);
        when(agent.streamEvents(any(List.class), any(RuntimeContext.class)))
                .thenReturn(Flux.error(new RuntimeException("mid-stream boom")));

        assertThatThrownBy(() -> client.converse(
                        command(null, new UsageContext("prj-1", Map.of())), event -> {
                        }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(MeteringScope.current()).isNull();
    }

    @Test
    void given_no_model_string_when_converse_then_configured_default_applied() {
        givenStream(new TextBlockDeltaEvent("r-1", "b-1", "默认"));

        client.converse(command(null, null), event -> {
        });

        verify(factory).obtain(eq("platform-agent"), eq("你是平台智能体。"),
                eq("deepseek:deepseek-v4-flash"), any(), any());
    }

    // ---------- 挂起语义 / resume ----------

    @Test
    void given_non_ask_user_confirm_event_when_converse_then_permission_required_and_no_run_finish() {
        givenStream(
                new TextBlockDeltaEvent("r-1", "b-1", "需要确认一个操作："),
                new RequireUserConfirmEvent("reply-9", List.of(
                        new ToolUseBlock("tc-1", "command",
                                Map.of("command", "rm -rf /workspace/data")))));

        List<AgentEvent> frames = new ArrayList<>();
        var reply = client.converse(command(null, null), frames::add);

        // #83 事件拆分：非提问挂起 = permission-required（确认卡呈现源）；挂起 = 软终点
        // ——解说尾段部件先出（确认卡前不留解说尾巴），不发 run-finish，挂起面随返回值上浮
        assertThat(frames.stream().map(AgentEvent::type)).containsExactly(
                AgentEventTypes.RUN_START,
                "text", AgentEventTypes.PART_TEXT, AgentEventTypes.PERMISSION_REQUIRED);
        AgentEvent permission = frames.get(3);
        assertThat(permission.payload()).containsEntry(AgentEventTypes.WAIT_ENGINE_REF_FIELD, "reply-9");
        assertThat(permission.payload()).containsEntry(
                AgentEventTypes.WAIT_SUMMARY_FIELD, "rm -rf /workspace/data");
        // data = 待确认工具最小面（恢复入参由业务编排从项目侧事实重建，不随事件携带）
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) permission.payload()
                .get(AgentEventTypes.WAIT_DATA_FIELD);
        assertThat(data).containsOnlyKeys("toolCalls");
        assertThat(reply.suspension()).isNotNull();
        assertThat(reply.suspension().engineRef()).isEqualTo("reply-9");
        assertThat(reply.suspension().question()).isFalse();
        assertThat(reply.suspension().toolCalls()).isEqualTo(List.of(Map.of(
                "id", "tc-1", "name", "command",
                "input", Map.of("command", "rm -rf /workspace/data"))));
    }

    @Test
    void given_ask_user_confirm_event_when_converse_then_question_raised_with_question_suspension() {
        givenStream(
                new RequireUserConfirmEvent("reply-8", List.of(
                        new ToolUseBlock("tc-q", "ask_user", Map.of("question", "用哪个框架?")))));

        List<AgentEvent> frames = new ArrayList<>();
        var reply = client.converse(command(null, null), frames::add);

        // 提问挂起 = question-raised（问答作答通道），挂起面 question=true
        assertThat(frames.stream().map(AgentEvent::type)).containsExactly(
                AgentEventTypes.RUN_START, AgentEventTypes.QUESTION_RAISED);
        assertThat(reply.suspension().question()).isTrue();
        assertThat(reply.suspension().engineRef()).isEqualTo("reply-8");
    }

    @Test
    void given_resume_request_when_resume_then_confirm_results_in_metadata_and_finishes() {
        when(factory.obtain(any(), any(), any(), any(), any())).thenReturn(agent);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Msg>> messages = ArgumentCaptor.forClass(List.class);
        when(agent.streamEvents(any(List.class), any(RuntimeContext.class)))
                .thenReturn(Flux.just(new TextBlockDeltaEvent("r-2", "b-2", "续跑中")));

        List<AgentEvent> frames = new ArrayList<>();
        client.resume(new AgentResume(
                "run-1", "s-1", "alice", null, "deepseek:deepseek-v4-flash", null, "reply-9",
                List.of(new ConfirmResult(true,
                        new ToolUseBlock("tc-1", "write_file", Map.of("path", "x")))),
                "approved", null, null, false), frames::add);

        // 恢复消息带 ConfirmResult metadata（AgentScope 挂起恢复口）；续跑流正常收口
        verify(agent).streamEvents(messages.capture(), any(RuntimeContext.class));
        Msg resumeMsg = messages.getValue().get(0);
        assertThat(resumeMsg.getMetadata()
                .get(Msg.METADATA_CONFIRM_RESULTS)).isInstanceOf(List.class);
        assertThat(resumeMsg.getTextContent()).isEqualTo("approved");
        // 续跑流部件恒挂：解说尾段部件在收口事件前
        assertThat(frames.stream().map(AgentEvent::type)).containsExactly(
                "text", AgentEventTypes.PART_TEXT, AgentEventTypes.RUN_FINISH);
        verify(factory).obtain(any(), any(), eq("deepseek:deepseek-v4-flash"), any(), any());
    }

    @Test
    void given_resume_prepare_fails_when_resume_then_error_frame_emitted_and_rethrown() {
        // resume 跑在异步轨道（异常被吞只记日志）：缺 API key 致模型创建失败等
        // 前段失败必须先发 error 事件（runId 锚定）再上抛——否则用户侧死寂
        when(factory.obtain(any(), any(), any(), any(), any())).thenThrow(new IllegalArgumentException(
                "Failed to create model for id: deepseek:deepseek-v4-flash: "
                        + "Environment variable DEEPSEEK_API_KEY is required to auto-create model"));

        List<AgentEvent> frames = new ArrayList<>();
        assertThatThrownBy(() -> client.resume(new AgentResume(
                "run-1", "s-1", "alice", null, "deepseek:deepseek-v4-flash", null, "reply-9",
                List.of(new ConfirmResult(true,
                        new ToolUseBlock("tc-1", "write_file", Map.of("path", "x")))),
                "approved", null, null, false), frames::add))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(frames.stream().map(AgentEvent::type))
                .containsExactly(AgentEventTypes.ERROR);
        assertThat(frames.get(0).payload())
                .containsEntry("runId", "run-1")
                .containsEntry("message",
                        "Failed to create model for id: deepseek:deepseek-v4-flash: "
                                + "Environment variable DEEPSEEK_API_KEY is required to auto-create model");
    }

    @Test
    void given_answered_tool_call_shape_when_rebuild_then_answer_in_metadata_input_untouched() {
        // 问答续跑批复重建（#34 口径）：input 原样不重写（answer 不进模型可见面），
        // 答复走 block metadata + ASKING 同形 + content 回填（重放参数校验只认
        // content 原文）
        ConfirmResult result = AgentscopeAgentClient.answeredToolCall(
                Map.of("id", "tc-1", "name", "ask_user",
                        "input", Map.of("question", "选哪个方案？")),
                "甲号方案");

        assertThat(result.isConfirmed()).isTrue();
        assertThat(result.getToolCall().getName()).isEqualTo("ask_user");
        assertThat(result.getToolCall().getInput())
                .containsEntry("question", "选哪个方案？")
                .doesNotContainKey("answer");
        assertThat(result.getToolCall().getContent()).doesNotContain("甲号方案");
        assertThat(result.getToolCall().getMetadata())
                .containsEntry(AgentscopeAgentClient.ANSWER_METADATA_KEY, "甲号方案");
        assertThat(result.getToolCall().getState()).isEqualTo(io.agentscope.core.message.ToolCallState.ASKING);
    }

    @Test
    void given_confirmed_tool_call_shape_when_rebuild_then_approved_flag_and_untouched_input() {
        // 权限作答复跑批复重建（#83）：批准位进 ConfirmResult（拒绝 = false，引擎写
        // DENIED 工具结果回模型）；input 原样、无答复 metadata（批准/拒绝无文本面）
        ConfirmResult approved = AgentscopeAgentClient.confirmedToolCall(
                Map.of("id", "tc-9", "name", "command",
                        "input", Map.of("command", "rm -rf /workspace/data")),
                true);
        assertThat(approved.isConfirmed()).isTrue();
        assertThat(approved.getToolCall().getName()).isEqualTo("command");
        assertThat(approved.getToolCall().getInput())
                .containsEntry("command", "rm -rf /workspace/data");
        assertThat(approved.getToolCall().getMetadata()).isEmpty();
        assertThat(approved.getToolCall().getContent()).contains("rm -rf");
        assertThat(approved.getToolCall().getState())
                .isEqualTo(io.agentscope.core.message.ToolCallState.ASKING);

        ConfirmResult denied = AgentscopeAgentClient.confirmedToolCall(
                Map.of("id", "tc-9", "name", "command",
                        "input", Map.of("command", "rm -rf /workspace/data")),
                false);
        assertThat(denied.isConfirmed()).isFalse();
    }

    @Test
    void given_history_states_when_has_asking_tool_call_then_reflects_pending() {
        // 挂起判定谓词（issue #40 守卫事实源）：memory_messages（引擎
        // StateBackedMemory 持久化的会话史）中存在 ASKING 态 ToolUseBlock
        // = 会话有挂起问答
        when(stateStore.getList("alice", "s-1", "memory_messages", Msg.class))
                .thenReturn(List.of(toolUseMessage(ToolCallState.ASKING)));
        assertThat(client.hasAskingToolCall("alice", "s-1")).isTrue();

        // 已收口（FINISHED）的问答不算挂起；空史（会话从未挂起）同 false
        when(stateStore.getList("alice", "s-1", "memory_messages", Msg.class))
                .thenReturn(List.of(toolUseMessage(ToolCallState.FINISHED)));
        assertThat(client.hasAskingToolCall("alice", "s-1")).isFalse();

        when(stateStore.getList("alice", "s-1", "memory_messages", Msg.class))
                .thenReturn(List.of());
        assertThat(client.hasAskingToolCall("alice", "s-1")).isFalse();
    }

    @Test
    void given_long_reply_id_when_shortened_then_idempotency_key_fits_column_limit() {
        // #109 模型边界计量：resume 幂等键 = agent-usage-{runId}-{replyId 短形}-{seq}，
        // replyId（引擎 32 位 UUID 十六进制）截前 8 位防超 event_id 列限 64；短串原样
        assertThat(AgentscopeAgentClient.shortReplyKey(
                "0123456789abcdef0123456789abcdef")).isEqualTo("01234567");
        assertThat(AgentscopeAgentClient.shortReplyKey("reply-9")).isEqualTo("reply-9");
    }

    private static Msg toolUseMessage(ToolCallState state) {
        return Msg.builder().role(MsgRole.ASSISTANT).content(List.of(new ToolUseBlock(
                "tc-1", "ask_user", Map.of("question", "目标用户是谁？"), null, null, state)))
                .build();
    }
}
