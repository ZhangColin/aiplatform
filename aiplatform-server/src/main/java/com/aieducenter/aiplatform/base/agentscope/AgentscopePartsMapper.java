package com.aieducenter.aiplatform.base.agentscope;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;

import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;

/**
 * 部件映射表单点（#77 parts 契约的生产方）：AgentScope 事件 → 平台消息部件事件
 * （词汇正本 = eventhub {@link AgentEventTypes} 部件组；词根取 agentscope 原生
 * part 族，薄翻译不套某家词表）。与 {@link AgentscopeLiveMapper} 同批内核
 * （{@link NarrationSegments} / {@link ToolActionLines}）——区别在部件面的完整
 * 生命周期：工具动作<b>开始即出帧</b>（live-action 仅调用落定才出），动作卡以
 * toolCallId 锚定跨状态（开始/进行中/完成/失败）；解说切段与步骤分组与直播
 * 同口径。全事件流恒挂（不限编码 run）——消息 = 有序部件集合是全部智能体事件
 * 的呈现地基。
 *
 * <table border="1">
 *   <caption>AgentScope 事件 → 消息部件事件</caption>
 *   <tr><th>AgentScope 事件</th><th>部件 type</th><th>部件字段</th></tr>
 *   <tr><td>TextBlockDelta（累积切段）</td><td>{@code part-text}</td><td>text（完整段）</td>
 *   <tr><td>ToolCallStart（封闭表内工具）</td><td>{@code part-action}</td><td>state=started</td>
 *   <tr><td>ToolCallEnd（同上）</td><td>{@code part-action}</td><td>state=running（label 至此具体）</td>
 *   <tr><td>ToolResultEnd（同上）</td><td>{@code part-action}</td><td>state=completed / failed</td>
 *   <tr><td>ModelCallStart（步骤计数）</td><td>{@code part-step}</td><td>step（1 起序号）</td>
 * </table>
 *
 * <p>思考（reasoning）与读类工具不进部件（同直播口径：对客户是噪音）；读类动作
 * 的呈现位随 #81 前端迁移按需扩表。步骤与动作边界先出解说余段（段与段有序
 * 不串），run 收尾/挂起由调用方 {@link #drain()} 出尾段（幂等）。</p>
 */
final class AgentscopePartsMapper {

    private final String runId;
    private final String sessionId;
    private final String engine;

    private final NarrationSegments narration = new NarrationSegments();
    private final ToolActionLines actions = new ToolActionLines();

    /** 动作对象锚（toolCallId → 动作短语）：running 起为具体对象，终态复述同一对象。 */
    private final Map<String, String> actionLabels = new HashMap<>();

    /** 步骤序号（模型调用边界计数，1 起；实例随流段生命周期，续跑新段重新起算）。 */
    private int steps;

    AgentscopePartsMapper(String runId, String sessionId, String engine) {
        this.runId = runId;
        this.sessionId = sessionId;
        this.engine = engine;
    }

    /** 过程事件 → 部件事件（0..n：边界事件可先带余段部件再出自身部件）；未映射类型产空。 */
    List<AgentEvent> map(io.agentscope.core.event.AgentEvent event) {
        if (event instanceof TextBlockDeltaEvent delta) {
            return textParts(narration.offer(delta));
        }
        if (event instanceof ToolCallDeltaEvent delta) {
            // 工具参数增量只累积不出部件（动作对象短语在边界点取）
            actions.onDelta(delta);
            return List.of();
        }
        List<AgentEvent> parts = new ArrayList<>();
        if (event instanceof ModelCallStartEvent) {
            drainNarrationInto(parts);
            steps += 1;
            parts.add(frame(AgentEventTypes.PART_STEP,
                    AgentEventTypes.PART_STEP_FIELD, steps));
            return parts;
        }
        if (event instanceof ToolCallStartEvent start) {
            drainNarrationInto(parts);
            actionPart(parts, start.getToolCallName(), start.getToolCallId(),
                    AgentEventTypes.PART_ACTION_STATE_STARTED, false);
            return parts;
        }
        if (event instanceof ToolCallEndEvent end) {
            drainNarrationInto(parts);
            actionPart(parts, end.getToolCallName(), end.getToolCallId(),
                    AgentEventTypes.PART_ACTION_STATE_RUNNING, true);
            return parts;
        }
        if (event instanceof ToolResultEndEvent end) {
            actionPart(parts, end.getToolCallName(), end.getToolCallId(),
                    resultState(end.getState()), false);
            return parts;
        }
        // 其余事件（思考/读类工具/块尾/挂起等）：不出部件
        return List.of();
    }

