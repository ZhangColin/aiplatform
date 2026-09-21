package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

/**
 * 步骤清单工具（run 执行体资产，#236）：run 开工先调一次给出本次任务的步骤拆分
 * （全量快照：步骤 = 稳定 id + 用户语言标题 + 状态 ✓●○），执行中随推进/调整再调
 * ——每次传全量，已收口步骤不可变（提示词纪律，平台 v1 不强制校验）。
 *
 * <p>效果仅呈现（{@code part-plan} 部件由部件映射表从本工具的参数增量解析产出
 * ——平台从工具调用事实观测，同 finish_edit 口径），无登记副作用、不落库（当次
 * 会话定格留驻，刷新不回显）；readOnly、无需确认。步骤不设 ✗ 态（失败留痕归
 * 动作部件与收尾卡）；拆分策略归 agent 侧（平台不内置步骤数约束——不产就不
 * 显示、忘推进如实滞留）。</p>
 */
public class UpdatePlanTool extends ToolBase {

    /**
     * 平台注册名。与 base.agentscope 的 PlanSnapshots.UPDATE_PLAN_TOOL（mapper 认名
     * 取参数增量产 part-plan）是同一名的两处字面——改名两处同步改（base 内核类包
     * 私有，不为共享常量升公共面）。
     */
    public static final String NAME = "update_plan";

    private static final String STEPS_KEY = "steps";
    private static final String ID_KEY = "id";
    private static final String TITLE_KEY = "title";
    private static final String STATE_KEY = "state";
    /** 步骤状态值域（✓●○）——正本 = eventhub {@link AgentEventTypes} part-plan 常量。 */
    private static final Set<String> STATES = Set.of(
            AgentEventTypes.PART_PLAN_STATE_PENDING,
            AgentEventTypes.PART_PLAN_STATE_IN_PROGRESS,
            AgentEventTypes.PART_PLAN_STATE_COMPLETED);

    public UpdatePlanTool() {
        super(ToolBase.builder()
                .name(NAME)
                .description("步骤清单工具（全量快照）：开工先调用一次，把本次任务的步骤拆分发给用户看"
                        + "（steps 传全部步骤，每步带稳定 id、一句用户语言标题、状态）；此后每完成一步、"
                        + "开始下一步或需要调整时再次调用，同样传当前全部步骤（已完成的步骤保持 id 与"
                        + "内容不变，只改未完成部分）。")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                STEPS_KEY, Map.of(
                                        "type", "array",
                                        "description", "全量步骤快照（按执行顺序）：每次调用传当前完整清单，不传增量",
                                        "items", Map.of(
                                                "type", "object",
                                                "properties", Map.of(
                                                        ID_KEY, Map.of(
                                                                "type", "string",
                                                                "description", "步骤稳定 id（跨多次调用不变）"),
                                                        TITLE_KEY, Map.of(
                                                                "type", "string",
                                                                "description", "步骤标题（用户语言一句话）"),
                                                        STATE_KEY, Map.of(
                                                                "type", "string",
                                                                "enum", List.of(
                                                                        AgentEventTypes.PART_PLAN_STATE_PENDING,
                                                                        AgentEventTypes.PART_PLAN_STATE_IN_PROGRESS,
                                                                        AgentEventTypes.PART_PLAN_STATE_COMPLETED),
                                                                "description", "pending=待做 / in_progress=进行中 / completed=已完成")),
                                                "required", List.of(ID_KEY, TITLE_KEY, STATE_KEY)))),
                        "required", List.of(STEPS_KEY)))
                .readOnly(true)
                .concurrencySafe(true));
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> toolInput,
            PermissionContextState context) {
        // 步骤清单是过程呈现的预期动作，工具点不放确认
        return Mono.just(PermissionDecision.allow("步骤清单是过程呈现的预期动作，无需确认"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        // 呈现走部件映射表（从参数增量观测），本工具只做输入校验给模型纠偏信号
        Map<String, Object> input = param.getInput() != null ? param.getInput() : Map.of();
        if (!(input.get(STEPS_KEY) instanceof List<?> steps) || steps.isEmpty()) {
            return Mono.just(ToolResultBlock.error(
                    "steps 必须是非空数组：传当前全部步骤（每步含 id/title/state）"));
        }
        for (int i = 0; i < steps.size(); i++) {
            if (!(steps.get(i) instanceof Map<?, ?> step)) {
                return Mono.just(ToolResultBlock.error("第 " + (i + 1) + " 个步骤不是对象：每步需含 id/title/state"));
            }
            String at = "第 " + (i + 1) + " 个步骤";
            if (blank(step.get(ID_KEY))) {
                return Mono.just(ToolResultBlock.error(at + "缺 id（稳定 id，跨调用不变）"));
            }
            if (blank(step.get(TITLE_KEY))) {
                return Mono.just(ToolResultBlock.error(at + "缺 title（用户语言一句话标题）"));
            }
            Object state = step.get(STATE_KEY);
            if (!(state instanceof String value) || !STATES.contains(value)) {
                return Mono.just(ToolResultBlock.error(
                        at + "的 state 需为 pending / in_progress / completed"));
            }
        }
        return Mono.just(ToolResultBlock.text("步骤清单已更新。"));
    }

    private static boolean blank(Object value) {
        return !(value instanceof String text) || text.isBlank();
    }
}
