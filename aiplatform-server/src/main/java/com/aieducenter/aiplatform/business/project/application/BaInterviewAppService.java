package com.aieducenter.aiplatform.business.project.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.RolePreset;
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
 * 平台重启后同标识恢复）。计量（role=BA 维度）与智能体资产（ask_user /
 * savePrd 工具集）同归 BA 编排。</p>
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
    private final AgentStreamAppService streamAppService;
    private final AgentSessionExecutor sessionExecutor;
    private final ProjectKnowledgeAppService knowledgeAppService;

    public BaInterviewAppService(ProjectRepository projectRepository,
            AgentscopeAgentClient agentClient, AgentStreamAppService streamAppService,
            AgentSessionExecutor sessionExecutor, ProjectKnowledgeAppService knowledgeAppService) {
        this.projectRepository = projectRepository;
        this.agentClient = agentClient;
        this.streamAppService = streamAppService;
        this.sessionExecutor = sessionExecutor;
        this.knowledgeAppService = knowledgeAppService;
    }

    /**
     * BA 会话建立轮（建项目自动开场专用）：绑定 {@code ba-{projectId}} 之时做
     * 知识命中注入——query = 初始需求原文，命中块落会话缓存并接 system prompt
     * 尾部（知识是背景非指令）；检索失败降级为空注入，访谈照常开始（#5 决议①）。
     * 一次切入一次注入：后续轮（{@link #runInterviewTurn}）与问答续跑
     * （{@link #answerQuestion}）复用同一注入块，不重检索不重追加。
     *
     * @throws ApplicationException PRJ_001 项目不存在
     */
    public InterviewRun startInterview(Long projectId, String requirement) {
        knowledgeAppService.establishSessionInjection(projectId, requirement);
        return turn(projectId, requirement);
    }

    /**
     * 跑一轮 BA 访谈（指令区发言，prompt 即用户侧输入）：会话执行器异步提交即
     * 返回（runId 随响应回，过程帧经 SSE；失败经 error 帧表达不炸调用方）。
     * system prompt = 角色卡 + 会话注入块（未建立/空注入/重启后 = 裸角色卡）。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_013 项目已归档（指令区关闭）
     */
    public InterviewRun runInterviewTurn(Long projectId, String prompt) {
        return turn(projectId, prompt);
    }

    private InterviewRun turn(Long projectId, String prompt) {
        Project project = requireInterviewableProject(projectId);
        RolePreset role = RolePreset.BA;
        String sessionId = SESSION_PREFIX + projectId;
        Map<String, Object> correlation = correlationOf(projectId);

        String runId = AgentStreamAppService.newRunId();
        emitRoleAssigned(projectId, runId, role);
        AgentCommand command = new AgentCommand(
                runId,
                prompt,
                role.systemPrompt() + knowledgeAppService.sessionTailOf(projectId),
                role.chatModelString(),
                sessionId,
                project.getOwnerAccountId() != null
                        ? project.getOwnerAccountId().toString() : null,
                usageContextOf(projectId, role),
                Long.toString(project.getWorkspaceId()),
                correlation);
        sessionExecutor.submit(sessionId, () -> agentClient.converse(command, sink(correlation)));
        return new InterviewRun(runId);
    }

    /**
     * 问答答复续跑（ask_user 挂起的恢复）：挂起轮的 runId/engineRef 与待确认工具
     * 清单（wait-raised 帧 data.toolCalls 形状，前端问答卡回传）+ 用户答复 →
     * ConfirmResult 批复续跑（续跑续在同一 run 上收口，帧序含答复后的下一问或
     * 收口）。恢复私货（角色卡/owner/工作区/计量）从项目侧事实重建，不信前端。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_013 项目已归档（指令区关闭）
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
                project.getOwnerAccountId() != null
                        ? project.getOwnerAccountId().toString() : null,
                Long.toString(project.getWorkspaceId()),
                role.chatModelString(),
                role.systemPrompt() + knowledgeAppService.sessionTailOf(projectId),
                replyId,
                pendingToolCalls.stream()
                        .map(toolCall -> AgentscopeAgentClient.answeredToolCall(toolCall, answerText))
                        .toList(),
                answerText,
                usageContextOf(projectId, role));
        sessionExecutor.submit(sessionId, () -> agentClient.resume(resume, sink(correlation)));
    }

    /** 一轮访谈的运行标识（前端挂智能体流 ?runId= 的锚）。 */
    public record InterviewRun(String runId) {
    }

    // ---------- 内部 ----------

    /** 流桥 sink：关联字段逐帧注入后经智能体流通道发射（发射失败只记日志不断流）。 */
    private Consumer<AgentEvent> sink(Map<String, Object> correlation) {
        return event -> {
            try {
                streamAppService.publish(event.type(), withCorrelation(event.payload(), correlation));
            }
            catch (RuntimeException e) {
                log.warn("[ba] 流帧发射失败（{}）：{}", event.type(), e.getMessage());
            }
        };
    }

    /** role-assigned 发射（run 提交前——帧序 role-assigned → task-start → …）。 */
    private void emitRoleAssigned(Long projectId, String runId, RolePreset role) {
        streamAppService.publish(AgentEventTypes.ROLE_ASSIGNED, Map.of(
                AgentStreamAppService.PROJECT_FIELD, projectId.toString(),
                AgentStreamAppService.RUN_FIELD, runId,
                AgentEventTypes.ROLE_FIELD, role.name(),
                AgentEventTypes.ROLE_LABEL_FIELD, role.getName(),
                AgentEventTypes.ROLE_ENGINE_FIELD, AgentscopeAgentClient.ENGINE));
    }

    private static Map<String, Object> correlationOf(Long projectId) {
        return Map.of(AgentStreamAppService.PROJECT_FIELD, projectId.toString());
    }

    private static UsageContext usageContextOf(Long projectId, RolePreset role) {
        return new UsageContext(Long.toString(projectId),
                Map.of(ProjectQueryAppService.DIM_ROLE, role.name()));
    }

    /** 关联字段注入（透传不解释；帧序在前——寻址字段不覆盖帧本体字段）。 */
    private static Map<String, Object> withCorrelation(Map<String, Object> payload,
                                                       Map<String, Object> correlation) {
        if (correlation == null || correlation.isEmpty()) {
            return new LinkedHashMap<>(payload);
        }
        Map<String, Object> addressed = new LinkedHashMap<>(correlation);
        addressed.putAll(payload);
        return addressed;
    }

    private Project requireProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
    }

    /** 访谈态守卫：归档即指令区关闭（只读终态），新发言与作答一并拒绝。 */
    private Project requireInterviewableProject(Long projectId) {
        Project project = requireProject(projectId);
        if (project.getArchivedAt() != null) {
            throw new ApplicationException(ProjectMessage.PROJECT_ALREADY_ARCHIVED);
        }
        return project;
    }
}
