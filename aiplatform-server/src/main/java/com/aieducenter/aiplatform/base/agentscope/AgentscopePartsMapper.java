package com.aieducenter.aiplatform.base.agentscope;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultState;

import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;

/**
 * 部件映射表单点（parts 契约的生产方）：AgentScope 事件 → 平台消息部件事件
 * （词汇正本 = eventhub {@link AgentEventTypes} 部件组；词根取 agentscope 原生
 * part 族，薄翻译不套某家词表）。解说切段与动作行内核独立承载
 * （{@link NarrationSegments} / {@link ToolActionLines}）；步骤清单快照内核
 * {@link PlanSnapshots}（#236）。工具动作<b>开始即出事件</b>（动作卡全生命周期），
 * 以 toolCallId 锚定跨状态（开始/进行中/完成/失败）。
 * 全事件流恒挂（不限编码 run）——消息 = 有序部件集合是全部智能体事件的呈现地基。
 *
 * <table border="1">
 *   <caption>AgentScope 事件 → 消息部件事件</caption>
 *   <tr><th>AgentScope 事件</th><th>部件 type</th><th>部件字段</th></tr>
 *   <tr><td>TextBlockDelta（累积切段，机器语法段守卫丢弃——#234）</td><td>{@code part-text}</td><td>text（完整段）</td>
 *   <tr><td>ToolCallStart（封闭表内工具）</td><td>{@code part-action}</td><td>state=started</td>
 *   <tr><td>ToolCallEnd（同上）</td><td>{@code part-action}</td><td>state=running（label 至此具体）</td>
 *   <tr><td>ToolCallEnd（update_plan——#236）</td><td>{@code part-plan}</td><td>steps（全量快照，参数落定点出）</td>
 *   <tr><td>ToolResultEnd（同上）</td><td>{@code part-action}</td><td>state=completed / failed（failed 携 error——错误/stderr 首行截断，#229）</td>
 * </table>
 *
 * <p>思考（reasoning）与读类工具不进部件（对客户是噪音）；读类动作的呈现位随
 * 需要扩表。工具参数与结果文本增量只累积不出部件（label 在边界点取、error 在
 * 终 failed 提取，#229）。动作边界先出解说余段（段与段有序不串），run 收尾/挂起由
 * 调用方 {@link #drain()} 出尾段（幂等）。步骤分组已退役（#115：步骤序号对
 * 用户零信息，部件按序竖排）。</p>
 */
final class AgentscopePartsMapper {

    private final String runId;
    private final String sessionId;
    private final String engine;

    private final NarrationSegments narration = new NarrationSegments();
    private final ToolActionLines actions = new ToolActionLines();
    private final PlanSnapshots plans = new PlanSnapshots();

    AgentscopePartsMapper(String runId, String sessionId, String engine) {
        this.runId = runId;
        this.sessionId = sessionId;
        this.engine = engine;
    }

