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

import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;

/**
 * 直播映射表单点（#23 生成环②）：AgentScope 事件 → 平台直播帧（词汇正本 = eventhub
 * {@link AgentEventTypes} 直播组；面向客户的解说广播，前端直播侧栏唯一消费面——不
 * 耦合引擎透传格式）。仅编码 run（生成/修正）经 {@link AgentCommand#live()} opt-in
 * 挂载，BA 对话不流式不留痕。
 *
 * <p><b>解说生产</b>（CONTEXT.md「直播」）：智能体自述为主（文本增量逐段成型）+
 * 工具动作人话模板兜底；思考（reasoning）与读类工具不播。段切分与动作行的生产
 * 内核与部件映射表共用（{@link NarrationSegments} / {@link ToolActionLines}，#77
 * 提取——本类在双发射过渡期保持原行为，随 #82 收缩票与 live-* 一并退役）。</p>
 *
 * <table border="1">
 *   <caption>AgentScope 事件 → 直播帧</caption>
 *   <tr><th>AgentScope 事件</th><th>直播 type</th><th>段字段</th></tr>
 *   <tr><td>TextBlockDelta（累积切段）</td><td>{@code live-text}</td><td>text（完整段）</td>
 *   <tr><td>ToolCallEnd（write_file/edit_file/command）</td><td>{@code live-action}</td><td>action（人话行）</td>
 *   <tr><td>ModelCallStart（步骤计数）</td><td>{@code live-step}</td><td>step（1 起序号）</td>
 * </table>
 */
final class AgentscopeLiveMapper {

    private final String runId;
    private final String sessionId;
    private final String engine;

    private final NarrationSegments narration = new NarrationSegments();
    private final ToolActionLines actions = new ToolActionLines();

    /** 步骤序号（帧无状态，以 mapper 实例内计数承载，1 起）。 */
    private int steps;

    AgentscopeLiveMapper(String runId, String sessionId, String engine) {
        this.runId = runId;
        this.sessionId = sessionId;
        this.engine = engine;
    }

    /** 过程事件 → 直播帧（0..n：边界事件可先带一帧余段再出自身帧）；未映射类型产空。 */
    List<AgentEvent> map(io.agentscope.core.event.AgentEvent event) {
        if (event instanceof TextBlockDeltaEvent delta) {
            return narration.offer(delta).stream()
                    .map(text -> frame(AgentEventTypes.LIVE_TEXT,
                            AgentEventTypes.LIVE_TEXT_FIELD, text))
                    .toList();
        }
        if (event instanceof ToolCallDeltaEvent delta) {
            // 工具参数增量只累积不产帧（动作行在调用落定点出）
            actions.onDelta(delta);
            return List.of();
        }
        List<AgentEvent> frames = new ArrayList<>();
        if (event instanceof ModelCallStartEvent) {
            flushNarration(frames);
            step(frames);
            return frames;
        }
        if (event instanceof ToolCallEndEvent end) {
            flushNarration(frames);
            actionLine(end).ifPresent(line ->
                    frames.add(frame(AgentEventTypes.LIVE_ACTION,
                            AgentEventTypes.LIVE_ACTION_FIELD, line)));
            return frames;
        }
        // 其余事件（思考/读类工具/块尾/挂起等）：不产帧不切段
        return List.of();
    }

    /** run 收尾（run-finish / error / 挂起前）：出余段（幂等，无尾段即空）。 */
    List<AgentEvent> flush() {
        List<AgentEvent> frames = new ArrayList<>();
        flushNarration(frames);
        return frames;
    }

    // ---------- 内部 ----------

    private void step(List<AgentEvent> frames) {
        steps += 1;
        frames.add(frame(AgentEventTypes.LIVE_STEP,
                AgentEventTypes.LIVE_STEP_FIELD, steps));
    }

    private Optional<String> actionLine(ToolCallEndEvent end) {
        return actions.objectPhrase(nvl(end.getToolCallName()), nvl(end.getToolCallId()), true)
                .map(phrase -> "正在" + phrase);
    }

    private void flushNarration(List<AgentEvent> frames) {
        narration.drain().forEach(text ->
                frames.add(frame(AgentEventTypes.LIVE_TEXT,
                        AgentEventTypes.LIVE_TEXT_FIELD, text)));
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
