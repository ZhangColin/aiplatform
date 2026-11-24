package com.semgravio.aiplatform.business.project.application;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.base.agentscope.AgentCommand;
import com.semgravio.aiplatform.base.agentscope.AgentReply;
import com.semgravio.aiplatform.base.agentscope.AgentResume;
import com.semgravio.aiplatform.base.agentscope.AgentSessionExecutor;
import com.semgravio.aiplatform.base.agentscope.AgentSuspension;
import com.semgravio.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.semgravio.aiplatform.base.agentscope.ModelCandidateQueue;
import com.semgravio.aiplatform.base.agentscope.UsageContext;
import com.semgravio.aiplatform.base.eventhub.application.EventsAppService;
import com.semgravio.aiplatform.business.billing.application.SegmentSettlementAppService;
import com.semgravio.aiplatform.business.billing.domain.enums.SegmentType;
import com.semgravio.aiplatform.business.project.application.dto.command.MessageAttachment;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.error.ProjectMessage;
import com.semgravio.aiplatform.business.project.domain.model.AgentProfile;
import com.semgravio.aiplatform.business.project.domain.model.UsageDims;

import lombok.extern.slf4j.Slf4j;

/**
 * 对话面引擎（#622，ADR-0056 两平面）：系统生成后接上的<b>独立轻会话</b>——与首建
 * 访谈链（{@code main-{projectId}}）分开，用户看到的仍是一根连续线程（对话史按
 * 项目全量落库，会话切换对用户不可见）。
 *
 * <p><b>只做两件事</b>：①<b>答疑</b>——只读查工作区、PRD、项目事实后据实回答，
 * 不触发任何生成；②<b>澄清</b>——修改/需求说得不清楚时，用 ask_user 出带选项与
 * 推荐项的问答卡先问清（复用现有问答卡/作答通道）。工具面＝ask_user ＋文件清单／
 * 文件内容／项目事实只读三件（{@link AgentConfigAppService#DIALOG_TOOL_SPEC}
 * 收窄装配）——<b>零写工作区/编码工具</b>，本票不提供修改触发出口（修改交接归
 * #623，任务句与派发由平台在执行面完成）。SSE 复用 run-start/run-finish/
 * question-raised/error，零新增事件类型。
 *
 * <p><b>只读面口径（#622）</b>：文件内容放开 {@code .env} 与 seed/脚本（账号密码
 * 类问题查证），仍拒绝 {@code data/pg}、{@code node_modules}、{@code .git} 等
 * （{@code ProjectFiles.isDialogViewable} 单点）。
 *
 * <p><b>额度与计费</b>：对话面不带五问额度闸（{@code AskUserTool} 无闸形态）；
 * 问答卡不呈现「第 N 问」（前端按非访谈期不出徽标）。段结算照对话轮口径（付费
 * 项目收口结算一次、试用零扣减）——旧受理期的试用拦截/余额留存不接本面（商业面
 * 代码保留，归 #625 清场）。
 *
 * <p><b>挂起与会合锚</b>：对话面澄清提问挂起后，作答经作答通道回到
 * {@link #answerQuestion}（与首轮同会话、同工具面）；渲染竞态窗口里经发言口到达
 * 的输入经 {@link #suspendedQuestions} 转作答复续跑（同 {@code MainAgentAppService}
 * 口径）。进程内态，重启丢锚（挂起仍在则同步 409 指路作答）。
 */
@Service
@Slf4j
public class DialogAppService {

    /** 对话面会话标识派生前缀（projectId → dialog-{projectId} 稳定绑定）。 */
    public static final String SESSION_PREFIX = "dialog-";

    /**
     * 对话面工作协议（#622）：两职能——答疑（只读查证后据实作答）与澄清（不清楚
     * 先问，一次一问、2-4 选项、推荐项）。无写面、无派发、无额度；本票不承诺动手
     * 修改（修改交接归 #623）。
     */
    public static final String DIALOG_SYSTEM_PROMPT =
            "你是平台的对话面，负责系统生成之后的对话，只做两件事：答疑与澄清。\n"
                    + "【答疑】用户问项目情况（系统怎么用、某个功能在哪、后台地址、账号密码等"
                    + "事实问题）时，先查证再答：用 query_project_facts 查项目事实（含系统访问"
                    + "地址）、用 list_workspace_files 看工作区文件清单、用 read_workspace_file "
                    + "读具体文件内容（.env、说明文档、seed/脚本、需求文档 docs/PRD.md 等都可读）。"
                    + "只据实作答，查不到"
                    + "就明说不知道，绝不编造。答疑不改任何东西、不产出任何产物、不触发生成。\n"
                    + "【澄清】用户提出修改或需求、但说得不清楚时，先用 ask_user 提问澄清："
                    + "一次只问一个问题，给出 2-4 个候选选项，并在有把握时用 recommended 标出"
                    + "推荐项（让用户检查并改，而不是从零写答案）。答复到达后继续判断：仍不清楚"
                    + "就再问，直到能明确说出要改什么；拿不准宁可多问一句，不要猜着往下走。\n"
                    + "【边界】你没有任何写工作区或编码工具，也不发起系统修改或生成——工作区一切"
                    + "变更只经平台生成内核。本阶段只做答疑与澄清，不承诺马上动手修改。\n"
                    + "【术语】对话与回答里对用户定制的目标统一称「系统」，不用 Demo、原型、"
                    + "样品等字眼。\n"
                    + "全程使用中文。";

