package com.aieducenter.aiplatform.business.project.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;

import com.aieducenter.aiplatform.base.eventhub.application.EventsAppService;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;

import lombok.extern.slf4j.Slf4j;

/**
 * 业务编排的智能体事件桥（BA 访谈 / 生成共用）：关联字段（projectId）逐事件注入
 * 后经事件通道发射。关联字段底座不解释、透传；发射失败护栏：单事件发射异常只记
 * 日志不断流（SSE 是「让 UI 活」的面，不承担正确性）。
 */
@Component
@Slf4j
public class AgentEventBridge {

    private final EventsAppService eventsAppService;

    public AgentEventBridge(EventsAppService eventsAppService) {
        this.eventsAppService = eventsAppService;
    }

    /** 事件桥 sink：关联字段逐事件注入后经事件通道发射（发射失败只记日志不断流）。 */
    public Consumer<AgentEvent> sink(Long projectId) {
        Map<String, Object> correlation = Map.of(
                EventsAppService.PROJECT_FIELD, projectId.toString());
        return event -> {
            try {
                eventsAppService.publishAgentEvent(event.type(), withCorrelation(event.payload(), correlation));
            }
            catch (RuntimeException e) {
                log.warn("[agent-event] 事件发射失败（{}）：{}", event.type(), e.getMessage());
            }
        };
    }

    /**
     * run-failed 发射（编码 run 重试超限·终态收口，#56）：轨道层在真终态落定点
     * 调用——修正轨道与终态账（恢复出口 {@code restartFixRun} 的重派依据）同
     * 事实点，排队合并续派的中途超限不发（轨道仍在途）；生成轨道超限即终态。
     * runId = 该场 run 的用户面标识（首试 runId——#84 静默重试：重试不换新锚，
     * 中间尝试的内部标识不出用户面）。前端恢复出口只认本事件，run 失败为唯一
     * 失败终态（重试全程静默，中间失败不出事件）。
     */
    public void emitRunFailed(Long projectId, String runId) {
        eventsAppService.publishAgentEvent(AgentEventTypes.RUN_FAILED, Map.of(
                EventsAppService.PROJECT_FIELD, projectId.toString(),
                EventsAppService.RUN_FIELD, runId));
    }

    /**
     * error 发射（失败家族——非重试族的失败表达）：意见链收口后派发修正 run 失败
     * （#51 → #82 失败家族归位：dispatch-failed 阶段族退役，失败信号归本事件）——
     * 意见锚已消费、不自动重试，用户重提即兜底；锚定收口 BA 轮的 runId（对话面
     * 已登记，失败提示随对话呈现）。
     */
    public void emitError(Long projectId, String runId, String message) {
        eventsAppService.publishAgentEvent(AgentEventTypes.ERROR, Map.of(
                EventsAppService.PROJECT_FIELD, projectId.toString(),
                EventsAppService.RUN_FIELD, runId,
                AgentEventTypes.ERROR_MESSAGE_FIELD, message));
    }

    /**
     * guide-reply 发射（兜底轻引导，#47 入口三分类）：非意见非咨询输入的平台侧
     * 定型文案——零产物路径的全部事件（无智能体 run、无事件序）；runId 为派发锚
     * （随派发响应同值返回），prompt 随事件携带供重放重建对话面，label 为呈现
     * 标签（「平台」，非智能体角色）。
     */
    public void emitGuideReply(Long projectId, String runId, String prompt, String label,
            String text) {
        eventsAppService.publishAgentEvent(AgentEventTypes.GUIDE_REPLY, Map.of(
                EventsAppService.PROJECT_FIELD, projectId.toString(),
                EventsAppService.RUN_FIELD, runId,
                AgentEventTypes.GUIDE_PROMPT_FIELD, prompt,
                AgentEventTypes.GUIDE_LABEL_FIELD, label,
                AgentEventTypes.GUIDE_TEXT_FIELD, text));
    }

    /**
     * permission-resolved 发射（#83 权限确认落定）：作答被受理（批准或拒绝）即
     * 发射——确认卡转已批/已拒终态的呈现源（事件族重放面：重连/刷新后确认卡
     * 不回退成待答）。续跑结果另行经 run 过程事件到达（批准的动作卡完成 / 拒绝
     * 的动作卡失败 + 后续模型行为）。
     */
    public void emitPermissionResolved(Long projectId, String runId, String engineRef,
            boolean approved) {
        eventsAppService.publishAgentEvent(AgentEventTypes.PERMISSION_RESOLVED, Map.of(
                EventsAppService.PROJECT_FIELD, projectId.toString(),
                EventsAppService.RUN_FIELD, runId,
                AgentEventTypes.WAIT_ENGINE_REF_FIELD, engineRef,
                AgentEventTypes.PERMISSION_APPROVED_FIELD, approved));
    }

    /** 关联字段注入（透传不解释；事件序在前——寻址字段不覆盖事件本体字段）。 */
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
