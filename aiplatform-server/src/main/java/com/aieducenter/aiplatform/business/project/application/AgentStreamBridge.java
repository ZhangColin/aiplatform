package com.aieducenter.aiplatform.business.project.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;

import com.aieducenter.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.aieducenter.aiplatform.base.eventhub.application.AgentStreamAppService;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.business.project.domain.model.RolePreset;

import lombok.extern.slf4j.Slf4j;

/**
 * 业务编排的智能体流桥（BA 访谈 / 生成共用）：关联字段（projectId）逐帧注入 +
 * role-assigned / run-retrying 发射。关联字段底座不解释、透传；发射失败护栏：
 * 单帧发射异常只记日志不断流（SSE 是「让 UI 活」的面，不承担正确性）。
 */
@Component
@Slf4j
public class AgentStreamBridge {

    /** 重试话术正本（用户侧文案，SSE事件清单 run-retrying 行）。 */
    static final String RETRYING_MESSAGE = "遇到问题，正在重试";

    private final AgentStreamAppService streamAppService;

    public AgentStreamBridge(AgentStreamAppService streamAppService) {
        this.streamAppService = streamAppService;
    }

    /** 流桥 sink：关联字段逐帧注入后经智能体流通道发射（发射失败只记日志不断流）。 */
    public Consumer<AgentEvent> sink(Long projectId) {
        Map<String, Object> correlation = Map.of(
                AgentStreamAppService.PROJECT_FIELD, projectId.toString());
        return event -> {
            try {
                streamAppService.publish(event.type(), withCorrelation(event.payload(), correlation));
            }
            catch (RuntimeException e) {
                log.warn("[agent-stream] 流帧发射失败（{}）：{}", event.type(), e.getMessage());
            }
        };
    }

    /** role-assigned 发射（run 提交前——帧序 role-assigned → run-start → …）。 */
    public void emitRoleAssigned(Long projectId, String runId, RolePreset role) {
        streamAppService.publish(AgentEventTypes.ROLE_ASSIGNED, Map.of(
                AgentStreamAppService.PROJECT_FIELD, projectId.toString(),
                AgentStreamAppService.RUN_FIELD, runId,
                AgentEventTypes.ROLE_FIELD, role.name(),
                AgentEventTypes.ROLE_LABEL_FIELD, role.getName(),
                AgentEventTypes.ROLE_ENGINE_FIELD, AgentscopeAgentClient.ENGINE));
    }

    /**
     * run-retrying 发射（生成自动重试）：runId 锚定失败的那次尝试（帧序
     * error → run-retrying → 下一尝试 run-start），携带即将下发的尝试序号
     * 与用户侧话术。
     */
    public void emitRunRetrying(Long projectId, String failedRunId, int nextAttempt) {
        streamAppService.publish(AgentEventTypes.RUN_RETRYING, Map.of(
                AgentStreamAppService.PROJECT_FIELD, projectId.toString(),
                AgentStreamAppService.RUN_FIELD, failedRunId,
                AgentEventTypes.RETRY_ATTEMPT_FIELD, nextAttempt,
                AgentEventTypes.RETRY_MESSAGE_FIELD, RETRYING_MESSAGE));
    }

    /**
     * run-failed 发射（编码 run 重试超限·终态收口，#56）：轨道层在真终态落定点
     * 调用——修正轨道与终态账（恢复出口 {@code restartFixRun} 的重派依据）同
     * 事实点，排队合并续派的中途超限不发（轨道仍在途）；生成轨道超限即终态。
     * runId 锚定末次失败的尝试（帧序 error(末次) → run-failed）——前端恢复出口
     * 只认本帧，重试进行中的 error 帧不判终态（「重新修改」零闪现）。
     */
    public void emitRunFailed(Long projectId, String lastFailedRunId) {
        streamAppService.publish(AgentEventTypes.RUN_FAILED, Map.of(
                AgentStreamAppService.PROJECT_FIELD, projectId.toString(),
                AgentStreamAppService.RUN_FIELD, lastFailedRunId));
    }

