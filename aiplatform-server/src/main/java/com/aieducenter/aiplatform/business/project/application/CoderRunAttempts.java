package com.aieducenter.aiplatform.business.project.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
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
import com.aieducenter.aiplatform.business.project.domain.model.AgentProfile;
import com.aieducenter.aiplatform.business.project.domain.model.UsageDims;

import lombok.extern.slf4j.Slf4j;

/**
 * 编码 run 尝试环（生成与更新共用，#22 落位 / #26 迭代环复用——所有编码 run 同
 * 机制）：失败有余量<b>静默续试</b>（#84：中间错误与重试信号不出用户面事件流，
 * <b>用户面 run 身份 = 首试 runId 全程不变</b>——重试尝试的内部 runId 逐次换新仅
 * 服务计量幂等键与日志，经用户面投影 {@link #userFacingProjection} 归一、重试不
 * 新发 run-start；run-finish 押后到收口判据落定——假完成不闪中场收口，判据核验
 * 出自检播报 part-check「检查中 → ✅/❌」（#85）；run 失败
 * 是唯一失败终态），超限转终态失败——终态收口事件
 * {@code run-failed} 由<b>轨道层</b>在真终态落定点发射（#56：修正轨道排队合并
 * 续派的中途超限不是终态，本层不判），用户侧兜底——生成重新发起 / 修正恢复出口
 * 重派或再提意见（#48）。
 *
 * <p>命令全要素同构：执行体配置（{@link AgentProfile#EXECUTOR}）、
 * {@code coder-{projectId}} 会话（重试续同会话——已落盘成果保留，同工作区不丢
 * 数据）、owner 寻址、长 run 超时、计量 dims（agentKind=executor）、项目工作区、
 * 流关联。知识命中前置注入只进首试 prompt（一次下发一次注入，重试不重检索不重
 * 块）。流桥挂步骤边界探活装饰（#49 逐修改刷新：part-step 边界 → 探活 →
 * preview-updated 通知）。</p>
 *
 * <p><b>权限确认挂起（#83）</b>：run 内需批准的工具操作（危险命令）以
 * {@code permission-required} 事件呈现确认卡后流软终点——本环在挂起点驻留
 * （{@link RunPermissionAppService#await}，持有会话执行器 stripe；作答由权限
 * 作答通道在请求线程直接唤醒，无自锁），批准/拒绝即以 ConfirmResult 续跑同
 * run（拒绝语义 = 引擎写 DENIED 工具结果回模型，改道或自行收口）。挂起期间
 * 轨道不收口、不重试（不是尝试失败）、不排空队列——run 仍在途，意见照常排队
 * 合并。挂起会合与作答校验的用户面锚 = 首试 runId（与投影后事件同锚）。</p>
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
     * 一场编码 run 的收场事实：成败（终态收口事件 run-failed 的用户面锚 = 调用方
     * 持有的首试 runId，#84——重试不换新锚，本层不再回传末次尝试的内部标识）。
     */
    record RunResult(boolean succeeded) {
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
     * @param firstRunId 首试 runId（调用方预生成随响应回 = 用户面 run 身份，全程
     *                   不变；重试尝试的内部 runId 不出用户面——经投影归一）
     * @return           收场事实（成败）；超限转终态后的兜底归轨道层——终态收口
     *                   事件 run-failed 锚首试 runId，与生成重新发起 / 修正恢复
     *                   出口（#48/#56）衔接
     */
    RunResult run(Project project, String firstRunId, Prompts prompts, Consumer<String> onSuccess,
            String what) {
        Long projectId = project.getId();
        String knowledgePrefix = knowledgeAppService.dispatchInjection(prompts.first());
        int maxAttempts = properties.getMaxAttempts();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String attemptRunId = attempt == 1 ? firstRunId : EventsAppService.newRunId();
            AgentCommand command = new AgentCommand(
                    attemptRunId,
                    attempt == 1 ? knowledgePrefix + prompts.first() : prompts.retry(),
                    AgentProfile.EXECUTOR.systemPrompt(),
                    AgentProfile.EXECUTOR.chatModelString(),
                    SESSION_PREFIX + projectId,
                    project.getOwnerAccountId() != null
                            ? project.getOwnerAccountId().toString() : null,
                    new UsageContext(Long.toString(projectId),
                            UsageDims.of(projectId, UsageDims.kindOf(AgentProfile.EXECUTOR),
                                    SESSION_PREFIX + projectId)),
                    Long.toString(project.getWorkspaceId()),
                    Map.of(EventsAppService.PROJECT_FIELD, projectId.toString()),
                    properties.getTimeout(),
                    AgentProfile.EXECUTOR.key(),
                    /* workspaceReadOnly= */ false);
            try {
                // 逐修改刷新（#49）：事件桥 sink 外包步骤边界探活装饰——part-step 边界
                // （完整修改落定）→ 平台侧探活 → 通过才发 preview-updated 通知
                Consumer<AgentEvent> projection = userFacingProjection(firstRunId, attemptRunId,
                        previewRefresh.decorate(
                                projectId, project.getWorkspaceId(), eventBridge.sink(projectId)));
                // run-finish 押后到收口判据落定（#84 假完成不闪收口）：converse 正常
                // 返回 ≠ 收口（判据在 onSuccess——8081 核验 / finish_edit 事实），判据
                // 不过即该次尝试失败走重试——中场 run-finish 若出用户面，工作消息定格
                // 了又生长（重试信号外泄）、run-failed 前出现假收口
                AtomicReference<AgentEvent> pendingFinish = new AtomicReference<>();
                Consumer<AgentEvent> sink = event -> {
                    if (AgentEventTypes.RUN_FINISH.equals(event.type())) {
                        pendingFinish.set(event);
                        return;
                    }
                    projection.accept(event);
                };
                settlePermissions(command, agentClient.converse(command, sink), sink, firstRunId);
                // 自检播报（#85）：收口判据核验（onSuccess——生成 8081 探活 / 修正
                // finish_edit 事实，复用既有收口链路、不新增探针）的呈现——核验前
                // 「检查中」、落定出结果，位于被押后的 run-finish 之前（收口前播报）。
                // 静默重试同构口径：尝试间核验未过不出 ❌（部件停在「检查中」——重试
                // 信号不外泄，重试核验再发「检查中」前端幂等）；❌ 仅在末次尝试未过
                //（超限转终态）时出，与轨道层 run-failed 同窗口。状态终值 = 探活结果，
                // 随智能体事件族进重放缓冲，可被收尾统计消费（#88 轮末统计行）
                emitSelfCheck(projection, command, AgentEventTypes.PART_CHECK_STATE_CHECKING);
                try {
                    onSuccess.accept(attemptRunId);
                }
                catch (RuntimeException e) {
                    if (attempt == maxAttempts) {
                        emitSelfCheck(projection, command, AgentEventTypes.PART_CHECK_STATE_FAILED);
                    }
                    throw e;
                }
                emitSelfCheck(projection, command, AgentEventTypes.PART_CHECK_STATE_PASSED);
                if (pendingFinish.get() != null) {
                    projection.accept(pendingFinish.get());
                }
                return new RunResult(true);
            }
            catch (RuntimeException e) {
                log.warn("[{}] 项目 {} 第 {}/{} 次尝试失败（attemptRunId={}）：{}",
                        what, projectId, attempt, maxAttempts, attemptRunId, e.toString());
            }
        }
        log.error("[{}] 项目 {} 重试超限（{} 次），转终态失败——用户侧兜底（生成重新发起/修正恢复出口）",
                what, projectId, maxAttempts);
        return new RunResult(false);
    }

    /**
     * 权限确认驻留与续跑（#83）：挂起（软终点）即等作答——批准/拒绝以 ConfirmResult
     * 续跑同 run（命令全要素同构，恢复私货从本环命令原样携带），续跑可再挂起
     * （一 run 多确认点）。问答挂起在编码 run 不可达（执行体无 ask_user 工具），
     * 防御即失败（走尝试环重试，最终 run-failed——不静默错频道）。
     *
     * @param userRunId 用户面 run 身份（首试 runId，#84）——挂起会合与作答校验的
     *                  锚，与投影后事件同锚（前端按所见 runId 作答）
     */
    private AgentReply settlePermissions(AgentCommand command, AgentReply reply,
            Consumer<AgentEvent> sink, String userRunId) {
        while (reply.suspension() != null && reply.suspension().permission()) {
            AgentSuspension suspension = reply.suspension();
            boolean approved = permissions.await(suspension.engineRef(), userRunId);
            reply = agentClient.resume(permissionResume(command, suspension, approved), sink);
        }
        if (reply.suspension() != null) {
            throw new IllegalStateException(
                    "编码 run 出现提问挂起（执行体无 ask_user 工具，不可达）：engineRef="
                            + reply.suspension().engineRef());
        }
        return reply;
    }

    /**
     * 权限作答的续跑请求：命令全要素同构（会话/配置/计量/工作区原样——run 上下文
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
                command.agentKey(),
                command.workspaceReadOnly());
    }

    /**
     * 自检播报事件（#85）：平台侧收口判据核验的部件事实（不经引擎部件映射表——
     * 判据是平台事实），payload = runId + sessionId + state。经用户面投影发射——
     * 重试尝试归锚首试 runId（用户面 run 身份不变）。
     */
    private static void emitSelfCheck(Consumer<AgentEvent> projection, AgentCommand command,
            String state) {
        projection.accept(new AgentEvent(AgentEventTypes.PART_CHECK, Map.of(
                AgentEventTypes.RUN_FIELD, command.runId(),
                AgentEventTypes.SESSION_FIELD, command.sessionId(),
                AgentEventTypes.PART_CHECK_STATE_FIELD, state)));
    }

    /**
     * 静默重试的用户面投影（#84：中间错误与重试信号不出用户面事件流——用户面
     * run 身份 = 首试 runId 全程不变）：① 底座逐次失败补的 {@code error} 事件
     * 是重试族的过程事实——滤掉；② 重试尝试的 {@code run-start}（重开场 + 内部
     * 重试 prompt 文本）——滤掉；③ 重试尝试的事件 payload runId 归一到首试
     * runId（内部 attempt runId 仅服务计量幂等键与日志，不出用户面）。用户面
     * 只见工作消息正常生长或（超限后）唯一的失败终态 {@code run-failed}。
     */
    private static Consumer<AgentEvent> userFacingProjection(String firstRunId,
            String attemptRunId, Consumer<AgentEvent> sink) {
        boolean retryAttempt = !attemptRunId.equals(firstRunId);
        return event -> {
            if (AgentEventTypes.ERROR.equals(event.type())) {
                return;
            }
            if (retryAttempt && AgentEventTypes.RUN_START.equals(event.type())) {
                return;
            }
            sink.accept(retryAttempt ? reanchor(event, firstRunId) : event);
        };
    }

    /** 事件归锚：payload 的 runId 换成用户面标识（首试 runId）；其余字段原样。 */
    private static AgentEvent reanchor(AgentEvent event, String firstRunId) {
        if (firstRunId.equals(event.payload().get(AgentEventTypes.RUN_FIELD))) {
            return event;
        }
        Map<String, Object> payload = new LinkedHashMap<>(event.payload());
        payload.put(AgentEventTypes.RUN_FIELD, firstRunId);
        return new AgentEvent(event.type(), payload);
    }
}
