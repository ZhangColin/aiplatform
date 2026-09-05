package com.aieducenter.aiplatform.business.project.application;

import java.util.LinkedHashMap;
import java.util.Map;

import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;

/**
 * 脚本化智能体事件（#84 验收缝，生成/迭代双侧共用——契约变化单点同步）：mock
 * 客户端按真实客户端的事件契约经 sink 吐事件（开场 run-start、部件 part-*、
 * 异常前补 error、收口 run-finish），runId 取该次命令的（真实 mapper 行为）。
 * 用户面事件序列断言的事实源。
 */
final class AgentEventScripts {

    private AgentEventScripts() {
    }

    static AgentEvent scripted(String type, String runId, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(AgentEventTypes.RUN_FIELD, runId);
        payload.putAll(extra);
        return new AgentEvent(type, payload);
    }
}
