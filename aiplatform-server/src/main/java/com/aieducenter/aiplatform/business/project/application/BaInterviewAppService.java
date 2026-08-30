package com.aieducenter.aiplatform.business.project.application;

import java.util.Map;

import org.springframework.stereotype.Service;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.agentengine.application.AgentRunContext;
import com.aieducenter.aiplatform.base.agentengine.application.AgentStreamAppService;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.chatagent.application.ChatAgentAppService;
import com.aieducenter.aiplatform.base.chatagent.domain.model.ChatAgentCommand;
import com.aieducenter.aiplatform.base.chatagent.domain.model.UsageContext;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.RolePreset;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * BA 访谈编排（ADR 双轨分野）：BA 是平台进程内对话智能体（AgentScope
 * HarnessAgent，经 {@link ChatAgentAppService}），创建即访谈
 * （{@code ProjectLifecycleAppService} 建项目后开场）与后续对话轮都续同一
 * BA 会话。
 *
 * <p><b>会话寻址（projectId → BA 会话的稳定绑定）</b>：sessionId =
 * {@code ba-{projectId}} 无表派生、userId = 项目 owner（State 槽位
 * (userId, sessionId) 跨轮一致，谁触发都不劈叉上下文）。计量（role=BA 维度）
 * 与引擎任务面同构。问答卡的作答通道随需求环（#19）落位。</p>
 */
@Service
@Slf4j
public class BaInterviewAppService {

    /** BA 会话标识派生前缀（projectId → ba-{projectId}，稳定绑定勿动）。 */
    public static final String SESSION_PREFIX = "ba-";

    private final ProjectRepository projectRepository;
    private final ChatAgentAppService chatAgentAppService;
    private final AgentStreamAppService streamAppService;

    public BaInterviewAppService(ProjectRepository projectRepository,
            ChatAgentAppService chatAgentAppService, AgentStreamAppService streamAppService) {
        this.projectRepository = projectRepository;
        this.chatAgentAppService = chatAgentAppService;
        this.streamAppService = streamAppService;
    }

    /**
     * 跑一轮 BA 访谈（开场或后续输入共用——prompt 即用户侧输入）：异步提交即返回
     * （runId 先生成随响应回，过程帧经 SSE；失败经 error 帧表达不炸调用方）。
     *
     * @throws ApplicationException PRJ_001 项目不存在
     */
    public InterviewRun runInterviewTurn(Long projectId, String prompt) {
        Project project = requireProject(projectId);
        RolePreset role = RolePreset.BA;
        String sessionId = SESSION_PREFIX + projectId;

        String runId = AgentRunContext.newRunId();
        emitRoleAssigned(projectId, runId, role);
        ChatAgentCommand command = new ChatAgentCommand(
                runId,
                prompt,
                role.systemPrompt(),
                role.chatModelString(),
                sessionId,
                project.getOwnerAccountId() != null
                        ? project.getOwnerAccountId().toString() : null,
                new UsageContext(Long.toString(projectId),
                        Map.of(ProjectQueryAppService.DIM_ROLE, role.name())),
                Long.toString(project.getWorkspaceId()),
                Map.of(AgentStreamAppService.PROJECT_FIELD, projectId.toString()));
        boolean accepted = chatAgentAppService.converseAsync(command);
        return new InterviewRun(runId, accepted);
    }

    /** 一轮访谈的运行标识（前端挂 agent 流 ?runId= 的锚）。 */
    public record InterviewRun(String runId, boolean accepted) {
    }

    // ---------- 内部 ----------

    /** role-assigned 发射（run 提交前——帧序 role-assigned → task-start → …）。 */
    private void emitRoleAssigned(Long projectId, String runId, RolePreset role) {
        streamAppService.publish(AgentEventTypes.ROLE_ASSIGNED, Map.of(
                AgentStreamAppService.PROJECT_FIELD, projectId.toString(),
                AgentStreamAppService.RUN_FIELD, runId,
                AgentEventTypes.ROLE_FIELD, role.name(),
                AgentEventTypes.ROLE_LABEL_FIELD, role.getName(),
                AgentEventTypes.ROLE_ENGINE_FIELD, ChatAgentAppService.ENGINE));
    }

    private Project requireProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
    }
}