    /**
     * fix-unchanged 发射（修正 run 收口·系统未动，#46）：finish_fix 判定
     * changed=false 时的如实呈现帧——锚定正常收口的那次尝试的 runId（帧序
     * run-finish → fix-unchanged），reason 为「未动系统」的用户侧原因。
     */
    public void emitFixUnchanged(Long projectId, String runId, String reason) {
        streamAppService.publish(AgentEventTypes.FIX_UNCHANGED, Map.of(
                AgentStreamAppService.PROJECT_FIELD, projectId.toString(),
                AgentStreamAppService.RUN_FIELD, runId,
                AgentEventTypes.FIX_UNCHANGED_REASON_FIELD, reason));
    }

    /**
     * error 发射（修正收口判据不过，#46）：编码 run 正常返回但未以 finish_fix
     * 收口——converse 内部不发 error（它自己认为成功了），此处补发如实表达
     * （error 帧是逐次尝试的过程事实，终态由轨道层的 run-failed 收口，#56），
     * 用户侧能区分「不需要改」与「链路断了」。
     */
    public void emitError(Long projectId, String runId, String message) {
        streamAppService.publish(AgentEventTypes.ERROR, Map.of(
                AgentStreamAppService.PROJECT_FIELD, projectId.toString(),
                AgentStreamAppService.RUN_FIELD, runId,
                "message", message));
    }

    /**
     * guide-reply 发射（兜底轻引导，#47 入口三分类）：非意见非咨询输入的平台侧
     * 定型文案——零产物路径的全部帧（无智能体 run、无帧序）；runId 为派发锚
     * （随派发响应同值返回），prompt 随帧携带供重放重建对话面，label 为呈现
     * 标签（「平台」，非智能体角色）。
     */
    public void emitGuideReply(Long projectId, String runId, String prompt, String label,
            String text) {
        streamAppService.publish(AgentEventTypes.GUIDE_REPLY, Map.of(
                AgentStreamAppService.PROJECT_FIELD, projectId.toString(),
                AgentStreamAppService.RUN_FIELD, runId,
                AgentEventTypes.GUIDE_PROMPT_FIELD, prompt,
                AgentEventTypes.GUIDE_LABEL_FIELD, label,
                AgentEventTypes.GUIDE_TEXT_FIELD, text));
    }

    /**
     * dispatch-stage 发射（#50 阶段状态条）：意见 / 咨询链的阶段推进帧——前端
     * 状态条唯一数据源。runId 锚定当前阶段所属的 run（分析/追问/更新 PRD 锚
     * BA 轮、修正/完成锚修正 run、已答复锚助理轮），链跨 run 推进、帧序即阶段序。
     */
    public void emitDispatchStage(Long projectId, String runId, DispatchStage stage) {
        streamAppService.publish(AgentEventTypes.DISPATCH_STAGE, Map.of(
                AgentStreamAppService.PROJECT_FIELD, projectId.toString(),
                AgentStreamAppService.RUN_FIELD, runId,
                AgentEventTypes.DISPATCH_STAGE_FIELD, stage.wireValue()));
    }

    /**
     * dispatch-stage·完成态发射（#50）：修正收口的终态阶段，{@code changed} 区分
     * 「已修改」与「未动系统」（未动的原因另由 {@code fix-unchanged} 帧承载）。
     */
    public void emitDispatchDone(Long projectId, String runId, boolean changed) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(AgentStreamAppService.PROJECT_FIELD, projectId.toString());
        payload.put(AgentStreamAppService.RUN_FIELD, runId);
        payload.put(AgentEventTypes.DISPATCH_STAGE_FIELD, DispatchStage.DONE.wireValue());
        payload.put(AgentEventTypes.DISPATCH_CHANGED_FIELD, changed);
        streamAppService.publish(AgentEventTypes.DISPATCH_STAGE, payload);
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
}