    private final AgentscopeAgentClient agentClient;
    private final AgentEventBridge eventBridge;
    private final AgentSessionExecutor sessionExecutor;
    private final AgentConfigAppService agentConfigs;
    private final ModelConfigAppService modelConfigs;
    private final ConversationHistoryAppService conversationHistory;
    private final MainAgentProperties mainAgentProperties;
    private final SegmentSettlementAppService segmentSettlement;

    /** 挂起问答的会合锚（sessionId → 最近一次 ask_user 挂起事实），同主智能体口径。 */
    private final Map<String, SuspendedQuestion> suspendedQuestions = new ConcurrentHashMap<>();

    /**
     * 对话面 run 登记（runId 集）：作答端点的路由判据之一——本进程内由对话面发起的
     * 挂起轮，作答回对话面会话（另一判据＝对话史问答卡的 sessionId，跨进程重启）。
     * 真收口/失败即移除；挂起期间保留至答复续跑收口。
     */
    private final Set<String> dialogRuns = ConcurrentHashMap.newKeySet();

    /** 一次 ask_user 挂起的会合事实（转答复续跑的入参重建源）。 */
    private record SuspendedQuestion(String runId, AgentSuspension suspension) {
    }

    public DialogAppService(AgentscopeAgentClient agentClient, AgentEventBridge eventBridge,
            AgentSessionExecutor sessionExecutor, AgentConfigAppService agentConfigs,
            ModelConfigAppService modelConfigs,
            ConversationHistoryAppService conversationHistory,
            MainAgentProperties mainAgentProperties,
            SegmentSettlementAppService segmentSettlement) {
        this.agentClient = agentClient;
        this.eventBridge = eventBridge;
        this.sessionExecutor = sessionExecutor;
        this.agentConfigs = agentConfigs;
        this.modelConfigs = modelConfigs;
        this.conversationHistory = conversationHistory;
        this.mainAgentProperties = mainAgentProperties;
        this.segmentSettlement = segmentSettlement;
    }

    /**
     * 对话面一轮发言（答疑/澄清的入口）：先落用户发言（与主智能体同口径——守卫
     * 全过后同步落，失败上抛撤回 REST 面），再异步提交对话面会话的一轮。零派发：
     * 不锚意见、不派更新 run、不触发生成。挂起问答在场时转作答通道（渲染竞态
     * 窗口；卡片已呈现时前端走作答端点，同路）。
     *
     * @return 本轮 runId（前端挂智能体事件 ?runId= 的锚）
     */
    public String turn(Project project, String prompt, List<MessageAttachment> attachments) {
        Long projectId = project.getId();
        String sessionId = sessionIdOf(projectId);
        String annotated = prompt + AttachmentPrompt.renderSuffix(attachments);
        SuspendedQuestion pending = suspendedQuestions.get(sessionId);
        if (pending != null) {
            log.info("[dialog] 项目 {} 对话面发言撞挂起问答（渲染竞态窗口），转作答复续跑（runId={}）",
                    projectId, pending.runId());
            answerQuestion(project, pending.runId(), pending.suspension().engineRef(),
                    pending.suspension().toolCalls(), annotated);
            return pending.runId();
        }
        if (agentClient.hasAskingToolCall(project.ownerUserId(), sessionId)) {
            // 丢锚重启边角：挂起仍在但本进程无挂起事实（runId 已失）——409 指路作答
            throw new ApplicationException(ProjectMessage.QUESTION_PENDING);
        }
        String runId = EventsAppService.newRunId();
        dialogRuns.add(runId);
        conversationHistory.recordUserUtterance(projectId, runId, prompt, attachments);
        AgentCommand command = command(project, runId, annotated);
        sessionExecutor.submit(sessionId, () -> {
            ConversationHistoryAppService.TurnRecorder recorder =
                    conversationHistory.recorder(projectId, eventBridge.sink(project));
            AgentReply reply;
            try {
                reply = agentClient.converse(command, recorder);
                settleSuspendedQuestion(sessionId, runId, reply);
                recorder.settle(runId, reply);
            }
            catch (RuntimeException e) {
                dialogRuns.remove(runId);
                settleTurn(projectId, runId); // 失败也扣（用量已实耗，同主智能体口径）
                throw e;
            }
            if (reply.suspension() == null) {
                dialogRuns.remove(runId);
                settleTurn(projectId, runId); // 真收口结算；挂起轮等答复续跑再结算
            }
        });
        return runId;
    }

