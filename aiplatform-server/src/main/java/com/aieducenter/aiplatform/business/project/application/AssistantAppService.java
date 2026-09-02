package com.aieducenter.aiplatform.business.project.application;

import java.util.Map;

import org.springframework.stereotype.Service;

import com.aieducenter.aiplatform.base.agentscope.AgentCommand;
import com.aieducenter.aiplatform.base.agentscope.AgentSessionExecutor;
import com.aieducenter.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.aieducenter.aiplatform.base.agentscope.UsageContext;
import com.aieducenter.aiplatform.base.eventhub.application.AgentStreamAppService;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.model.RolePreset;
import com.aieducenter.aiplatform.business.project.domain.model.UsageDims;

/**
 * 助理职能体编排（#47 咨询零产物短路）：咨询类输入的应答者——与 BA / 编码智能体
 * 同构（AgentScope HarnessAgent 经 {@link AgentscopeAgentClient} 直调，角色卡 +
 * 工具集 + 独立会话 {@code assist-{projectId}}），差异在姿态：
 *
 * <ul>
 *   <li><b>零产物</b>：工具集只读（文件树 / 文件内容 / 项目事实）且工作区解析为
 *       只读面（内核文件/shell 工具不挂，写面结构性关闭）——PRD 与系统都不动，
 *       全程不可能产任何写类事件；</li>
 *   <li><b>零链路</b>：不锚意见、不派修正 run、不受修正排队影响（与修正轨道无
 *       任何共享态），随时可答；</li>
 *   <li><b>独立会话</b>：{@code assist-{projectId}} 无表派生、userId = 项目
 *       owner（槽位跨轮一致），咨询上下文跨轮延续；计量 dims agentKind=assistant。</li>
 * </ul>
 *
 * <p>入口归 {@link DispatchAppService}（三分类的咨询分支）；守卫在派发入口统一
 * 前置（归档 / 订单冻结 / 挂起问答），本服务收到的已是可答的咨询。会话执行器
 * 异步提交即返回（runId 随响应回，过程帧经 SSE；失败经 error 帧表达）。</p>
 */
@Service
public class AssistantAppService {

    /** 助理会话标识派生前缀（projectId → assist-{projectId}，稳定绑定勿动）。 */
    public static final String SESSION_PREFIX = "assist-";

    private final AgentscopeAgentClient agentClient;
    private final AgentStreamBridge streamBridge;
    private final AgentSessionExecutor sessionExecutor;

    public AssistantAppService(AgentscopeAgentClient agentClient, AgentStreamBridge streamBridge,
            AgentSessionExecutor sessionExecutor) {
        this.agentClient = agentClient;
        this.streamBridge = streamBridge;
        this.sessionExecutor = sessionExecutor;
    }

    /**
     * 应答一条咨询（prompt 即用户侧输入）：role-assigned（ASSISTANT）前置后异步
     * 提交——回答经 SSE 到达，runId 随响应回。项目事实由派发入口守卫后的聚合
     * 携带（owner / 工作区寻址不入前端信）。
     */
    public AssistantRun answer(Project project, String question) {
        Long projectId = project.getId();
        RolePreset role = RolePreset.ASSISTANT;
        String sessionId = SESSION_PREFIX + projectId;

        String runId = AgentStreamAppService.newRunId();
        streamBridge.emitRoleAssigned(projectId, runId, role);
        AgentCommand command = new AgentCommand(
                runId,
                question,
                role.systemPrompt(),
                role.chatModelString(),
                sessionId,
                ownerUserIdOf(project),
                new UsageContext(Long.toString(projectId),
                        UsageDims.of(projectId, UsageDims.kindOf(role), sessionId)),
                Long.toString(project.getWorkspaceId()),
                Map.of(AgentStreamAppService.PROJECT_FIELD, projectId.toString()),
                null,
                /* live= */ false,
                role.name(),
                /* workspaceReadOnly= */ true);
        sessionExecutor.submit(sessionId, () ->
                agentClient.converse(command, streamBridge.sink(projectId)));
        return new AssistantRun(runId);
    }

    /** 一轮咨询应答的运行标识（前端挂智能体流 ?runId= 的锚）。 */
    public record AssistantRun(String runId) {
    }

    /** owner 的会话寻址 userId（cat_agent_state 槽位 (userId, sessionId) 的 userId 腿）。 */
    private static String ownerUserIdOf(Project project) {
        return project.getOwnerAccountId() != null
                ? project.getOwnerAccountId().toString() : null;
    }
}
