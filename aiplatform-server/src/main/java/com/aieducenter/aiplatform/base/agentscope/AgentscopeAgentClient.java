package com.aieducenter.aiplatform.base.agentscope;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.metering.domain.model.TokenUsage;
import com.aieducenter.aiplatform.base.metering.domain.model.UsageEvent;
import com.aieducenter.aiplatform.base.metering.domain.port.UsageEventSink;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;

/**
 * AgentScope HarnessAgent 客户端（平台唯一智能体内核）：平台进程内跑一轮多轮对话
 * ——AgentScope 事件经 {@link AgentscopeEventMapper}（映射表单点）转平台智能体流帧
 * 逐个回调 sink（task-start/session-created 开场、text/reasoning/tool/step-* 过程、
 * task-finish/error 收口，runId 锚定；runTurn 前的前段失败——模型解析/agent 工厂
 * 构建/工作区解析——同样经 error 帧表达，异步轨道起跑失败不零帧死寂）；模型调用
 * 事件（ReAct 每迭代一条 ModelCallEnd）五桶累积，对话结束（含失败轮，已耗 token
 * 如实计量）按命令的 usageContext 上报恰一条 UsageEvent（幂等键
 * agent-usage-{runId}[-{replyId}]，engine=agentscope；归属为空不发明、零用量不报）。
 *
 * <p><b>挂起与续跑（问答机制）</b>：AgentScope 的确认挂起（RequireUserConfirmEvent，
 * 业务侧 ask_user 提问工具触发）= 本轮流软终点——发 {@code wait-raised} 帧（载荷带
 * 待确认工具清单，恢复入参由业务编排从项目侧事实重建）后流终止，<b>不发
 * task-finish</b>（run 未终态）；业务编排以 {@link #resume} 续跑（同一
 * (userId, sessionId) 从 AgentStateStore 恢复上下文——平台重启后可续，访谈上下文
 * 不丢），续跑流可再挂起（一 run 多批准点）或正常收口。失败上抛
 * IllegalStateException（异步轨道由会话执行器吞掉记日志，失败表达归 error 帧）。</p>
 *
 * <p>工作区解析：命令带 workspaceId → 项目 dev 工作区（容器文件面，docker exec
 * 读写，写入即落项目工作区）；缺省 → 配置的本地工作区。session-created 发射口径：
 * 按 cat_agent_state 槽位首见判定（跨重启不重发——该表承载全部智能体会话）。</p>
 */
@Component
public class AgentscopeAgentClient {

    /** 智能体栈单栈自述名（agent 流帧的引擎值正本——计量不记引擎）。 */
    public static final String ENGINE = "agentscope";

    /**
     * 提问工具注册名：业务侧 AskUserTool 以此名注册，mapper 以此名判定挂起帧的
     * QUESTION 载荷形状——两端共用一个真值（工具在 business，名字契约在此）。
     */
    public static final String ASK_USER_TOOL_NAME = "ask_user";

    private static final String USAGE_EVENT_PREFIX = "agent-usage-";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentscopeHarnessAgentFactory factory;
    private final AgentscopeProperties properties;
    private final WorkspaceLifecycleAppService workspaceLifecycleAppService;
    private final AgentStateStore stateStore;
    private final UsageEventSink usageEventSink;
    private final Clock clock;

    @Autowired
    public AgentscopeAgentClient(AgentscopeHarnessAgentFactory factory,
            AgentscopeProperties properties,
            WorkspaceLifecycleAppService workspaceLifecycleAppService,
            AgentStateStore stateStore,
            UsageEventSink usageEventSink) {
        this(factory, properties, workspaceLifecycleAppService, stateStore, usageEventSink,
                Clock.systemUTC());
    }

    AgentscopeAgentClient(AgentscopeHarnessAgentFactory factory,
            AgentscopeProperties properties,
            WorkspaceLifecycleAppService workspaceLifecycleAppService,
            AgentStateStore stateStore,
            UsageEventSink usageEventSink, Clock clock) {
        this.factory = factory;
        this.properties = properties;
        this.workspaceLifecycleAppService = workspaceLifecycleAppService;
        this.stateStore = stateStore;
        this.usageEventSink = usageEventSink;
        this.clock = clock;
    }

    /** 跑一轮对话：过程帧实时回调 sink（payload 已带 runId；关联字段由编排桥注入）。 */
    public AgentReply converse(AgentCommand command, Consumer<AgentEvent> sink) {
        PreparedTurn prepared;
        try {
            prepared = prepareTurn(command, sink);
        }
        catch (RuntimeException e) {
            // 前段（模型解析/agent 工厂构建/工作区解析）失败原是零帧区（异步轨道吞
            // 异常只记日志，用户只见死寂）——补发 error 帧（runId 锚定 = command 的）
            // 后照常上抛；静默调用（空 sink）无害丢弃
            sink.accept(AgentscopeEventMapper.error(command.runId(), e.getMessage()));
            throw e;
        }
        TurnResult result = runTurn(prepared, List.of(new UserMessage(command.prompt())),
                command.runId(), USAGE_EVENT_PREFIX + command.runId(), command.usageContext(),
                command.timeout(), sink);
        if (result.error() != null) {
            throw new IllegalStateException("智能体调用失败（runId=" + command.runId()
                    + ", model=" + prepared.modelRef().toModelString() + "）："
                    + result.error().getMessage(), result.error());
        }
        return new AgentReply(command.runId(), result.text());
    }

