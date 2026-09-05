package com.aieducenter.aiplatform.business.project.application;

import java.util.Map;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;

import com.aieducenter.aiplatform.base.agentscope.AgentCommand;
import com.aieducenter.aiplatform.base.agentscope.AgentReply;
import com.aieducenter.aiplatform.base.agentscope.AgentResume;
import com.aieducenter.aiplatform.base.agentscope.AgentSuspension;
import com.aieducenter.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.aieducenter.aiplatform.base.agentscope.UsageContext;
import com.aieducenter.aiplatform.base.eventhub.application.EventsAppService;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.model.RolePreset;
import com.aieducenter.aiplatform.business.project.domain.model.UsageDims;

import lombok.extern.slf4j.Slf4j;

/**
 * 编码 run 尝试环（生成与更新共用，#22 落位 / #26 迭代环复用——所有编码 run 同
 * 机制）：每次尝试新 runId，失败有余量<b>静默续试</b>（中间错误不出用户面事件流，
 * run 失败是唯一失败终态），超限转终态失败——终态收口事件 {@code run-failed} 由
 * <b>轨道层</b>在真终态落定点发射（#56：修正轨道排队合并续派的中途超限不是终态，
 * 本层不判），用户侧兜底——生成重新发起 / 修正恢复出口重派或再提意见（#48）。
 *
 * <p>命令全要素同构：CODER 角色卡、{@code coder-{projectId}} 会话（重试续同会话
 * ——已落盘成果保留，同工作区不丢数据）、owner 寻址、长 run 超时、计量 dims
 * （agentKind=coder）、项目工作区、流关联。知识命中前置注入只进首试 prompt（一次
 * 下发一次注入，重试不重检索不重块）。流桥挂步骤边界探活装饰（#49 逐修改刷新：
 * part-step 边界 → 探活 → preview-updated 通知）。</p>
 *
 * <p><b>权限确认挂起（#83）</b>：run 内需批准的工具操作（危险命令）以
 * {@code permission-required} 事件呈现确认卡后流软终点——本环在挂起点驻留
 * （{@link RunPermissionAppService#await}，持有会话执行器 stripe；作答由权限
 * 作答通道在请求线程直接唤醒，无自锁），批准/拒绝即以 ConfirmResult 续跑同
 * run（拒绝语义 = 引擎写 DENIED 工具结果回模型，改道或自行收口）。挂起期间
 * 轨道不收口、不重试（不是尝试失败）、不排空队列——run 仍在途，意见照常排队
 * 合并。</p>
 */
@Component
@Slf4j
class CoderRunAttempts {

    /** 编码会话标识派生前缀（projectId → coder-{projectId}，稳定绑定勿动）。 */
    public static final String SESSION_PREFIX = "coder-";

    /** 一场编码 run 的 prompt 对（首试 + 重试续作轨）。 */
    record Prompts(String first, String retry) {
    }

    /**
     * 一场编码 run 的收场事实：成败 + 末次尝试 runId（终态收口事件 run-failed 的锚，
     * 与末次失败同 runId——#56）。成功时 lastRunId = 成功收口的那次尝试。
     */
    record RunResult(boolean succeeded, String lastRunId) {
    }

    private final AgentscopeAgentClient agentClient;
    private final AgentEventBridge eventBridge;
    private final ProjectKnowledgeAppService knowledgeAppService;
    private final GenerationProperties properties;
    private final StepBoundaryPreviewRefresh previewRefresh;
    private final RunPermissionAppService permissions;

    CoderRunAttempts(AgentscopeAgentClient agentClient,
            AgentEventBridge eventBridge, ProjectKnowledgeAppService knowledgeAppService,
            GenerationProperties properties, StepBoundaryPreviewRefresh previewRefresh,
            RunPermissionAppService permissions) {
        this.agentClient = agentClient;
        this.eventBridge = eventBridge;
        this.knowledgeAppService = knowledgeAppService;
        this.properties = properties;
        this.previewRefresh = previewRefresh;
        this.permissions = permissions;
    }