    /** 过程事件 → 部件事件（0..n：边界事件可先带余段部件再出自身部件）；未映射类型产空。 */
    List<AgentEvent> map(io.agentscope.core.event.AgentEvent event) {
        // 来源归属（#95 委派位）：子智能体转发进父流的事件带引擎 source 路径，执行体
        // 自身的事件 source 为空——缺省不携带（用户面仍无角色标签，source 只供过程
        // 呈现归属）
        String source = AgentscopeEventMapper.sourceOf(event);
        if (event instanceof TextBlockDeltaEvent delta) {
            return textParts(narration.offer(delta, source));
        }
        if (event instanceof ToolCallDeltaEvent delta) {
            // 工具参数增量只累积不出部件（短语/快照在边界点取）：update_plan 归计划
            // 内核（不出动作行），其余归动作行内核
            if (PlanSnapshots.handles(delta.getToolCallName())) {
                plans.onDelta(delta);
            }
            else {
                actions.onDelta(delta);
            }
            return List.of();
        }
        if (event instanceof ToolResultTextDeltaEvent delta) {
            // 工具结果文本增量只累积不出部件（失败留痕的原料——终 failed 提取，#229）
            actions.onResultText(delta);
            return List.of();
        }
        List<AgentEvent> parts = new ArrayList<>();
        if (event instanceof ToolCallStartEvent start) {
            drainNarrationInto(parts);
            actionPart(parts, start.getToolCallName(), start.getToolCallId(),
                    AgentEventTypes.PART_ACTION_STATE_STARTED, false, source);
            return parts;
        }
        if (event instanceof ToolCallEndEvent end) {
            drainNarrationInto(parts);
            actionPart(parts, end.getToolCallName(), end.getToolCallId(),
                    AgentEventTypes.PART_ACTION_STATE_RUNNING, false, source);
            // 步骤清单快照在参数落定点出（#236）：全量快照、不走动作行不留动作痕
            if (PlanSnapshots.handles(end.getToolCallName())) {
                planPart(parts, end.getToolCallId(), source);
            }
            return parts;
        }
        if (event instanceof ToolResultEndEvent end) {
            String state = resultState(end.getState());
            // 参数累积保留至终态才取走：非终态（挂起重放 RUNNING）重算取最新——
            // 已落定的具体对象不因参数被前点耗尽而闪回通用标签
            actionPart(parts, end.getToolCallName(), end.getToolCallId(),
                    state, terminal(state), source);
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

    /** 动作态是否终态（completed / failed）。 */
    private static boolean terminal(String state) {
        return AgentEventTypes.PART_ACTION_STATE_COMPLETED.equals(state)
                || AgentEventTypes.PART_ACTION_STATE_FAILED.equals(state);
    }

    /** 动作部件（封闭表内工具才有；label 无时态，时态由 state 表达——非终态从累积
     *  参数重算取最新（在途 → 通用对象、落定 → 具体对象），终态取走参数复述同一
     *  具体对象：动作卡跨状态同一行，不闪换文案）。 */
    private void actionPart(List<AgentEvent> parts, String toolName, String toolCallId,
            String state, boolean consumeArgs, String source) {
        Optional<String> phrase = actions.objectPhrase(nvl(toolName), nvl(toolCallId), consumeArgs);
        if (phrase.isEmpty()) {
            return;
        }
        String label = phrase.get();
        Map<String, Object> payload = new HashMap<>();
        payload.put(AgentEventTypes.RUN_FIELD, runId);
        payload.put(AgentEventTypes.SESSION_FIELD, sessionId);
        payload.put(AgentEventTypes.ENGINE_FIELD, engine);
        if (source != null) {
            payload.put(AgentEventTypes.SOURCE_FIELD, source);
        }
        payload.put(AgentEventTypes.PART_ACTION_TOOL_CALL_FIELD, nvl(toolCallId));
        payload.put(AgentEventTypes.PART_ACTION_TOOL_NAME_FIELD, nvl(toolName));
        payload.put(AgentEventTypes.PART_ACTION_STATE_FIELD, state);
        payload.put(AgentEventTypes.PART_ACTION_LABEL_FIELD, label);
        // 失败留痕（#229）：仅 failed 携带 error——错误/stderr 首行截断（与 label
        // 各管各的额度）；completed 终态取走丢弃（结果文本生命周期同参数）
        actions.errorPhrase(nvl(toolCallId),
                AgentEventTypes.PART_ACTION_STATE_FAILED.equals(state), consumeArgs)
                .ifPresent(error -> payload.put(AgentEventTypes.PART_ACTION_ERROR_FIELD, error));
        parts.add(new AgentEvent(AgentEventTypes.PART_ACTION, payload));
    }

    /**
     * 步骤清单部件（#236）：update_plan 参数落定 → 全量快照（执行中再调即整表
     * 替换——消费端不合并）；解析不出 / 空表不出部件（不产就不显示）。与来源
     * 解耦：不携带计划来源（v1 run 执行体提示词直产）。
     */
    private void planPart(List<AgentEvent> parts, String toolCallId, String source) {
        List<Map<String, Object>> steps = plans.takeSnapshot(toolCallId);
        if (steps.isEmpty()) {
            return;
        }
        parts.add(frame(AgentEventTypes.PART_PLAN, AgentEventTypes.PART_PLAN_STEPS_FIELD,
                steps, source));
    }

    private List<AgentEvent> textParts(List<NarrationSegments.Segment> segments) {
        List<AgentEvent> parts = new ArrayList<>();
        segments.forEach(segment -> parts.add(frame(
                AgentEventTypes.PART_TEXT, AgentEventTypes.PART_TEXT_FIELD,
                segment.text(), segment.source())));
        return parts;
    }

    private void drainNarrationInto(List<AgentEvent> parts) {
        parts.addAll(textParts(narration.drain()));
    }

    private AgentEvent frame(String type, String field, Object value, String source) {
        Map<String, Object> payload = new HashMap<>();
        payload.put(AgentEventTypes.RUN_FIELD, runId);
        payload.put(AgentEventTypes.SESSION_FIELD, sessionId);
        payload.put(AgentEventTypes.ENGINE_FIELD, engine);
        if (source != null) {
            payload.put(AgentEventTypes.SOURCE_FIELD, source);
        }
        payload.put(field, value);
        return new AgentEvent(type, payload);
    }

    private static String nvl(String s) {
        return s != null ? s : "";
    }
}