    /**
     * 挂起续跑（业务编排的问答答复通道调用）：以 ConfirmResult（用户答复/批准/拒绝）
     * 经同一 (userId, sessionId) 恢复上下文续跑——不重发 task-start/session-created
     * （run 已开场），可再挂起（wait-raised 再发）或正常收口（task-finish）。
     * 计量幂等键带 replyId 后缀（挂起轮已报过 agent-usage-{runId}）。
     */
    public void resume(AgentResume resume, Consumer<AgentEvent> sink) {
        PreparedTurn prepared;
        try {
            prepared = prepareFor(resume.runId(), resume.sessionId(), resume.userId(),
                    resume.modelString(), resume.systemPrompt(), resume.workspaceId());
        }
        catch (RuntimeException e) {
            // 同口径补口（resume 跑在异步轨道，异常被吞只记日志）：前段失败必须先发
            // error 帧（runId 锚定）再上抛——缺 API key 致模型创建失败这类故障，
            // 用户侧有明确报错而非死寂
            sink.accept(AgentscopeEventMapper.error(resume.runId(), e.getMessage()));
            throw e;
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(Msg.METADATA_CONFIRM_RESULTS, resume.confirmResults());
        Msg resumeMsg = UserMessage.builder()
                .textContent(resume.resumeText() != null ? resume.resumeText() : "")
                .metadata(metadata)
                .build();

        runTurn(prepared, List.of(resumeMsg), resume.runId(),
                USAGE_EVENT_PREFIX + resume.runId() + "-" + resume.replyId(),
                resume.usageContext(), null, sink);
    }

    /**
     * 提问类挂起的续跑批复：待确认工具（挂起帧 data.toolCalls 元素形状 {id,name,input}）
     * + 用户答复 → ConfirmResult（答复注入工具 input 的 answer 键——提问工具执行即读
     * 它作为工具结果回给模型）。重建为 ASKING 态与会话状态同形（确认应用按此替换，
     * 否则原 ASKING 残留卡后续轮）；content 回填 input 的 JSON 串——重放的参数校验
     * （ToolValidator.validateInput）只认 content 原文不认 input map，null 会让重放炸
     * 「argument is null」错误结果给模型（模型见错换 id 重问/自述提问系统失败）。
     */
    public static ConfirmResult answeredToolCall(Map<String, Object> toolCall, String answerText) {
        Map<String, Object> input = new LinkedHashMap<>();
        if (toolCall.get("input") instanceof Map<?, ?> inputMap) {
            inputMap.forEach((key, value) -> input.put(String.valueOf(key), value));
        }
        input.put("answer", answerText);
        return new ConfirmResult(true, new ToolUseBlock(
                String.valueOf(toolCall.get("id")), String.valueOf(toolCall.get("name")),
                input, toJson(input), null, ToolCallState.ASKING));
    }

    // ---------- 内部 ----------

    /** 一轮流的结果（挂起轮 text 为已生成部分；error 非空 = 失败）。 */
    private record TurnResult(String text, Throwable error) {
    }

    /** 前段产物（模型解析 + agent 构建 + 会话上下文 + 映射表）。 */
    private record PreparedTurn(ModelRef modelRef, HarnessAgent agent, RuntimeContext ctx,
            AgentscopeEventMapper mapper) {
    }

    /**
     * converse 前段：公共准备 + 开场帧（task-start / session-created 首见）。本段自身
     * 不做失败处理——任一失败由 {@link #converse} 补发 error 帧后上抛。
     */
    private PreparedTurn prepareTurn(AgentCommand command, Consumer<AgentEvent> sink) {
        PreparedTurn prepared = prepareFor(command.runId(), command.sessionId(), command.userId(),
                command.modelString(), command.systemPrompt(), command.workspaceId());
        sink.accept(AgentscopeEventMapper.taskStart(command.runId(), command.prompt(),
                prepared.modelRef().toModelString(), ENGINE));
        if (firstSeen(command.userId(), command.sessionId())) {
            sink.accept(AgentscopeEventMapper.sessionCreated(
                    command.runId(), command.sessionId(), ENGINE));
        }
        return prepared;
    }

    /**
     * 前段公共体（converse 首轮与 resume 续跑共用）：模型解析（配置兜底）→ 工作区
     * 解析 → agent 工厂构建 → 会话上下文与映射表组装。
     */
    private PreparedTurn prepareFor(String runId, String sessionId, String userId,
            String modelString, String systemPrompt, String workspaceId) {
        ModelRef modelRef = ModelRef.parse(modelString != null
                ? modelString : properties.getDefaultModel());
        String sysPrompt = systemPrompt != null
                ? systemPrompt : properties.getDefaultSystemPrompt();
        AgentWorkspace workspace = resolveWorkspace(workspaceId);
        HarnessAgent agent = factory.obtain(properties.getAgentName(), sysPrompt,
                modelRef.toModelString(), workspace);
        return new PreparedTurn(modelRef, agent, runtimeContext(sessionId, userId),
                new AgentscopeEventMapper(runId, sessionId, ENGINE));
    }

    /**
     * 一轮流的公共体（converse 首轮与 resume 续跑共用）：事件逐帧映射发射，挂起
     * （RequireUserConfirm）发 wait-raised 后流终止且不发 task-finish；正常收口发
     * task-finish；异常发 error 帧。用量无论成败如实上报（幂等键由调用方给）；
     * 超时取逐轮指定（可空 = 内核配置默认）。
     */
    private TurnResult runTurn(PreparedTurn prepared, List<Msg> messages, String runId,
            String usageIdempotencyKey, UsageContext usageContext, Duration timeout,
            Consumer<AgentEvent> sink) {
        StringBuilder text = new StringBuilder();
        AtomicReference<TokenUsage> usage = new AtomicReference<>(TokenUsage.ZERO);
        AtomicReference<String> finish = new AtomicReference<>();
        AtomicReference<RequireUserConfirmEvent> suspension = new AtomicReference<>();
        AgentscopeEventMapper mapper = prepared.mapper();
        try {
            prepared.agent().streamEvents(messages, prepared.ctx())
                    .doOnNext(event -> handleEvent(event, mapper, sink, text, usage, finish,
                            suspension))
                    .blockLast(timeout != null ? timeout : properties.getTimeout());
            if (suspension.get() == null) {
                sink.accept(AgentscopeEventMapper.taskFinish(
                        runId, prepared.ctx().getSessionId(), finish.get(), ENGINE));
            }
            return new TurnResult(text.toString(), null);
        }
        catch (Exception e) {
            sink.accept(AgentscopeEventMapper.error(runId, e.getMessage()));
            return new TurnResult(text.toString(), e);
        }
        finally {
            reportUsage(usageIdempotencyKey, usage.get(), runId,
                    prepared.ctx().getSessionId(), prepared.modelRef(), usageContext);
        }
    }

    /** 工作区解析：带 workspaceId → 项目 dev 工作区；缺省 → 本地工作区。 */
    private AgentWorkspace resolveWorkspace(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            return new AgentWorkspace.Local(properties.getWorkspace());
        }
        var handle = workspaceLifecycleAppService.handleOf(workspaceId);
        return new AgentWorkspace.ProjectDev(workspaceId, handle.containerName());
    }