    /** run 收尾（run-finish / error / 挂起前）：出解说余段部件（幂等，无尾段即空）。 */
    List<AgentEvent> drain() {
        return textParts(narration.drain());
    }

    // ---------- 内部 ----------

    /** 工具结果态 → 动作卡终态（成功 = 完成；出错/被拒/中断 = 失败；running 原样透传）。 */
    private static String resultState(ToolResultState state) {
        if (state == ToolResultState.SUCCESS) {
            return AgentEventTypes.PART_ACTION_STATE_COMPLETED;
        }
        if (state == ToolResultState.RUNNING) {
            return AgentEventTypes.PART_ACTION_STATE_RUNNING;
        }
        return AgentEventTypes.PART_ACTION_STATE_FAILED;
    }

    /** 动作部件（封闭表内工具才有；label 无时态，时态由 state 表达——非终态存档
     *  动作对象、终态复述已锚定对象：动作卡跨状态同一行，不闪换文案）。 */
    private void actionPart(List<AgentEvent> parts, String toolName, String toolCallId,
            String state, boolean consumeArgs) {
        Optional<String> phrase = actions.objectPhrase(nvl(toolName), nvl(toolCallId), consumeArgs);
        if (phrase.isEmpty()) {
            return;
        }
        boolean terminal = AgentEventTypes.PART_ACTION_STATE_COMPLETED.equals(state)
                || AgentEventTypes.PART_ACTION_STATE_FAILED.equals(state);
        String label;
        if (terminal) {
            label = actionLabels.getOrDefault(nvl(toolCallId), phrase.get());
        }
        else {
            label = phrase.get();
            actionLabels.put(nvl(toolCallId), label);
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put(AgentEventTypes.RUN_FIELD, runId);
        payload.put(AgentEventTypes.SESSION_FIELD, sessionId);
        payload.put(AgentEventTypes.ROLE_ENGINE_FIELD, engine);
        payload.put(AgentEventTypes.PART_ACTION_TOOL_CALL_FIELD, nvl(toolCallId));
        payload.put(AgentEventTypes.PART_ACTION_TOOL_NAME_FIELD, nvl(toolName));
        payload.put(AgentEventTypes.PART_ACTION_STATE_FIELD, state);
        payload.put(AgentEventTypes.PART_ACTION_LABEL_FIELD, label);
        parts.add(new AgentEvent(AgentEventTypes.PART_ACTION, payload));
    }

    private List<AgentEvent> textParts(List<String> segments) {
        List<AgentEvent> parts = new ArrayList<>();
        segments.forEach(text -> parts.add(frame(
                AgentEventTypes.PART_TEXT, AgentEventTypes.PART_TEXT_FIELD, text)));
        return parts;
    }

    private void drainNarrationInto(List<AgentEvent> parts) {
        parts.addAll(textParts(narration.drain()));
    }

    private AgentEvent frame(String type, String field, Object value) {
        Map<String, Object> payload = new HashMap<>();
        payload.put(AgentEventTypes.RUN_FIELD, runId);
        payload.put(AgentEventTypes.SESSION_FIELD, sessionId);
        payload.put(AgentEventTypes.ROLE_ENGINE_FIELD, engine);
        payload.put(field, value);
        return new AgentEvent(type, payload);
    }

    private static String nvl(String s) {
        return s != null ? s : "";
    }
}