    /**
     * 跑一场编码 run（有限次尝试）：成功收口即 {@code onSuccess}（收口回调携该次
     * 尝试的 runId——修正收口事件锚定用；回调抛异常即该次尝试失败，走重试/终态——
     * 收口判据不满足的既有口径，如生成 8081 核验 / 修正 finish_edit 事实）。项目
     * 事实（工作区 / owner）从聚合派生。
     *
     * @param what       日志标签（generate / fix）
     * @param firstRunId 首试 runId（调用方预生成随响应回；重试换新 runId 经事件到达）
     * @return           收场事实（成败 + 末次尝试 runId）；超限转终态后的兜底归
     *                   轨道层——终态收口事件 run-failed 与生成重新发起 / 修正恢复
     *                   出口（#48/#56）
     */
    RunResult run(Project project, String firstRunId, Prompts prompts, Consumer<String> onSuccess,
            String what) {
        Long projectId = project.getId();
        String knowledgePrefix = knowledgeAppService.dispatchInjection(prompts.first());
        int maxAttempts = properties.getMaxAttempts();
        String lastRunId = firstRunId;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String runId = attempt == 1 ? firstRunId : EventsAppService.newRunId();
            lastRunId = runId;
            AgentCommand command = new AgentCommand(
                    runId,
                    attempt == 1 ? knowledgePrefix + prompts.first() : prompts.retry(),
                    RolePreset.CODER.systemPrompt(),
                    RolePreset.CODER.chatModelString(),
                    SESSION_PREFIX + projectId,
                    project.getOwnerAccountId() != null
                            ? project.getOwnerAccountId().toString() : null,
                    new UsageContext(Long.toString(projectId),
                            UsageDims.of(projectId, UsageDims.kindOf(RolePreset.CODER),
                                    SESSION_PREFIX + projectId)),
                    Long.toString(project.getWorkspaceId()),
                    Map.of(EventsAppService.PROJECT_FIELD, projectId.toString()),
                    properties.getTimeout(),
                    RolePreset.CODER.name(),
                    /* workspaceReadOnly= */ false);
            try {
                // 逐修改刷新（#49）：事件桥 sink 外包步骤边界探活装饰——part-step 边界
                // （完整修改落定）→ 平台侧探活 → 通过才发 preview-updated 通知
                Consumer<AgentEvent> sink = silentRetryErrors(previewRefresh.decorate(
                        projectId, project.getWorkspaceId(), eventBridge.sink(projectId)));
                settlePermissions(command, agentClient.converse(command, sink), sink);
                onSuccess.accept(runId);
                return new RunResult(true, runId);
            }
            catch (RuntimeException e) {
                log.warn("[{}] 项目 {} 第 {}/{} 次尝试失败（runId={}）：{}",
                        what, projectId, attempt, maxAttempts, runId, e.toString());
            }
        }
        log.error("[{}] 项目 {} 重试超限（{} 次），转终态失败——用户侧兜底（生成重新发起/修正恢复出口）",
                what, projectId, maxAttempts);
        return new RunResult(false, lastRunId);
    }

    /**
     * 权限确认驻留与续跑（#83）：挂起（软终点）即等作答——批准/拒绝以 ConfirmResult
     * 续跑同 run（命令全要素同构，恢复私货从本环命令原样携带），续跑可再挂起
     * （一 run 多确认点）。问答挂起在编码 run 不可达（CODER 无 ask_user 工具），
     * 防御即失败（走尝试环重试，最终 run-failed——不静默错频道）。
     */
    private AgentReply settlePermissions(AgentCommand command, AgentReply reply,
            Consumer<AgentEvent> sink) {
        while (reply.suspension() != null && reply.suspension().permission()) {
            AgentSuspension suspension = reply.suspension();
            boolean approved = permissions.await(suspension.engineRef(), command.runId());
            reply = agentClient.resume(permissionResume(command, suspension, approved), sink);
        }
        if (reply.suspension() != null) {
            throw new IllegalStateException(
                    "编码 run 出现提问挂起（CODER 无 ask_user 工具，不可达）：engineRef="
                            + reply.suspension().engineRef());
        }
        return reply;
    }

    /**
     * 权限作答的续跑请求：命令全要素同构（会话/角色卡/计量/工作区原样——run 上下文
     * 不因确认点漂移），ConfirmResult 按批准位重建（拒绝 = confirmed=false，引擎写
     * DENIED 工具结果回模型）。续跑文本给模型明确的决策反馈与拒绝后的出路
     * （改道或如实收口），不替模型做决定。
     */
    private static AgentResume permissionResume(AgentCommand command, AgentSuspension suspension,
            boolean approved) {
        return new AgentResume(
                command.runId(),
                command.sessionId(),
                command.userId(),
                command.workspaceId(),
                command.modelString(),
                command.systemPrompt(),
                suspension.engineRef(),
                suspension.toolCalls().stream()
                        .map(toolCall -> AgentscopeAgentClient.confirmedToolCall(toolCall, approved))
                        .toList(),
                approved
                        ? "用户已批准该操作，请继续执行并完成本轮任务。"
                        : "用户已拒绝该操作。",
                command.usageContext(),
                command.agentRole());
    }

    /**
     * 静默重试的事件过滤（中间错误不出用户面）：编码 run 尝试环内底座逐次失败补的
     * {@code error} 事件是重试族的过程事实——包在 sink 外滤掉，用户面只见工作消息
     * 正常生长或（超限后）唯一的失败终态 {@code run-failed}。
     */
    private static Consumer<AgentEvent> silentRetryErrors(Consumer<AgentEvent> sink) {
        return event -> {
            if (AgentEventTypes.ERROR.equals(event.type())) {
                return;
            }
            sink.accept(event);
        };
    }
}
