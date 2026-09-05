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
     * runId 锚定末次失败的尝试——前端恢复出口只认本事件，run 失败为唯一失败
     * 终态（重试全程静默，中间失败不出事件）。
     */
    public void emitRunFailed(Long projectId, String lastFailedRunId) {
        eventsAppService.publishAgentEvent(AgentEventTypes.RUN_FAILED, Map.of(
                EventsAppService.PROJECT_FIELD, projectId.toString(),
                EventsAppService.RUN_FIELD, lastFailedRunId));
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