    private RuntimeContext runtimeContext(String sessionId, String userId) {
        return RuntimeContext.builder()
                .sessionId(sessionId)
                .userId(userId)
                .build();
    }

    /**
     * session-created 首见判定：cat_agent_state 槽位 (userId, sessionId) 有行即已建
     * （跨重启不重发；该表承载全部智能体会话）。
     */
    private boolean firstSeen(String userId, String sessionId) {
        return !stateStore.exists(userId, sessionId);
    }

    private void handleEvent(io.agentscope.core.event.AgentEvent event,
            AgentscopeEventMapper mapper, Consumer<AgentEvent> sink, StringBuilder text,
            AtomicReference<TokenUsage> usage, AtomicReference<String> finish,
            AtomicReference<RequireUserConfirmEvent> suspension) {
        if (event instanceof TextBlockDeltaEvent delta) {
            text.append(delta.getDelta());
        }
        else if (event instanceof ModelCallEndEvent end) {
            usage.updateAndGet(total -> total.plus(AgentscopeUsageMapper.toTokenUsage(end.getUsage())));
        }
        else if (event instanceof RequireUserConfirmEvent confirm) {
            // 挂起：记软终点标志后发 wait-raised 帧（业务编排据此呈现问答卡），不产透传帧
            suspension.set(confirm);
            sink.accept(mapper.waitRaised(confirm));
            return;
        }
        mapper.finishToken(event).ifPresent(finish::set);
        AgentEvent frame = mapper.map(event);
        if (frame != null) {
            sink.accept(frame);
        }
    }

    private void reportUsage(String idempotencyKey, TokenUsage total, String runId,
            String sessionId, ModelRef modelRef, UsageContext usageContext) {
        if (usageContext == null || total.total() <= 0) {
            return;
        }
        usageEventSink.report(new UsageEvent(
                idempotencyKey,
                clock.instant(),
                usageContext.subject(),
                runId,
                sessionId,
                modelRef.provider(),
                modelRef.modelId(),
                usageContext.dims(),
                total));
    }

    private static String toJson(Map<String, Object> input) {
        try {
            return JSON.writeValueAsString(input);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("待确认工具参数序列化失败", e);
        }
    }
}
