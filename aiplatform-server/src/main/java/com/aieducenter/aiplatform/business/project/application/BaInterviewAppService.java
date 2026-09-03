package com.aieducenter.aiplatform.business.project.application;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.agentscope.AgentCommand;
import com.aieducenter.aiplatform.base.agentscope.AgentResume;
import com.aieducenter.aiplatform.base.agentscope.AgentSessionExecutor;
import com.aieducenter.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.aieducenter.aiplatform.base.agentscope.UsageContext;
import com.aieducenter.aiplatform.base.eventhub.application.AgentStreamAppService;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.business.order.application.OrderQueryAppService;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.RolePreset;
import com.aieducenter.aiplatform.business.project.domain.model.UsageDims;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * BA 访谈编排：BA 是平台进程内对话智能体（AgentScope HarnessAgent，经
 * {@link AgentscopeAgentClient} 直调——编排缝极薄，无中间端口层），创建即访谈
 * （{@code ProjectLifecycleAppService} 建项目后开场）与后续对话轮都续同一
 * BA 会话。
 *
 * <p><b>会话寻址（projectId → BA 会话的稳定绑定）</b>：sessionId =
 * {@code ba-{projectId}} 无表派生、userId = 项目 owner（状态槽位
 * (userId, sessionId) 跨轮一致，谁触发都不劈叉上下文；cat_agent_state 承载，
 * 平台重启后同标识恢复）。计量（dims 终态口径 {@link UsageDims}，agentKind=ba）
 * 与智能体资产（ask_user / savePrd 工具集，按角色发放）同归 BA 编排。</p>
 *
 * <p><b>链必达收口（#43）</b>：BA 无派发权——修正 run 的派发不在模型手里，平台
 * 在 BA 回合落定后观测收口（无挂起问答且项目已生成）即自动派修正 run（交接物
 * = 用户意见原文 + BA 判定结果——PRD 改没改、改了什么，见
 * {@link #dispatchFixOnTurnClose}）。判定结果从工具调用事实观测
 * （{@link PrdRevisionFacts}），不新增模型自报结论的面；守卫沿用（未生成止于
 * BA、归档拒、在途排队合并，归 {@link IterationAppService}）。</p>
 *
 * <p><b>流桥</b>：过程帧经 {@link AgentStreamAppService}（eventhub 唯一 SSE 管道）
 * 发射，关联字段（projectId）逐帧注入——底座不解释、透传。发射失败护栏：单帧发射
 * 异常只记日志不断流（SSE 是「让 UI 活」的面，不承担正确性）；对话本身的成败以
 * error 帧 + 异常表达（会话执行器吞掉记日志，REST 快返回）。</p>
 */
@Service
@Slf4j
public class BaInterviewAppService {

    /** BA 会话标识派生前缀（projectId → ba-{projectId}，稳定绑定勿动）。 */
    public static final String SESSION_PREFIX = "ba-";

    /**
     * 引擎透传工具帧的观测常量（SSE事件清单·通道二透传行：tool 帧 {@code data} 为
     * {toolCallId, toolName, phase: start|end}）——BA 流上的 savePrd 起跑观测
     * （#50 阶段状态条「更新 PRD 中」），透传集合开放、契约名照正本引用。
     */
    private static final String TOOL_FRAME_TYPE = "tool";
    private static final String TOOL_DATA_FIELD = "data";
    private static final String TOOL_NAME_FIELD = "toolName";
    private static final String TOOL_PHASE_FIELD = "phase";
    private static final String SAVE_PRD_TOOL = "savePrd";

    private final ProjectRepository projectRepository;
    private final AgentscopeAgentClient agentClient;
    private final AgentStreamBridge streamBridge;
    private final AgentSessionExecutor sessionExecutor;
    private final ProjectKnowledgeAppService knowledgeAppService;
    private final OrderQueryAppService orderQueryAppService;
    private final IterationAppService iterationAppService;
    private final PrdRevisionFacts prdRevisions;

    /**
     * 挂起交换的意见锚（sessionId → 交接物意见腿文本）：意见原文随会话任务落锚
     * （任务首行 put，随执行器串行——BA 轮在途的新意见各自成轮，后写不覆盖在途
     * 轮的锚，#54），追问挂起期间的答复逐条并入（{@link #appendOpinionReply}）
     * ——收口派发的任务即锚的终值；收口即消费，轮炸即清（失败不留锚，重提即
     * 兜底）。进程内态，重启丢锚（续跑收口降级为仅末条答复，实质由 PRD 承载）。
     */
    private final Map<String, String> opinionExchanges = new ConcurrentHashMap<>();

    public BaInterviewAppService(ProjectRepository projectRepository,
            AgentscopeAgentClient agentClient, AgentStreamBridge streamBridge,
            AgentSessionExecutor sessionExecutor, ProjectKnowledgeAppService knowledgeAppService,
            OrderQueryAppService orderQueryAppService, IterationAppService iterationAppService,
            PrdRevisionFacts prdRevisions) {
        this.projectRepository = projectRepository;
        this.agentClient = agentClient;
        this.streamBridge = streamBridge;
        this.sessionExecutor = sessionExecutor;
        this.knowledgeAppService = knowledgeAppService;
        this.orderQueryAppService = orderQueryAppService;
        this.iterationAppService = iterationAppService;
        this.prdRevisions = prdRevisions;
    }

    /**
     * BA 会话建立轮（建项目自动开场专用）：绑定 {@code ba-{projectId}} 之时做
     * 知识命中注入——query = 初始需求原文，命中块落会话缓存并接 system prompt
     * 尾部（知识是背景非指令）；检索失败降级为空注入，访谈照常开始（#5 决议①）。
     * 一次切入一次注入：后续轮（{@link #runInterviewTurn}）与问答续跑
     * （{@link #answerQuestion}）复用同一注入块，不重检索不重追加。
     *
     * @throws ApplicationException PRJ_001 项目不存在；守卫组同 {@link #runInterviewTurn}
     *                              （自动开场仅 PRJ_001 可实际发生）
     */
    public InterviewRun startInterview(Long projectId, String requirement) {
        knowledgeAppService.establishSessionInjection(projectId, requirement);
        return turn(projectId, requirement);
    }

    /**
     * 派发入口全局守卫（#51 守卫后移）：指令区开着的最低门槛——项目存在 / 未归档
     * （归档 = 指令区物理关闭，咨询与兜底也停）。订单冻结（ORD_006）与挂起问答
     * （PRJ_024）只拦意见链：意见分岔自带同款守卫（{@link #turn}，防御纵深），
     * 咨询与兜底随时可答（CONTEXT.md「派发」）。已知代价：被拒意见先烧一次
     * flash 分类调用（秒级轻调用，接受）。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_013 项目已归档（指令区关闭）
     */
    public Project requireDispatchableProject(Long projectId) {
        Project project = requireProject(projectId);
        if (project.getArchivedAt() != null) {
            throw new ApplicationException(ProjectMessage.PROJECT_ALREADY_ARCHIVED);
        }
        return project;
    }

    /**
     * 跑一轮 BA 访谈（指令区发言，prompt 即用户侧输入）：会话执行器异步提交即
     * 返回（runId 随响应回，过程帧经 SSE；失败经 error 帧表达不炸调用方）。
     * system prompt = 角色卡 + 会话注入块（未建立/空注入/重启后 = 裸角色卡）。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_013 项目已归档（指令区关闭）；
 *                              ORD_006 订单处理中（下单即冻结迭代，取消即解冻）；
 *                              PRJ_024 挂起问答待答（同步 409 指路作答，#40 / ADR-0005）
     */
    public InterviewRun runInterviewTurn(Long projectId, String prompt) {
        return turn(projectId, prompt);
    }

    private InterviewRun turn(Long projectId, String prompt) {
        Project project = requireInterviewableProject(projectId);
        RolePreset role = RolePreset.BA;
        String sessionId = SESSION_PREFIX + projectId;
        requireNoPendingQuestion(project, sessionId);

        String runId = AgentStreamAppService.newRunId();
        Consumer<AgentEvent> sink = stageAwareSink(project, runId);
        streamBridge.emitRoleAssigned(projectId, runId, role);
        AgentCommand command = new AgentCommand(
                runId,
                prompt,
                role.systemPrompt() + knowledgeAppService.sessionTailOf(projectId),
                role.chatModelString(),
                sessionId,
                ownerUserIdOf(project),
                usageContextOf(projectId, role, sessionId),
                Long.toString(project.getWorkspaceId()),
                correlationOf(projectId),
                role.name());
        sessionExecutor.submit(sessionId, () -> {
            // 排队成轮（#54）：锚随任务落（同会话 FIFO——后发意见的 put 排在本轮
            // 收口之后，不覆盖在途轮的锚）：BA 轮在途连发的意见各自成轮、各自
            // 收口派发，撞在途修正 run 走排队合并
            opinionExchanges.put(sessionId, prompt);
            // 本轮需求侧判定从零起算（随会话执行器串行——上一轮收口消费在前，
            // 不会被本轮起跑插队 wipe）：炸轮滞留/访谈期的 savePrd 事实残留不进
            // 本轮交接物（意见锚无此滞留——失败即清，见下）
            prdRevisions.clear(Long.toString(project.getWorkspaceId()));
            try {
                agentClient.converse(command, sink);
            }
            catch (RuntimeException e) {
                // 失败即清锚（#54，对齐「收口即消费」）：炸轮不留锚——重提即兜底，
                // 不自动重试；error 帧已由 converse 内发出（异常上抛由会话执行器吞）
                opinionExchanges.remove(sessionId);
                throw e;
            }
            dispatchFixOnTurnClose(projectId, sessionId, runId);
        });
        return new InterviewRun(runId);
    }

    /**
     * 问答答复续跑（ask_user 挂起的恢复）：挂起轮的 runId/engineRef 与待确认工具
     * 清单（question-raised 帧 data.toolCalls 形状，前端问答卡回传）+ 用户答复 →
     * ConfirmResult 批复续跑（续跑续在同一 run 上收口，帧序含答复后的下一问或
     * 收口）。恢复私货（角色卡/owner/工作区/计量）从项目侧事实重建，不信前端。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_013 项目已归档（指令区关闭）；
 *                              ORD_006 订单处理中（下单即冻结迭代，取消即解冻）
     */
    public void answerQuestion(Long projectId, String runId, String replyId,
            List<Map<String, Object>> pendingToolCalls, String answerText) {
        Project project = requireInterviewableProject(projectId);
        RolePreset role = RolePreset.BA;
        String sessionId = SESSION_PREFIX + projectId;
        Map<String, Object> correlation = correlationOf(projectId);

        AgentResume resume = new AgentResume(
                runId,
                sessionId,
                ownerUserIdOf(project),
                Long.toString(project.getWorkspaceId()),
                role.chatModelString(),
                role.systemPrompt() + knowledgeAppService.sessionTailOf(projectId),
                replyId,
                pendingToolCalls.stream()
                        .map(toolCall -> AgentscopeAgentClient.answeredToolCall(toolCall, answerText))
                        .toList(),
                answerText,
                usageContextOf(projectId, role, sessionId),
                role.name());
        appendOpinionReply(sessionId, answerText);
        Consumer<AgentEvent> sink = stageAwareSink(project, runId);
        sessionExecutor.submit(sessionId, () -> {
            agentClient.resume(resume, sink);
            dispatchFixOnTurnClose(projectId, sessionId, runId);
        });
    }

    /** 一轮访谈的运行标识（前端挂智能体流 ?runId= 的锚）。 */
    public record InterviewRun(String runId) {
    }

    // ---------- 内部 ----------

    /**
     * 链必达收口观测（#43）：BA 回合落定（对话轮收口或问答续跑收口）后观测链的
     * 走向——会话有挂起问答 = 本轮未收口（答复后续跑再判，意见锚保留）；未生成
     * = 静默止于 BA（访谈期常态：生成前意见链终点）；收口前归档 = 竞态守卫，
     * 静默不派。三者皆过即平台自动派修正 run（交接物 = {@link #opinionExchanges}
     * 中的意见原文及追问答复 + {@link #prdRevisions} 中的 PRD 修订事实——本轮
     * savePrd 调用的 summary 终值，无调用事实即 null「未修订」）。BA 无派发权：
     * 模型存没存 PRD、调没调任何工具都不影响派发——链的收口在平台代码。派发
     * 失败发 {@code dispatch-failed} 失败终态帧如实告知重提（#51：状态条不悬死
     * 在「派发中」）；不炸 BA 轨道、不恢复意见锚（收口即消费语义保持）、不自动
     * 重试——重提即兜底。
     *
     * <p>#50：收口派发即发阶段帧——起跑发 {@code dispatching}、在途排队发
     * {@code queued}（如实呈现排队，锚本条意见的 BA 轮 runId）。</p>
     */
    private void dispatchFixOnTurnClose(Long projectId, String sessionId, String runId) {
        try {
            Project project = projectRepository.findById(projectId).orElse(null);
            if (project == null
                    || agentClient.hasAskingToolCall(ownerUserIdOf(project), sessionId)) {
                return;
            }
            String workspaceId = Long.toString(project.getWorkspaceId());
            if (project.getGeneratedAt() == null || project.getArchivedAt() != null) {
                opinionExchanges.remove(sessionId); // 收口即消费：锚不留过轮
                prdRevisions.clear(workspaceId); // 修订事实同锚口径：不留过轮
                return;
            }
            String task = opinionExchanges.remove(sessionId);
            if (task == null || task.isBlank()) {
                return; // 锚缺失（任务内落锚先于收口，同任务序——进程内理论不可达）——防御不派
            }
            String prdRevisionSummary = prdRevisions.consume(workspaceId);
            // 派发中先于派发调用发射（帧序先于轨道起跑——轨道内首帧即 fixing）
            streamBridge.emitDispatchStage(projectId, runId, DispatchStage.DISPATCHING);
            IterationAppService.FixDispatch dispatch =
                    iterationAppService.startFixRun(projectId, task, prdRevisionSummary);
            if (dispatch.queued()) {
                streamBridge.emitDispatchStage(projectId, runId, DispatchStage.QUEUED);
            }
            log.info("[ba-close] 项目 {} BA 回合收口，平台自动派修正 run（{}，PRD {}）",
                    projectId, dispatch.queued() ? "排队下一轮" : "起跑",
                    prdRevisionSummary != null ? "已修订" : "未修订");
        }
        catch (RuntimeException e) {
            // 派发失败终态帧（#51）：状态条不悬死在「派发中」，如实告知重提——
            // 意见锚已消费（不恢复）、不自动重试，重提即兜底；发射护栏同阶段帧
            try {
                streamBridge.emitDispatchStage(projectId, runId, DispatchStage.DISPATCH_FAILED);
            }
            catch (RuntimeException emitFailure) {
                log.warn("[ba-close] 项目 {} 派发失败终态帧发射失败：{}", projectId,
                        emitFailure.toString());
            }
            log.warn("[ba-close] 项目 {} 修正 run 自动派发失败（用户重提即兜底）：{}",
                    projectId, e.toString());
        }
    }

    /**
     * BA 流的阶段观测装饰（#50）：意见链进入状态条的前提是项目已生成（生成前
     * 意见链止于 BA，无隐藏处理段，不发帧——访谈期以对话面本身呈现）。已生成时
     * 先发 {@code analyzing}（正在分析您的意见，先于 role-assigned——状态条先
     * 开口），流中观测两处阶段边界：问答挂起（question-raised·QUESTION）→
     * {@code clarifying}、savePrd 起跑（tool 帧 start）→ {@code updating-prd}。
     * 阶段发射失败只记日志不断流。
     */
    private Consumer<AgentEvent> stageAwareSink(Project project, String runId) {
        Consumer<AgentEvent> sink = streamBridge.sink(project.getId());
        if (project.getGeneratedAt() == null) {
            return sink;
        }
        streamBridge.emitDispatchStage(project.getId(), runId, DispatchStage.ANALYZING);
        return event -> {
            sink.accept(event);
            try {
                observeStageBoundary(project.getId(), runId, event);
            }
            catch (RuntimeException e) {
                log.warn("[dispatch-stage] 项目 {} 阶段帧发射失败（不断流）：{}",
                        project.getId(), e.toString());
            }
        };
    }

    /** BA 流上的阶段边界观测：问答挂起 → 追问中；savePrd 起跑 → 更新 PRD 中。 */
    private void observeStageBoundary(Long projectId, String runId, AgentEvent event) {
        if (AgentEventTypes.QUESTION_RAISED.equals(event.type()) && isQuestionKind(event)) {
            streamBridge.emitDispatchStage(projectId, runId, DispatchStage.CLARIFYING);
            return;
        }
        if (TOOL_FRAME_TYPE.equals(event.type()) && isSavePrdStart(event)) {
            streamBridge.emitDispatchStage(projectId, runId, DispatchStage.UPDATING_PRD);
        }
    }

    private static boolean isQuestionKind(AgentEvent event) {
        return "QUESTION".equals(String.valueOf(event.payload().get(AgentEventTypes.WAIT_KIND_FIELD)));
    }

    /** savePrd 起跑判定（tool 透传帧 data 形状照 SSE事件清单；只认 start 不认 end——更新中）。 */
    private static boolean isSavePrdStart(AgentEvent event) {
        if (!(event.payload().get(TOOL_DATA_FIELD) instanceof Map<?, ?> data)) {
            return false;
        }
        return SAVE_PRD_TOOL.equals(data.get(TOOL_NAME_FIELD))
                && "start".equals(data.get(TOOL_PHASE_FIELD));
    }

    /** 追问答复并入意见锚（挂起交换期间累积——多轮追问的答复都进交接物）；
     * 无锚（重启丢锚的续跑）时以答复自立。 */
    private void appendOpinionReply(String sessionId, String reply) {
        opinionExchanges.compute(sessionId, (key, opinion) -> opinion == null
                ? reply : opinion + "；用户对追问的答复：" + reply);
    }

    private static Map<String, Object> correlationOf(Long projectId) {
        return Map.of(AgentStreamAppService.PROJECT_FIELD, projectId.toString());
    }

    private static UsageContext usageContextOf(Long projectId, RolePreset role,
            String sessionId) {
        return new UsageContext(Long.toString(projectId),
                UsageDims.of(projectId, UsageDims.kindOf(role), sessionId));
    }

    private Project requireProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
    }

    /** 访谈态守卫：归档即指令区关闭（只读终态）；未终结订单在即冻结迭代
     * （下单后的意见不再受理，取消订单即解冻回迭代态）——新发言与作答一并拒绝。 */
    private Project requireInterviewableProject(Long projectId) {
        Project project = requireProject(projectId);
        if (project.getArchivedAt() != null) {
            throw new ApplicationException(ProjectMessage.PROJECT_ALREADY_ARCHIVED);
        }
        orderQueryAppService.requireNoActiveOrder(projectId);
        return project;
    }

    /** 挂起问答守卫（#40 / ADR-0005）：会话存在挂起问答（ASKING 态工具块）时
     * 新输入不盲提交——引擎必拒且 REST 已返 200 只见异步 error 帧；同步 409
     * 指路作答。作答（resume）在途、ASKING 尚未清库的偶发拦截为已接受竞态边角。
     * 守卫先于 role-assigned 帧与命令提交，拒绝即零帧。 */
    private void requireNoPendingQuestion(Project project, String sessionId) {
        if (agentClient.hasAskingToolCall(ownerUserIdOf(project), sessionId)) {
            throw new ApplicationException(ProjectMessage.QUESTION_PENDING);
        }
    }

    /** owner 的会话寻址 userId（cat_agent_state 槽位 (userId, sessionId) 的 userId 腿）。 */
    private static String ownerUserIdOf(Project project) {
        return project.getOwnerAccountId() != null
                ? project.getOwnerAccountId().toString() : null;
    }
}
