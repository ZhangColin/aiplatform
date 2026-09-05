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
 * ——AgentScope 事件经 {@link AgentscopeEventMapper}（映射表单点）转平台智能体事件
 * 逐个回调 sink（run-start 开场、text/reasoning/tool/step-* 过程、run-finish/error
 * 收口，runId 锚定；runTurn 前的前段失败——模型解析/agent 工厂构建/工作区解析
 * ——同样经 error 事件表达，异步轨道起跑失败不零事件死寂）；同一事件流恒经
 * {@link AgentscopePartsMapper} 产消息部件事件（part-*，parts 契约：动作卡全生命
 * 周期 + 解说/步骤分组，收口/挂起事件前出解说尾段）；模型调用事件（ReAct 每迭代
 * 一条 ModelCallEnd）五桶累积，对话结束（含失败轮，已耗 token 如实计量）按命令的
 * usageContext 上报恰一条 UsageEvent（幂等键 agent-usage-{runId}[-{replyId}]，
 * engine=agentscope；归属为空不发明、零用量不报）。
 *
 * <p><b>挂起与续跑（作答机制，#83 通道分家）</b>：AgentScope 的确认挂起
 * （RequireUserConfirmEvent——ask_user 提问或需批准的工具操作）= 本轮流软终点——
 * 按分诊发 {@code question-raised}（问答卡，问答作答通道）或 {@code
 * permission-required}（确认卡，权限作答通道）事件（载荷带待确认工具清单，恢复
 * 入参由业务编排从项目侧事实重建）后流终止，<b>不发 run-finish</b>（run 未终态），
 * 挂起事实随返回值 {@link AgentReply#suspension()} 上浮；业务编排以 {@link #resume}
 * 续跑（同一 (userId, sessionId) 从 AgentStateStore 恢复上下文——平台重启后可续，
 * 访谈上下文不丢），续跑流可再挂起（一 run 多批准点）或正常收口。失败上抛
 * IllegalStateException（异步轨道由会话执行器吞掉记日志，失败表达归 error 事件）。</p>
 *
 * <p>工作区解析：命令带 workspaceId → 项目 dev 工作区（容器文件面，docker exec
 * 读写，写入即落项目工作区）；缺省 → 配置的本地工作区。</p>
 */
@Component
public class AgentscopeAgentClient {

    /** 智能体栈单栈自述名（智能体事件的引擎值正本——计量不记引擎）。 */
    public static final String ENGINE = "agentscope";

    /**
     * 提问工具注册名：业务侧 AskUserTool 以此名注册，mapper 以此名判定挂起事件的
     * QUESTION 载荷形状——两端共用一个真值（工具在 business，名字契约在此）。
     */
    public static final String ASK_USER_TOOL_NAME = "ask_user";

    /**
     * 问答答复的注入通道：挂起批复重写 block 的 metadata 键（#34 口径——答复不进
     * 工具 input：input 持久化进会话、模型可见，会教模型「ask_user 可自带答案」
     * 自答后续提问；metadata 不序列化给模型，仅工具执行体经 ToolCallParam 读取）。
     */
    public static final String ANSWER_METADATA_KEY = "answer";

    private static final String USAGE_EVENT_PREFIX = "agent-usage-";
    /** 会话史状态键（引擎 StateBackedMemory 持久化键同源——agentscope 未出公共常量）。 */
    private static final String MEMORY_MESSAGES_KEY = "memory_messages";
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

    /** 跑一轮对话：过程事件实时回调 sink（payload 已带 runId；关联字段由编排桥注入）。 */
    public AgentReply converse(AgentCommand command, Consumer<AgentEvent> sink) {
        PreparedTurn prepared;
        try {
            prepared = prepareTurn(command, sink);
        }
        catch (RuntimeException e) {
            // 前段（模型解析/agent 工厂构建/工作区解析）失败原是零事件区（异步轨道吞
            // 异常只记日志，用户只见死寂）——补发 error 事件（runId 锚定 = command 的）
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
        return new AgentReply(command.runId(), result.text(), result.suspension());
    }

    /**
     * 挂起续跑（业务编排的作答复原通道——问答作答与权限作答共用内核面）：以
     * ConfirmResult（用户答复/批准/拒绝）经同一 (userId, sessionId) 恢复上下文
     * 续跑——不重发 run-start（run 已开场），可再挂起（question-raised /
     * permission-required 再发）或正常收口（run-finish）。计量幂等键带 replyId
     * 后缀（挂起轮已报过 agent-usage-{runId}）。挂起轮软终点以返回值
     * {@link AgentReply#suspension()} 表达。
     */
    public AgentReply resume(AgentResume resume, Consumer<AgentEvent> sink) {
        PreparedTurn prepared;
        try {
            prepared = prepareFor(TurnSpec.resumeOf(resume));
        }
        catch (RuntimeException e) {
            // 同口径补口（resume 跑在异步轨道，异常被吞只记日志）：前段失败必须先发
            // error 事件（runId 锚定）再上抛——缺 API key 致模型创建失败这类故障，
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

        TurnResult result = runTurn(prepared, List.of(resumeMsg), resume.runId(),
                USAGE_EVENT_PREFIX + resume.runId() + "-" + resume.replyId(),
                resume.usageContext(), null, sink);
        if (result.error() != null) {
            throw new IllegalStateException("智能体续跑失败（runId=" + resume.runId()
                    + ", model=" + prepared.modelRef().toModelString() + "）："
                    + result.error().getMessage(), result.error());
        }
        return new AgentReply(resume.runId(), result.text(), result.suspension());
    }

    /**
     * 提问类挂起的续跑批复：待确认工具（挂起事件 data.toolCalls 元素形状 {id,name,input}）
     * + 用户答复 → ConfirmResult（input 原样不重写；答复注入重写 block 的 metadata
     * {@link #ANSWER_METADATA_KEY}——模型不可见通道，提问工具执行即读它作为工具
     * 结果回给模型。答复进 input 会持久化进会话教模型自答，#34）。重建为 ASKING 态
     * 与会话状态同形（确认应用按此替换并置 ALLOWED，否则原 ASKING 残留卡后续轮）；
     * content 回填 input 的 JSON 串——重放的参数校验（ToolValidator.validateInput）
     * 只认 content 原文不认 input map，null 会让重放炸「argument is null」错误结果给
     * 模型（模型见错换 id 重问/自述提问系统失败）。
     */
    public static ConfirmResult answeredToolCall(Map<String, Object> toolCall, String answerText) {
        Map<String, Object> input = new LinkedHashMap<>();
        if (toolCall.get("input") instanceof Map<?, ?> inputMap) {
            inputMap.forEach((key, value) -> input.put(String.valueOf(key), value));
        }
        return new ConfirmResult(true, new ToolUseBlock(
                String.valueOf(toolCall.get("id")), String.valueOf(toolCall.get("name")),
                input, toJson(input), Map.of(ANSWER_METADATA_KEY, answerText),
                ToolCallState.ASKING));
    }

    /**
     * 挂起问答判定（#40 守卫事实源）：会话持久化史（cat_agent_state 的
     * {@code memory_messages}）中存在 ASKING 态工具块 = 该会话有挂起问答（run
     * 软终点待答）。业务编排在新 converse 提交前于请求路径同步调用（异步轨道上
     * 引擎虽也会拒，但 REST 已返 200）；作答复在途、ASKING 尚未清库的偶发误报
     * 为已接受的竞态边角（ADR-0005）。
     */
    public boolean hasAskingToolCall(String userId, String sessionId) {
        return stateStore.getList(userId, sessionId, MEMORY_MESSAGES_KEY, Msg.class).stream()
                .flatMap(msg -> msg.getContentBlocks(ToolUseBlock.class).stream())
                .anyMatch(block -> block.getState() == ToolCallState.ASKING);
    }

    /**
     * 权限确认类挂起的续跑批复（#83 权限作答通道）：待确认工具 + 批准位 →
     * ConfirmResult（input 原样不重写；批准 = confirmed=true，拒绝 = false——引擎
     * 对拒绝写 DENIED 态工具结果回模型，模型可见拒绝事实、可改道或自行收口）。
     * 重建形状同 {@link #answeredToolCall}（ASKING 同形、content 回填 JSON 串）。
     */
    public static ConfirmResult confirmedToolCall(Map<String, Object> toolCall, boolean approved) {
        Map<String, Object> input = new LinkedHashMap<>();
        if (toolCall.get("input") instanceof Map<?, ?> inputMap) {
            inputMap.forEach((key, value) -> input.put(String.valueOf(key), value));
        }
        return new ConfirmResult(approved, new ToolUseBlock(
                String.valueOf(toolCall.get("id")), String.valueOf(toolCall.get("name")),
                input, toJson(input), Map.of(), ToolCallState.ASKING));
    }

    // ---------- 内部 ----------

    /** 一轮流的结果（挂起轮 text 为已生成部分、suspension 非空；error 非空 = 失败）。 */
    private record TurnResult(String text, AgentSuspension suspension, Throwable error) {
    }

    /** 前段产物（模型解析 + agent 构建 + 会话上下文 + 透传映射表 + 部件映射表）。 */
    private record PreparedTurn(ModelRef modelRef, HarnessAgent agent, RuntimeContext ctx,
            AgentscopeEventMapper mapper, AgentscopePartsMapper parts) {
    }

    /**
     * 一轮准备的寻址要素束（converse 首轮与 resume 续跑的同源字段，按名访问消除
     * 同型位置参数的错位面；只读面续跑不存在——挂起问答是 BA 资产，只读角色无
     * ask_user，resume 恒读写面）。
     */
    private record TurnSpec(String runId, String sessionId, String userId, String modelString,
            String systemPrompt, String workspaceId, String agentRole,
            boolean workspaceReadOnly) {

        static TurnSpec of(AgentCommand command) {
            return new TurnSpec(command.runId(), command.sessionId(), command.userId(),
                    command.modelString(), command.systemPrompt(), command.workspaceId(),
                    command.agentRole(), command.workspaceReadOnly());
        }

        static TurnSpec resumeOf(AgentResume resume) {
            return new TurnSpec(resume.runId(), resume.sessionId(), resume.userId(),
                    resume.modelString(), resume.systemPrompt(), resume.workspaceId(),
                    resume.agentRole(), false);
        }
    }

    /**
     * converse 前段：公共准备 + 开场事件（run-start）。本段自身不做失败处理——
     * 任一失败由 {@link #converse} 补发 error 事件后上抛。
     */
    private PreparedTurn prepareTurn(AgentCommand command, Consumer<AgentEvent> sink) {
        PreparedTurn prepared = prepareFor(TurnSpec.of(command));
        sink.accept(AgentscopeEventMapper.runStart(command.runId(), command.prompt(),
                prepared.modelRef().toModelString(), ENGINE, command.agentRole()));
        return prepared;
    }

    /**
     * 前段公共体（converse 首轮与 resume 续跑共用）：模型解析（配置兜底）→ 工作区
     * 解析 → agent 工厂构建（角色键穿透工具装配——按角色发放工具集）→ 会话上下文
     * 与映射表组装（部件映射表恒挂——消息部件是全部智能体事件的呈现地基）。
     */
    private PreparedTurn prepareFor(TurnSpec spec) {
        ModelRef modelRef = ModelRef.parse(spec.modelString() != null
                ? spec.modelString() : properties.getDefaultModel());
        String sysPrompt = spec.systemPrompt() != null
                ? spec.systemPrompt() : properties.getDefaultSystemPrompt();
        AgentWorkspace workspace = resolveWorkspace(spec.workspaceId(), spec.workspaceReadOnly());
        HarnessAgent agent = factory.obtain(properties.getAgentName(), sysPrompt,
                modelRef.toModelString(), workspace, spec.agentRole());
        return new PreparedTurn(modelRef, agent, runtimeContext(spec.sessionId(), spec.userId()),
                new AgentscopeEventMapper(spec.runId(), spec.sessionId(), ENGINE),
                new AgentscopePartsMapper(spec.runId(), spec.sessionId(), ENGINE));
    }

    /**
     * 一轮流的公共体（converse 首轮与 resume 续跑共用）：事件逐个映射发射，挂起
     * （RequireUserConfirm）按分诊发 question-raised / permission-required 后流终止
     * 且不发 run-finish；正常收口发 run-finish；异常发 error 事件。用量无论成败
     * 如实上报（幂等键由调用方给）；超时取逐轮指定（可空 = 内核配置默认）。
     */
    private TurnResult runTurn(PreparedTurn prepared, List<Msg> messages, String runId,
            String usageIdempotencyKey, UsageContext usageContext, Duration timeout,
            Consumer<AgentEvent> sink) {
        StringBuilder text = new StringBuilder();
        AtomicReference<TokenUsage> usage = new AtomicReference<>(TokenUsage.ZERO);
        AtomicReference<String> finish = new AtomicReference<>();
        AtomicReference<RequireUserConfirmEvent> suspended = new AtomicReference<>();
        AtomicReference<Boolean> suspendedQuestion = new AtomicReference<>();
        AgentscopeEventMapper mapper = prepared.mapper();
        try {
            prepared.agent().streamEvents(messages, prepared.ctx())
                    .doOnNext(event -> handleEvent(event, mapper, prepared.parts(),
                            sink, text, usage, finish, suspended, suspendedQuestion))
                    .blockLast(timeout != null ? timeout : properties.getTimeout());
            // 部件解说尾段先出（收口事件前），挂起轮已随挂起事件出尾——解说不因流形态丢尾
            drainParts(prepared.parts(), sink);
            RequireUserConfirmEvent suspension = suspended.get();
            if (suspension == null) {
                sink.accept(AgentscopeEventMapper.runFinish(
                        runId, prepared.ctx().getSessionId(), finish.get(), ENGINE));
                return new TurnResult(text.toString(), null, null);
            }
            return new TurnResult(text.toString(), new AgentSuspension(
                    suspension.getReplyId(), suspendedQuestion.get(),
                    toolCallFace(suspension)), null);
        }
        catch (Exception e) {
            drainParts(prepared.parts(), sink);
            sink.accept(AgentscopeEventMapper.error(runId, e.getMessage()));
            return new TurnResult(text.toString(), null, e);
        }
        finally {
            reportUsage(usageIdempotencyKey, usage.get(), runId,
                    prepared.ctx().getSessionId(), prepared.modelRef(), usageContext);
        }
    }

    /**
     * 工作区解析：带 workspaceId → 项目 dev 工作区（只读标记则解析为只读面，
     * #47）；缺省 → 本地工作区。
     */
    private AgentWorkspace resolveWorkspace(String workspaceId, boolean readOnly) {
        if (workspaceId == null || workspaceId.isBlank()) {
            return new AgentWorkspace.Local(properties.getWorkspace());
        }
        var handle = workspaceLifecycleAppService.handleOf(workspaceId);
        return readOnly
                ? new AgentWorkspace.ProjectReadOnly(workspaceId, handle.containerName())
                : new AgentWorkspace.ProjectDev(workspaceId, handle.containerName());
    }

    private RuntimeContext runtimeContext(String sessionId, String userId) {
        return RuntimeContext.builder()
                .sessionId(sessionId)
                .userId(userId)
                .build();
    }

    private void handleEvent(io.agentscope.core.event.AgentEvent event,
            AgentscopeEventMapper mapper, AgentscopePartsMapper parts,
            Consumer<AgentEvent> sink, StringBuilder text, AtomicReference<TokenUsage> usage,
            AtomicReference<String> finish, AtomicReference<RequireUserConfirmEvent> suspended,
            AtomicReference<Boolean> suspendedQuestion) {
        if (event instanceof TextBlockDeltaEvent delta) {
            text.append(delta.getDelta());
        }
        else if (event instanceof ModelCallEndEvent end) {
            usage.updateAndGet(total -> total.plus(AgentscopeUsageMapper.toTokenUsage(end.getUsage())));
        }
        else if (event instanceof RequireUserConfirmEvent confirm) {
            // 挂起：先出部件解说尾段（确认/问答卡前不留解说尾巴）再按分诊发挂起事件
            // （question-raised 问答卡 / permission-required 确认卡），不产透传事件
            suspended.set(confirm);
            suspendedQuestion.set(confirm.getToolCalls().stream()
                    .anyMatch(tc -> ASK_USER_TOOL_NAME.equals(tc.getName())));
            drainParts(parts, sink);
            sink.accept(mapper.suspension(confirm));
            return;
        }
        mapper.finishToken(event).ifPresent(finish::set);
        AgentEvent frame = mapper.map(event);
        if (frame != null) {
            sink.accept(frame);
        }
        // 部件事件：全事件流恒挂（parts 契约）
        parts.map(event).forEach(sink);
    }

    /** 挂起事件的待确认工具最小面（AgentSuspension.toolCalls 的形状，同事件 data.toolCalls）。 */
    private static List<Map<String, Object>> toolCallFace(RequireUserConfirmEvent event) {
        return AgentscopeEventMapper.toolCallPayloads(event.getToolCalls());
    }

    /** 部件收尾：解说余段出部件（幂等）。 */
    private static void drainParts(AgentscopePartsMapper parts, Consumer<AgentEvent> sink) {
        parts.drain().forEach(sink);
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
