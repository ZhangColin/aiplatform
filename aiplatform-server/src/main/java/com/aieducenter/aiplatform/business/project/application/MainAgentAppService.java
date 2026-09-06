package com.aieducenter.aiplatform.business.project.application;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.agentscope.AgentCommand;
import com.aieducenter.aiplatform.base.agentscope.AgentReply;
import com.aieducenter.aiplatform.base.agentscope.AgentResume;
import com.aieducenter.aiplatform.base.agentscope.AgentSessionExecutor;
import com.aieducenter.aiplatform.base.agentscope.AgentSuspension;
import com.aieducenter.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.aieducenter.aiplatform.base.agentscope.UsageContext;
import com.aieducenter.aiplatform.base.eventhub.application.EventsAppService;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.aieducenter.aiplatform.business.order.application.OrderQueryAppService;
import com.aieducenter.aiplatform.business.project.application.dto.command.AnnotationAttachment;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.AgentProfile;
import com.aieducenter.aiplatform.business.project.domain.model.UsageDims;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 主智能体单会话编排（#86 并轨，ADR 0006「单一主智能体+委派式执行」）：主智能体
 * 是平台进程内对话智能体（AgentScope HarnessAgent，经 {@link AgentscopeAgentClient}
 * 直调——编排缝极薄，无中间端口层），与用户的全部对话——建项目开场、追问、答询、
 * 受理意见、PRD 撰写修订、需求侧判定——都续同一会话，连续不换会话。
 *
 * <p><b>会话寻址（projectId → 主智能体会话的稳定绑定）</b>：sessionId =
 * {@code main-{projectId}} 无表派生、userId = 项目 owner（状态槽位
 * (userId, sessionId) 跨轮一致，谁触发都不劈叉上下文；cat_agent_state 承载，
 * 平台重启后同标识恢复）。工作区解析为<b>只读面</b>（主智能体永不读写沙箱代码——
 * 内核文件/shell 工具结构性关闭；PRD 写入走 savePrd 业务工具自带通道）。计量
 * （dims 终态口径 {@link UsageDims}，agentKind=main）与智能体资产（ask_user /
 * savePrd + 只读三件，按配置发放）同归本编排。</p>
 *
 * <p><b>车道语义（入口三分类归 {@link DispatchAppService}，本服务只收分岔后的
 * 轮）</b>：咨询零产物短路——{@link #answerInquiry} 同会话直答，不锚意见、不派
 * 更新 run、不受守卫（订单冻结 / 挂起问答只拦意见链）。挂起问答期间对话区输入
 * 即作答（CONTEXT.md「挂起问答」）：问答卡已呈现时前端直发作答通道；<b>渲染
 * 竞态窗口</b>（挂起事件已发、卡片未及呈现）里经派发口到达的咨询经
 * {@link #suspendedQuestions} 会合锚<b>转作答复续跑</b>——同一挂起 run 与工具面、
 * 咨询文本即答复文本，与作答通道完全同路（模型按协议消化非答复文本，可答可
 * 再问）；不炸对话、不出错误气泡。仅平台重启丢锚且挂起仍在的边角同步 409
 * PRJ_024 指路作答（run 无表丢 runId，无法代答）。</p>
 *
 * <p><b>链必达收口（#43，#101 生成无门自动发起）</b>：主智能体无派发权——生成与
 * 更新 run 的派发都不在模型手里，平台在意见轮落定后观测收口（无挂起问答）即按
 * 项目态自动派发：已生成派更新 run（交接物 = 用户意见原文 + 需求侧判定结果——
 * PRD 改没改、改了什么，见 {@link #dispatchOnTurnClose}）、未生成但已产出 PRD 派
 * 首次生成 run（{@link GenerationAppService#dispatchGenerationOnTurnClose}）、未
 * 产出 PRD 止于对话（访谈期常态）。判定结果从工具调用事实观测
 * （{@link PrdRevisionFacts}），不新增模型自报结论的面；守卫沿用（未产出 PRD
 * 止于对话、归档拒、在途排队合并/静默跳过，归 {@link IterationAppService} /
 * {@link GenerationAppService}）。</p>
 *
 * <p><b>事件桥与对话史（#89）</b>：过程事件经 {@link EventsAppService}（eventhub
 * 唯一 SSE 管道）发射，关联字段（projectId）逐事件注入——底座不解释、透传。发射
 * 失败护栏：单事件发射异常只记日志不断流（SSE 是「让 UI 活」的面，不承担正确性）；
 * 对话本身的成败以 error 事件 + 异常表达（会话执行器吞掉记日志，REST 快返回）。
 * 对话史落库的主写口在本编排：用户发言提交侧同步落（守卫全过后——失败上抛撤回
 * REST 面，「落库 ⟺ 说过」不漂移），智能体回复段与问答卡在轮落定点按对话序落
 * （{@link ConversationHistoryAppService.TurnRecorder}——挂起段文本先落、问答卡
 * 随后），收尾卡归编码 run 收口（{@code CoderRunAttempts}）。</p>
 */
@Service
@Slf4j
public class MainAgentAppService {

    /** 主智能体会话标识派生前缀（projectId → main-{projectId}，稳定绑定勿动）。 */
    public static final String SESSION_PREFIX = "main-";

    private final ProjectRepository projectRepository;
    private final AgentscopeAgentClient agentClient;
    private final AgentEventBridge eventBridge;
    private final AgentSessionExecutor sessionExecutor;
    private final ProjectKnowledgeAppService knowledgeAppService;
    private final OrderQueryAppService orderQueryAppService;
    private final IterationAppService iterationAppService;
    private final GenerationAppService generationAppService;
    private final PrdRevisionFacts prdRevisions;
    private final ConversationHistoryAppService conversationHistory;

    /**
     * 挂起交换的意见锚（sessionId → 交接物意见腿文本）：意见原文随会话任务落锚
     * （任务首行 put，随执行器串行——意见轮在途的新意见各自成轮，后写不覆盖在途
     * 轮的锚，#54），追问挂起期间的答复逐条并入（{@link #appendOpinionReply}）
     * ——收口派发的任务即锚的终值；收口即消费，轮炸即清（失败不留锚，重提即
     * 兜底）。进程内态，重启丢锚（续跑收口降级为仅末条答复，实质由 PRD 承载）。
     */
    private final Map<String, String> opinionExchanges = new ConcurrentHashMap<>();

    /**
     * 挂起问答的会合锚（sessionId → 本进程内最近一次 ask_user 挂起事实）：轮落定
     * 点维护（{@link #opinionTurn} 的 converse 与 {@link #answerQuestion} 的 resume
     * 返回处——问答挂起即落锚、收口或再挂起即覆盖/清除，随会话执行器串行无并发
     * 写）。用途：答询轮撞挂起（渲染竞态窗口）转作答复续跑——锚携带挂起 run 与
     * engineRef（run 无表，重启即丢——丢锚回落 409 指路口径）。
     */
    private final Map<String, SuspendedQuestion> suspendedQuestions = new ConcurrentHashMap<>();

    /** 一次 ask_user 挂起的会合事实（转答复续跑的入参重建源）。 */
    private record SuspendedQuestion(String runId, AgentSuspension suspension) {
    }

    public MainAgentAppService(ProjectRepository projectRepository,
            AgentscopeAgentClient agentClient, AgentEventBridge eventBridge,
            AgentSessionExecutor sessionExecutor, ProjectKnowledgeAppService knowledgeAppService,
            OrderQueryAppService orderQueryAppService, IterationAppService iterationAppService,
            GenerationAppService generationAppService, PrdRevisionFacts prdRevisions,
            ConversationHistoryAppService conversationHistory) {
        this.projectRepository = projectRepository;
        this.agentClient = agentClient;
        this.eventBridge = eventBridge;
        this.sessionExecutor = sessionExecutor;
        this.knowledgeAppService = knowledgeAppService;
        this.orderQueryAppService = orderQueryAppService;
        this.iterationAppService = iterationAppService;
        this.generationAppService = generationAppService;
        this.prdRevisions = prdRevisions;
        this.conversationHistory = conversationHistory;
    }

    /**
     * 主智能体会话建立轮（建项目自动开场专用）：绑定 {@code main-{projectId}}
     * 之时做知识命中注入——query = 初始需求原文，命中块落会话缓存并接 system prompt
     * 尾部（知识是背景非指令）；检索失败降级为空注入，访谈照常开始（#5 决议①）。
     * 一次切入一次注入：后续轮（{@link #runOpinionTurn} / {@link #answerInquiry}）
     * 与问答续跑（{@link #answerQuestion}）复用同一注入块，不重检索不重追加。
     *
     * @throws ApplicationException PRJ_001 项目不存在；守卫组同 {@link #runOpinionTurn}
     *                              （自动开场仅 PRJ_001 可实际发生）
     */
    public MainAgentRun startConversation(Long projectId, String requirement) {
        knowledgeAppService.establishSessionInjection(projectId, requirement);
        return opinionTurn(projectId, requirement, AnnotationAttachment.NONE);
    }

    /**
     * 派发入口全局守卫（#51 守卫后移）：对话区开着的最低门槛——项目存在 / 未归档
     * （归档 = 对话区物理关闭，咨询与兜底也停）。订单冻结（ORD_006）与挂起问答
     * （PRJ_024）只拦意见链：意见分岔自带同款守卫（{@link #opinionTurn}，防御
     * 纵深），咨询与兜底随时可答（CONTEXT.md「派发」）。已知代价：被拒意见先烧
     * 一次 flash 分类调用（秒级轻调用，接受）。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_013 项目已归档（对话区关闭）
     */
    public Project requireDispatchableProject(Long projectId) {
        Project project = requireProject(projectId);
        if (project.getArchivedAt() != null) {
            throw new ApplicationException(ProjectMessage.PROJECT_ALREADY_ARCHIVED);
        }
        return project;
    }

    /**
     * 跑一轮意见受理（意见链分岔，prompt 即用户侧输入）：会话执行器异步提交即
     * 返回（runId 随响应回，过程事件经 SSE；失败经 error 事件表达不炸调用方）。
     * system prompt = 主智能体配置 + 会话注入块（未建立/空注入/重启后 = 裸配置）。
     * 轮收口（无挂起问答且项目已生成）由平台观测自动派更新 run。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_013 项目已归档（对话区关闭）；
     *                              ORD_006 订单处理中（下单即冻结迭代，取消即解冻）；
     *                              PRJ_024 挂起问答待答（同步 409 指路作答，#40 / ADR-0005）
     */
    public MainAgentRun runOpinionTurn(Long projectId, String prompt,
            List<AnnotationAttachment> attachments) {
        return opinionTurn(projectId, prompt, attachments);
    }

    /** 无圈注附件的意见轮（纯文字发言——#97 之前与测试既有口径）。 */
    public MainAgentRun runOpinionTurn(Long projectId, String prompt) {
        return runOpinionTurn(projectId, prompt, AnnotationAttachment.NONE);
    }

    /**
     * 应答一条咨询（咨询链分岔，零产物短路，#47 语义随 #86 并轨单会话）：同一
     * {@code main-{projectId}} 会话直答——不锚意见、不派更新 run、不受订单冻结与
     * 挂起问答守卫（只拦意见链）。异步提交（runId 随响应回，回答经 SSE 到达）。
     * 项目事实由派发入口守卫后的聚合携带（owner / 工作区寻址不入前端信）。
     *
     * <p><b>渲染竞态收敛</b>：挂起问答期间对话区输入即作答——问答卡已呈现时前端
     * 直发作答通道；卡片未及呈现的窗口里经派发口到达的咨询经 {@link
     * #suspendedQuestions} 转作答复续跑（同挂起 run 与工具面，咨询文本即答复文本）
     * ——同一输入无论卡片渲染快慢行为一致，不出错误气泡。丢锚重启边角（挂起仍在
     * 但 runId 已失）同步 409 PRJ_024 指路作答。</p>
     */
    public MainAgentRun answerInquiry(Project project, String question,
            List<AnnotationAttachment> attachments) {
        Long projectId = project.getId();
        String sessionId = sessionIdOf(projectId);
        // 圈注随咨询同句发送：渲染进答复续跑/答询 prompt（结构化定位喂主智能体）
        String annotated = question + AnnotationPrompt.renderSuffix(attachments);
        SuspendedQuestion pending = suspendedQuestions.get(sessionId);
        if (pending != null) {
            MainAgentAppService.log.info("[main] 项目 {} 答询撞挂起问答（渲染竞态窗口），转作答复续跑（runId={}）",
                    projectId, pending.runId());
            answerQuestion(projectId, pending.runId(), pending.suspension().engineRef(),
                    pending.suspension().toolCalls(), annotated);
            return new MainAgentRun(pending.runId());
        }
        if (agentClient.hasAskingToolCall(project.ownerUserId(), sessionId)) {
            // 丢锚重启边角：挂起仍在但本进程没有挂起事实（runId 已失，无法代答）
            // ——同步 409 指路作答，好过异步错误气泡
            throw new ApplicationException(ProjectMessage.QUESTION_PENDING);
        }
        String runId = EventsAppService.newRunId();
        conversationHistory.recordUserUtterance(projectId, runId, question, attachments);
        AgentCommand command = mainCommand(project, runId, annotated);
        // 零产物：仅对话（答询协议在主智能体配置内——查证只读工具 + 据实作答）
        sessionExecutor.submit(sessionId, () -> {
            ConversationHistoryAppService.TurnRecorder recorder =
                    conversationHistory.recorder(projectId, eventBridge.sink(projectId));
            AgentReply reply = agentClient.converse(command, recorder);
            recorder.settle(runId, reply);
        });
        return new MainAgentRun(runId);
    }

    /** 无圈注附件的答询轮（纯文字咨询——#97 之前与测试既有口径）。 */
    public MainAgentRun answerInquiry(Project project, String question) {
        return answerInquiry(project, question, AnnotationAttachment.NONE);
    }

    /**
     * 问答答复续跑（ask_user 挂起的恢复）：挂起轮的 runId/engineRef 与待确认工具
     * 清单（question-raised 事件 data.toolCalls 形状，前端问答卡回传）+ 用户答复 →
     * ConfirmResult 批复续跑（续跑续在同一 run 上收口，事件序含答复后的下一问或
     * 收口）。恢复私货（配置/owner/工作区/计量）从项目侧事实重建，不信前端。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_013 项目已归档（对话区关闭）；
     *                              ORD_006 订单处理中（下单即冻结迭代，取消即解冻）
     */
    public void answerQuestion(Long projectId, String runId, String replyId,
            List<Map<String, Object>> pendingToolCalls, String answerText) {
        Project project = requireUpdatableProject(projectId);
        String sessionId = sessionIdOf(projectId);
        conversationHistory.recordAnswer(projectId, runId, answerText);

        AgentResume resume = new AgentResume(
                runId,
                sessionId,
                project.ownerUserId(),
                Long.toString(project.getWorkspaceId()),
                AgentProfile.MAIN.chatModelString(),
                AgentProfile.MAIN.systemPrompt() + knowledgeAppService.sessionTailOf(projectId),
                replyId,
                pendingToolCalls.stream()
                        .map(toolCall -> AgentscopeAgentClient.answeredToolCall(toolCall, answerText))
                        .toList(),
                answerText,
                usageContextOf(projectId, sessionId),
                AgentProfile.MAIN.key(),
                /* workspaceReadOnly= */ true);
        appendOpinionReply(sessionId, answerText);
        ConversationHistoryAppService.TurnRecorder recorder =
                conversationHistory.recorder(projectId, eventBridge.sink(projectId));
        sessionExecutor.submit(sessionId, () -> {
            try {
                AgentReply reply = agentClient.resume(resume, recorder);
                settleSuspendedQuestion(sessionId, runId, reply);
                recorder.settle(runId, reply);
            }
            catch (RuntimeException e) {
                // 续跑失败同轮失败口径（#83 起 resume 失败上抛）：清锚不派发——error
                // 事件已由 resume 内发出，用户重提即兜底（不自动重试）
                opinionExchanges.remove(sessionId);
                suspendedQuestions.remove(sessionId);
                throw e;
            }
            dispatchOnTurnClose(projectId, sessionId, runId);
        });
    }

    /** 一轮主智能体对话的运行标识（前端挂智能体事件 ?runId= 的锚）。 */
    public record MainAgentRun(String runId) {
    }

    // ---------- 内部 ----------

    private MainAgentRun opinionTurn(Long projectId, String prompt,
            List<AnnotationAttachment> attachments) {
        Project project = requireUpdatableProject(projectId);
        String sessionId = sessionIdOf(projectId);
        requireNoPendingQuestion(project, sessionId);

        String runId = EventsAppService.newRunId();
        // 圈注随意见同句发送：渲染进主智能体 prompt 与交接物（意见原文）——结构化
        // 定位随链下到更新 run，执行体知道改哪里
        String annotated = prompt + AnnotationPrompt.renderSuffix(attachments);
        // 对话史落库（#89）：提交守卫全过后同步落用户发言（失败上抛撤回 REST 面——
        // 「落库 ⟺ 说过」不漂移）；智能体回复段在轮落定点写（见 recordTurnReply）。
        // attachments 原始 JSON 落库供刷新回显重建圈注 chip
        conversationHistory.recordUserUtterance(projectId, runId, prompt, attachments);
        // 受理动作卡（#87）：迭代期意见轮（受理轮）开场即发受理事件——意见已接住、
        // 主智能体正在受理（追问或改 PRD 的过程呈现位，衔接轮收口自动派的更新 run
        // 工作消息）。守卫全过才发（拒绝即零事件）；访谈期意见轮是纯追问轮、咨询
        // 轮走 {@link #answerInquiry}，场景矩阵均无卡
        if (project.isGenerated()) {
            eventBridge.emitAcceptanceStarted(projectId, runId);
        }
        AgentCommand command = mainCommand(project, runId, annotated);
        sessionExecutor.submit(sessionId, () -> {
            // 排队成轮（#54）：锚随任务落（同会话 FIFO——后发意见的 put 排在本轮
            // 收口之后，不覆盖在途轮的锚）：意见轮在途连发的意见各自成轮、各自
            // 收口派发，撞在途更新 run 走排队合并
            opinionExchanges.put(sessionId, annotated);
            // 本轮需求侧判定从零起算（随会话执行器串行——上一轮收口消费在前，
            // 不会被本轮起跑插队 wipe）：炸轮滞留/访谈期的 savePrd 事实残留不进
            // 本轮交接物（意见锚无此滞留——失败即清，见下）
            prdRevisions.clear(Long.toString(project.getWorkspaceId()));
            try {
                ConversationHistoryAppService.TurnRecorder recorder =
                        conversationHistory.recorder(projectId, eventBridge.sink(projectId));
                AgentReply reply = agentClient.converse(command, recorder);
                settleSuspendedQuestion(sessionId, runId, reply);
                recorder.settle(runId, reply);
            }
            catch (RuntimeException e) {
                // 失败即清锚（#54，对齐「收口即消费」）：炸轮不留锚——重提即兜底，
                // 不自动重试；error 事件已由 converse 内发出（异常上抛由会话执行器吞）
                opinionExchanges.remove(sessionId);
                suspendedQuestions.remove(sessionId);
                throw e;
            }
            dispatchOnTurnClose(projectId, sessionId, runId);
        });
        return new MainAgentRun(runId);
    }

    /**
     * 轮落定点的挂起问答会合锚维护：问答挂起（软终点）即落锚（渲染竞态窗口里
     * 答询轮转作答复续跑的入参源）；权限类挂起/正常收口即清锚（无可答之问）。
     */
    private void settleSuspendedQuestion(String sessionId, String runId, AgentReply reply) {
        if (reply.suspension() != null && !reply.suspension().permission()) {
            suspendedQuestions.put(sessionId, new SuspendedQuestion(runId, reply.suspension()));
        }
        else {
            suspendedQuestions.remove(sessionId);
        }
    }

    /** 主智能体对话命令（意见轮与咨询轮同构：同会话、同配置、同只读面）。 */
    private AgentCommand mainCommand(Project project, String runId, String prompt) {
        Long projectId = project.getId();
        String sessionId = sessionIdOf(projectId);
        return new AgentCommand(
                runId,
                prompt,
                AgentProfile.MAIN.systemPrompt() + knowledgeAppService.sessionTailOf(projectId),
                AgentProfile.MAIN.chatModelString(),
                sessionId,
                project.ownerUserId(),
                usageContextOf(projectId, sessionId),
                Long.toString(project.getWorkspaceId()),
                correlationOf(projectId),
                null,
                AgentProfile.MAIN.key(),
                /* workspaceReadOnly= */ true);
    }

    /** 会话标识派生（projectId → main-{projectId} 稳定绑定）。 */
    private static String sessionIdOf(Long projectId) {
        return SESSION_PREFIX + projectId;
    }

    /**
     * 链必达收口观测（#43，#101 生成无门自动发起）：意见轮落定（对话轮收口或
     * 问答续跑收口）后观测链的走向——会话有挂起问答 = 本轮未收口（答复后续跑再判，
     * 意见锚保留）；收口前归档 = 竞态守卫，静默不派。落定即按项目态自动派发：
     * 已生成派更新 run（交接物 = {@link #opinionExchanges} 中的意见原文及追问答复
     * + {@link #prdRevisions} 中的 PRD 修订事实——本轮 savePrd 调用的 summary 终值，
     * 无调用事实即 null「未修订」）；未生成但已产出 PRD 派首次生成 run（生成无门，
     * 意见锚与修订事实同「收口即消费」清掉——生成读的是工作区 PRD 正本，不进
     * 交接物）；未产出 PRD 止于对话（访谈期常态）。主智能体无派发权：模型存没存
     * PRD、调没调任何工具都不影响派发——链的收口在平台代码。派发失败不炸对话
     * 轨道、不恢复意见锚（收口即消费语义保持）、不自动重试——用户重提即兜底。
     */
    private void dispatchOnTurnClose(Long projectId, String sessionId, String runId) {
        try {
            Project project = projectRepository.findById(projectId).orElse(null);
            if (project == null
                    || agentClient.hasAskingToolCall(project.ownerUserId(), sessionId)) {
                return;
            }
            String workspaceId = Long.toString(project.getWorkspaceId());
            if (project.getArchivedAt() != null) {
                clearTurnAnchors(sessionId, workspaceId); // 归档竞态守卫：静默不派
                return;
            }
            if (project.getGeneratedAt() == null) {
                // 未生成：PRD 已产出即平台自动派首次生成（生成无门，#101）；未产出
                // PRD 静默止于对话（访谈期常态：生成前意见链终点）
                clearTurnAnchors(sessionId, workspaceId);
                if (project.getPrdProducedAt() != null) {
                    GenerationAppService.GenerationRun run =
                            generationAppService.dispatchGenerationOnTurnClose(projectId);
                    if (run != null) {
                        log.info("[main-close] 项目 {} 意见轮收口，平台自动派首次生成 run（{}）",
                                projectId, run.runId());
                    }
                }
                return;
            }
            String task = opinionExchanges.remove(sessionId);
            if (task == null || task.isBlank()) {
                return; // 锚缺失（任务内落锚先于收口，同任务序——进程内理论不可达）——防御不派
            }
            String prdRevisionSummary = prdRevisions.consume(workspaceId);
            IterationAppService.FixDispatch dispatch =
                    iterationAppService.startFixRun(projectId, task, prdRevisionSummary);
            log.info("[main-close] 项目 {} 意见轮收口，平台自动派更新 run（{}，{}）",
                    projectId, dispatch.queued() ? "排队下一轮" : "起跑",
                    prdRevisionSummary != null ? "PRD 已修订" : "本轮无修订");
        }
        catch (RuntimeException e) {
            // 派发失败（#51 → #82 失败家族归位；#101 起同 catch 接生成与更新两路）：
            // 意见锚已消费（不恢复）、不自动重试，用户重提即兜底——失败信号归 error
            // 事件（dispatch-failed 阶段族已退役），对话面如实呈现不静默
            try {
                eventBridge.emitError(projectId, runId, "意见派发失败，请重新发送");
            }
            catch (RuntimeException emitFailure) {
                log.warn("[main-close] 项目 {} 派发失败事件发射失败：{}", projectId,
                        emitFailure.toString());
            }
            log.warn("[main-close] 项目 {} 收口自动派发失败（用户重提即兜底）：{}",
                    projectId, e.toString());
        }
    }

    /** 收口即消费：意见锚与修订事实同口径清掉（不留过轮）——归档 / 未产出 PRD /
     * 未生成派生成前的三条静默路径共用（生成读的是工作区 PRD 正本，不进交接物）。 */
    private void clearTurnAnchors(String sessionId, String workspaceId) {
        opinionExchanges.remove(sessionId);
        prdRevisions.clear(workspaceId);
    }

    /** 追问答复并入意见锚（挂起交换期间累积——多轮追问的答复都进交接物）；
     * 无锚（重启丢锚的续跑）时以答复自立。 */
    private void appendOpinionReply(String sessionId, String reply) {
        opinionExchanges.compute(sessionId, (key, opinion) -> opinion == null
                ? reply : opinion + "；用户对追问的答复：" + reply);
    }

    private static Map<String, Object> correlationOf(Long projectId) {
        return Map.of(EventsAppService.PROJECT_FIELD, projectId.toString());
    }

    private static UsageContext usageContextOf(Long projectId, String sessionId) {
        return new UsageContext(Long.toString(projectId),
                UsageDims.of(projectId, UsageDims.kindOf(AgentProfile.MAIN), sessionId));
    }

    private Project requireProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
    }

    /** 意见轮守卫：归档即对话区关闭（只读终态）；未终结订单在即冻结迭代
     * （下单后的意见不再受理，取消订单即解冻回迭代态）——新发言与作答一并拒绝。 */
    private Project requireUpdatableProject(Long projectId) {
        Project project = requireProject(projectId);
        if (project.getArchivedAt() != null) {
            throw new ApplicationException(ProjectMessage.PROJECT_ALREADY_ARCHIVED);
        }
        orderQueryAppService.requireNoActiveOrder(projectId);
        return project;
    }

    /** 挂起问答守卫（#40 / ADR-0005）：会话存在挂起问答（ASKING 态工具块）时
     * 新输入不盲提交——引擎必拒且 REST 已返 200 只见异步 error 事件；同步 409
     * 指路作答。作答（resume）在途、ASKING 尚未清库的偶发拦截为已接受竞态边角。
     * 守卫先于命令提交，拒绝即零事件。 */
    private void requireNoPendingQuestion(Project project, String sessionId) {
        if (agentClient.hasAskingToolCall(project.ownerUserId(), sessionId)) {
            throw new ApplicationException(ProjectMessage.QUESTION_PENDING);
        }
    }
}
