package com.aieducenter.aiplatform.base.eventhub.domain.model;

import java.util.Map;

/**
 * 智能体流通道的一帧（CONTEXT.md「智能体流事件」的载体）：agentscope 基础设施
 * （事件 mapper）产出、业务编排桥接发射、前端按 type 分发。
 *
 * <p>两类（SSE事件清单·通道二）：平台事件（封闭集合：run-start / run-created /
 * error / run-finish / question-raised，type 取 {@link AgentEventTypes}）与引擎透传事件
 * （开放集合：type = 引擎 part 类型原样，payload 的 {@code data} 键内为 part 原样）。</p>
 *
 * <p>每帧 payload 内盖上 {@code runId}（+ 已知时的 {@code engine}/{@code sessionId}）
 * ——runId 随命令透传进 mapper，任何 sink 无需闭包即可关联。发射到通道的 payload
 * 顶层禁 {@code type} 键名（信封契约），引擎 part 的 type 藏在 {@code data} 内层。</p>
 */
public record AgentEvent(String type, Map<String, Object> payload) {

    public AgentEvent {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("AgentEvent type 不能为空");
        }
        if (payload == null) {
            throw new IllegalArgumentException("AgentEvent payload 必须是对象");
        }
    }
}
