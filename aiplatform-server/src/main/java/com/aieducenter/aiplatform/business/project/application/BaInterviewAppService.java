package com.aieducenter.aiplatform.business.project.application;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.agentscope.AgentCommand;
import com.aieducenter.aiplatform.base.agentscope.AgentResume;
import com.aieducenter.aiplatform.base.agentscope.AgentSessionExecutor;
import com.aieducenter.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.aieducenter.aiplatform.base.agentscope.UsageContext;
import com.aieducenter.aiplatform.base.eventhub.application.AgentStreamAppService;
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
 * 在 BA 回合落定后观测收口（无挂起问答且项目已生成）即自动派修正 run（交接任务
 * = 用户意见原文，见 {@link #dispatchFixOnTurnClose}）。判定结果从工具调用事实
 * 观测，不新增模型自报结论的面；守卫沿用（未生成止于 BA、归档拒、在途排队合并，
 * 归 {@link IterationAppService}）。</p>
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

    private final ProjectRepository projectRepository;
    private final AgentscopeAgentClient agentClient;
    private final AgentStreamBridge streamBridge;
    private final AgentSessionExecutor sessionExecutor;
    private final ProjectKnowledgeAppService knowledgeAppService;
    private final OrderQueryAppService orderQueryAppService;
    private final IterationAppService iterationAppService;

    /**
     * 挂起交换的意见锚（sessionId → 交接任务文本）：意见原文开场落锚，追问挂起
     * 期间的答复逐条并入（{@link #appendOpinionReply}）——收口派发的任务即锚的
     * 终值；收口即消费。进程内态，重启丢锚（续跑收口降级为仅末条答复，实质由
     * PRD 承载）。
     */
    private final Map<String, String> opinionExchanges = new ConcurrentHashMap<>();

    public BaInterviewAppService(ProjectRepository projectRepository,
            AgentscopeAgentClient agentClient, AgentStreamBridge streamBridge,
            AgentSessionExecutor sessionExecutor, ProjectKnowledgeAppService knowledgeAppService,
            OrderQueryAppService orderQueryAppService, IterationAppService iterationAppService) {
        this.projectRepository = projectRepository;
        this.agentClient = agentClient;
        this.streamBridge = streamBridge;
        this.sessionExecutor = sessionExecutor;
        this.knowledgeAppService = knowledgeAppService;
        this.orderQueryAppService = orderQueryAppService;
        this.iterationAppService = iterationAppService;
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
     * 派发入口守卫（#47 三分类的公共前置）：指令区输入无论走哪个分支（意见 /
     * 咨询 / 兜底）都先过同一组守卫——项目存在 / 未归档（指令区关闭）/ 无未终结
     * 订单（下单即冻结）/ 无挂起问答（同步 409 指路作答）。归
     * {@link DispatchAppService} 的 REST 路径同步调用（错误码语义留在响应里），
     * 各分支方法自带的守卫不动（防御纵深）。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_013 项目已归档；
     *                              ORD_006 订单处理中；PRJ_024 挂起问答待答
     */
    public Project requireDispatchableProject(Long projectId) {
        Project project = requireProject(projectId);
        if (project.getArchivedAt() != null) {
            throw new ApplicationException(ProjectMessage.PROJECT_ALREADY_ARCHIVED);
        }
        orderQueryAppService.requireNoActiveOrder(projectId);
        requireNoPendingQuestion(project, SESSION_PREFIX + projectId);
        return project;
    }

    /**
     * 跑一轮 BA 访谈（指令区发言，prompt 即用户侧输入）：会话执行器异步提交即
     * 返回（runId 随响应回，过程帧经 SSE；失败经 error 帧表达不炸调用方）。
     * system prompt = 角色卡 + 会话注入块（未建立/空注入/重启后 = 裸角色卡）。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_013 项目已归档（指令区关闭）；
 *                              ORD_006 订单处理中（下单即冻结迭代，取消即解冻）；
 *                              PRJ_024 挂起问答待答（同步 409 指路作答，#40 / ADR-0004）
     */
    public InterviewRun runInterviewTurn(Long projectId, String prompt) {
        return turn(projectId, prompt);
    }

    private InterviewRun turn(Long projectId, String prompt) {
        Project project = requireInterviewableProject(projectId);
        RolePreset role = RolePreset.BA;
        String sessionId = SESSION_PREFIX + projectId;
        requireNoPendingQuestion(project, sessionId);
        opinionExchanges.put(sessionId, prompt);

        String runId = AgentStreamAppService.newRunId();
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
            agentClient.converse(command, streamBridge.sink(projectId));
            dispatchFixOnTurnClose(projectId, sessionId);
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
        sessionExecutor.submit(sessionId, () -> {
            agentClient.resume(resume, streamBridge.sink(projectId));
            dispatchFixOnTurnClose(projectId, sessionId);
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
     * 静默不派。三者皆过即平台自动派修正 run（交接任务 = {@link #opinionExchanges}
     * 中的意见原文及追问答复）。BA 无派发权：模型存没存 PRD、调没调任何工具都
     * 不影响派发——链的收口在平台代码。观测或派发失败只记日志（不炸 BA 轨道；
     * 意见不丢，用户重提即兜底）。
     */
    private void dispatchFixOnTurnClose(Long projectId, String sessionId) {
        try {
            Project project = projectRepository.findById(projectId).orElse(null);
            if (project == null
                    || agentClient.hasAskingToolCall(ownerUserIdOf(project), sessionId)) {
                return;
            }
            if (project.getGeneratedAt() == null || project.getArchivedAt() != null) {
                opinionExchanges.remove(sessionId); // 收口即消费：锚不留过轮
                return;
            }
            String task = opinionExchanges.remove(sessionId);
            if (task == null || task.isBlank()) {
                return; // 锚缺失（put 先于收口，进程内理论不可达）——防御不派
            }
            iterationAppService.startFixRun(projectId, task);
            log.info("[ba-close] 项目 {} BA 回合收口，平台自动派修正 run", projectId);
        }
        catch (RuntimeException e) {
            log.warn("[ba-close] 项目 {} 修正 run 自动派发失败（用户重提即兜底）：{}",
                    projectId, e.toString());
        }
    }

    /** 追问答复并入意见锚（挂起交换期间累积——多轮追问的答复都进交接任务）；
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

    /** 挂起问答守卫（#40 / ADR-0004）：会话存在挂起问答（ASKING 态工具块）时
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
