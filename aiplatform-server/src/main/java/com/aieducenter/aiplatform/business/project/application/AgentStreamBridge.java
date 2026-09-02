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
     * （末次 error 即终态，与「末次 error 帧即终态表达」的既有口径对齐），
     * 用户侧能区分「不需要改」与「链路断了」。
     */
    public void emitError(Long projectId, String runId, String message) {
        streamAppService.publish(AgentEventTypes.ERROR, Map.of(
                AgentStreamAppService.PROJECT_FIELD, projectId.toString(),
                AgentStreamAppService.RUN_FIELD, runId,
                "message", message));
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