    /**
     * 对话面问答作答续跑（ask_user 挂起的恢复）：挂起轮的 runId/replyId/待确认工具
     * 清单 + 用户答复 → 续跑同一对话面会话。恢复私货（配置/owner/工作区/计量）从
     * 项目侧事实重建，不信前端；工具面仍为对话面只读集会。零派发。
     */
    public void answerQuestion(Project project, String runId, String replyId,
            List<Map<String, Object>> pendingToolCalls, String answerText) {
        Long projectId = project.getId();
        String sessionId = sessionIdOf(projectId);
        dialogRuns.add(runId); // 跨进程重启（登记丢失）时由调用侧按对话史 sessionId 归位
        conversationHistory.recordAnswer(projectId, runId, answerText);
        AgentConfigAppService.EffectiveConfig main = agentConfigs.effectiveOf(AgentProfile.MAIN);
        ModelCandidateQueue model = modelConfigs.resolveQueue(main.modelConfigKey());
        AgentResume resume = new AgentResume(
                runId,
                sessionId,
                project.ownerUserId(),
                Long.toString(project.getWorkspaceId()),
                model.modelString(),
                model.channel(),
                model, // #557 续跑与首轮同队列（failover 装配面）
                DIALOG_SYSTEM_PROMPT,
                replyId,
                pendingToolCalls.stream()
                        .map(toolCall -> AgentscopeAgentClient.answeredToolCall(toolCall, answerText))
                        .toList(),
                answerText,
                usageContextOf(projectId, sessionId),
                AgentProfile.MAIN.key(),
                /* workspaceReadOnly= */ true,
                AgentConfigAppService.DIALOG_TOOL_SPEC,
                mainAgentProperties.getSilenceTimeout(),
                mainAgentProperties.getToolSilenceTimeout());
        ConversationHistoryAppService.TurnRecorder recorder =
                conversationHistory.recorder(projectId, eventBridge.sink(project));
        sessionExecutor.submit(sessionId, () -> {
            AgentReply reply;
            try {
                reply = agentClient.resume(resume, recorder);
                settleSuspendedQuestion(sessionId, runId, reply);
                recorder.settle(runId, reply);
            }
            catch (RuntimeException e) {
                dialogRuns.remove(runId);
                settleTurn(projectId, runId); // 失败也扣（同主智能体口径）
                throw e;
            }
            if (reply.suspension() == null) {
                dialogRuns.remove(runId);
                settleTurn(projectId, runId);
            }
        });
    }

    /** 作答路由判据：该 run 是否由对话面发起/挂起（本进程内登记；跨重启由对话史兜底）。 */
    public boolean ownsRun(String runId) {
        return runId != null && dialogRuns.contains(runId);
    }

    /** 对话面会话标识派生（projectId → dialog-{projectId} 稳定绑定）。 */
    public static String sessionIdOf(Long projectId) {
        return SESSION_PREFIX + projectId;
    }

    // ---------- 内部 ----------

    /** 对话面命令：主智能体配置的模型档与通道，系统提示与工具面换对话面形态。 */
    private AgentCommand command(Project project, String runId, String prompt) {
        Long projectId = project.getId();
        String sessionId = sessionIdOf(projectId);
        AgentConfigAppService.EffectiveConfig main = agentConfigs.effectiveOf(AgentProfile.MAIN);
        ModelCandidateQueue model = modelConfigs.resolveQueue(main.modelConfigKey());
        return new AgentCommand(
                runId,
                prompt,
                DIALOG_SYSTEM_PROMPT,
                model.modelString(),
                model.channel(),
                sessionId,
                project.ownerUserId(),
                usageContextOf(projectId, sessionId),
                Long.toString(project.getWorkspaceId()),
                correlationOf(projectId),
                /* timeout= */ null, // 活超时口径同主智能体对话轮
                mainAgentProperties.getSilenceTimeout(),
                mainAgentProperties.getToolSilenceTimeout(),
                AgentProfile.MAIN.key(), // 同槽身份（前端对话面锚定/计量 agentKind）
                /* workspaceReadOnly= */ true,
                /* heading= */ null,
                AgentConfigAppService.DIALOG_TOOL_SPEC,
                model);
    }

    /** 挂起问答会合锚维护（挂起即落锚、收口或再挂起即覆盖/清除）。 */
    private void settleSuspendedQuestion(String sessionId, String runId, AgentReply reply) {
        if (reply.suspension() != null) {
            suspendedQuestions.put(sessionId, new SuspendedQuestion(runId, reply.suspension()));
        }
        else {
            suspendedQuestions.remove(sessionId);
        }
    }

    /** 段结算（#537 段收口结算）：对话面车段型＝主智能体轮；异常只记日志不反噬轨道。 */
    private void settleTurn(Long projectId, String runId) {
        try {
            segmentSettlement.settleSegment(projectId, SegmentType.MAIN_TURN, runId,
                    List.of(runId));
        }
        catch (RuntimeException e) {
            log.warn("[dialog-settle] 项目 {} 段结算失败（不反噬对话轨道）：{}", projectId,
                    e.toString());
        }
    }

    private static Map<String, Object> correlationOf(Long projectId) {
        return Map.of(EventsAppService.PROJECT_FIELD, projectId.toString());
    }

    private static UsageContext usageContextOf(Long projectId, String sessionId) {
        return new UsageContext(Long.toString(projectId),
                UsageDims.of(projectId, UsageDims.kindOf(AgentProfile.MAIN), sessionId));
    }
}
