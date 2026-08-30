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

    public BaInterviewAppService(ProjectRepository projectRepository,
            AgentscopeAgentClient agentClient, AgentStreamAppService streamAppService,
            AgentSessionExecutor sessionExecutor) {
        this.projectRepository = projectRepository;
        this.agentClient = agentClient;
        this.streamAppService = streamAppService;
        this.sessionExecutor = sessionExecutor;
    }

    /**
     * 跑一轮 BA 访谈（开场或后续输入共用——prompt 即用户侧输入）：会话执行器异步
     * 提交即返回（runId 随响应回，过程帧经 SSE；失败经 error 帧表达不炸调用方）。
     *
     * @throws ApplicationException PRJ_001 项目不存在
     */
    public InterviewRun runInterviewTurn(Long projectId, String prompt) {
        Project project = requireProject(projectId);
        RolePreset role = RolePreset.BA;
        String sessionId = SESSION_PREFIX + projectId;
        Map<String, Object> correlation = correlationOf(projectId);

        String runId = AgentStreamAppService.newRunId();
        emitRoleAssigned(projectId, runId, role);
        AgentCommand command = new AgentCommand(
                runId,
                prompt,
                role.systemPrompt(),
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
     * @throws ApplicationException PRJ_001 项目不存在
     */
    public void answerQuestion(Long projectId, String runId, String replyId,
            List<Map<String, Object>> pendingToolCalls, String answerText) {
        Project project = requireProject(projectId);
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
                role.systemPrompt(),
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
}
