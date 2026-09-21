package com.aieducenter.aiplatform.base.agentscope;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;

import io.agentscope.core.event.ToolCallDeltaEvent;

/**
 * 步骤清单快照单点（部件 part-plan 的生产内核，#236）：update_plan 工具的参数
 * 增量累积 → 参数落定点（ToolCallEnd）解析<b>全量快照</b>（步骤：稳定 id、标题、
 * 状态 pending/in_progress/completed——✓●○ 三态，不设 ✗）。每次调用即整表快照
 * （执行中再调就地整表更新——已收口步骤不可变是提示词纪律，平台 v1 不强制校验）。
 * 畸形条目防御归一：缺 id/标题或空白标题丢弃、未知状态回落 pending、重号首见
 * 胜出；整体解析不出（非 JSON / steps 缺失或非数组 / 空表）不发事件——不产就不
 * 显示。快照不落库（当次会话呈现，刷新不回显——过程明细口径维持）。
 *
 * <p>与 {@link ToolActionLines} 的分工：update_plan 的参数归本内核（不出动作行、
 * 不留动作痕——计划变化不进部件流水），其余工具照旧归动作行内核。</p>
 */
final class PlanSnapshots {

    /**
     * 步骤清单工具名（平台注册，#236）。与 business 侧 UpdatePlanTool.NAME 是同一
     * 名的两处字面（mapper 认名取参数、工具按名注册）——改名两处同步改（本内核
     * 包私有，不为共享常量升公共面）。
     */
    static final String UPDATE_PLAN_TOOL = "update_plan";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, StringBuilder> toolArgs = new HashMap<>();

    /** 该工具是否归本内核（参数路由判据）。 */
    static boolean handles(String toolName) {
        return UPDATE_PLAN_TOOL.equals(toolName);
    }

    /** 工具参数增量只累积不出快照（快照在参数落定点取）。 */
    void onDelta(ToolCallDeltaEvent delta) {
        toolArgs.computeIfAbsent(nvl(delta.getToolCallId()), k -> new StringBuilder())
                .append(nvl(delta.getDelta()));
    }

    /**
     * 参数落定 → 全量快照（取走累积参数）：步骤字段 id/title/state（防御归一见类
     * 注）；解析不出 / 空表 → 空（调用方不发事件）。
     */
    List<Map<String, Object>> takeSnapshot(String toolCallId) {
        StringBuilder args = toolArgs.remove(nvl(toolCallId));
        if (args == null || args.isEmpty()) {
            return List.of();
        }
        JsonNode steps;
        try {
            steps = JSON.readTree(args.toString()).path(AgentEventTypes.PART_PLAN_STEPS_FIELD);
        }
        catch (Exception ignored) {
            // 参数流不完整/非 JSON：不出快照（不产就不显示）
            return List.of();
        }
        if (!steps.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> snapshot = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (JsonNode step : steps) {
            String id = scalarText(step.path(AgentEventTypes.PART_PLAN_STEP_ID_FIELD));
            String title = scalarText(step.path(AgentEventTypes.PART_PLAN_STEP_TITLE_FIELD));
            if (id.isEmpty() || title.isEmpty() || !seenIds.add(id)) {
                continue;
            }
            snapshot.add(Map.of(
                    AgentEventTypes.PART_PLAN_STEP_ID_FIELD, id,
                    AgentEventTypes.PART_PLAN_STEP_TITLE_FIELD, title,
                    AgentEventTypes.PART_PLAN_STEP_STATE_FIELD, stateOf(
                            step.path(AgentEventTypes.PART_PLAN_STEP_STATE_FIELD))));
        }
        return snapshot;
    }

    /** 状态值防御归一：值域外（含缺失）回落 pending（多跑向安全——未知步按待做呈现）。 */
    private static String stateOf(JsonNode state) {
        String text = scalarText(state);
        return AgentEventTypes.PART_PLAN_STATE_PENDING.equals(text)
                || AgentEventTypes.PART_PLAN_STATE_IN_PROGRESS.equals(text)
                || AgentEventTypes.PART_PLAN_STATE_COMPLETED.equals(text)
                        ? text
                        : AgentEventTypes.PART_PLAN_STATE_PENDING;
    }

    /** 标量节点 → strip 文本（非标量/空白 → 空）。 */
    private static String scalarText(JsonNode node) {
        return node != null && node.isValueNode() ? node.asText().strip() : "";
    }

    private static String nvl(String s) {
        return s != null ? s : "";
    }
}
