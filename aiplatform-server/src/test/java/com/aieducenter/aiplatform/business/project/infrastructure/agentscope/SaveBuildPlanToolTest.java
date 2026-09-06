package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import com.aieducenter.aiplatform.business.project.application.BuildPlan;
import com.aieducenter.aiplatform.business.project.application.BuildPlanFacts;

/**
 * {@link SaveBuildPlanTool}（ADR 0009）：切片计划事实登记（有序纵向切片清单，后写
 * 胜出）+ 参数缺失/空片/非字符串回错误结果（模型可见可重试）+ 权限自检恒放行
 * （切片计划是 PRD 产出的伴生动作）。readOnly（不动工作区，效果仅平台侧事实）。
 */
class SaveBuildPlanToolTest {

    private final BuildPlanFacts facts = new BuildPlanFacts();
    private final SaveBuildPlanTool tool = new SaveBuildPlanTool("42", facts);

    @Test
    void given_registration_shape_when_inspected_then_contract_keys_present() {
        assertThat(tool.getName()).isEqualTo("saveBuildPlan");
        assertThat(tool.getParameters()).containsKeys("type", "properties", "required");
        assertThat(String.valueOf(tool.getParameters().get("required"))).contains("slices");
        assertThat(tool.isReadOnly()).isTrue(); // 不动工作区，仅平台侧事实登记
    }

    @Test
    void given_any_call_when_check_permissions_then_always_allow() {
        // 切片计划是 PRD 产出的伴生动作（访谈协议的预期终点）：工具点不放确认
        PermissionDecision decision = tool
                .checkPermissions(Map.of("slices", List.of("用户能注册登录")), null).block();

        assertThat(decision.getBehavior()).isEqualTo(PermissionBehavior.ALLOW);
    }

    @Test
    void given_slices_when_called_then_plan_recorded_in_order() {
        ToolResultBlock result = call(List.of("用户能注册登录", "用户能下单支付"));

        assertThat(result.getState()).isNotEqualTo(ToolResultState.ERROR);
        assertThat(facts.consume("42"))
                .isEqualTo(new BuildPlan(List.of("用户能注册登录", "用户能下单支付")));
        assertThat(resultText(result)).contains("2 个切片");
    }

    @Test
    void given_second_call_when_repeated_then_last_write_wins() {
        // 一轮多次 saveBuildPlan：交接物取终值（后写胜出——同 PrdRevisionFacts 口径）
        call(List.of("用户能注册登录"));
        call(List.of("用户能下单支付", "用户能查看订单"));

        assertThat(facts.consume("42"))
                .isEqualTo(new BuildPlan(List.of("用户能下单支付", "用户能查看订单")));
    }

    @Test
    void given_missing_or_empty_slices_when_called_then_error_without_record() {
        ToolResultBlock missing = call(null);
        ToolResultBlock empty = call(List.of());

        assertThat(missing.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(empty.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(facts.consume("42")).as("漏传/空清单不落计划事实").isNull();
    }

    @Test
    void given_blank_or_non_string_slice_when_called_then_error_without_record() {
        ToolResultBlock blank = call(List.of("用户能注册登录", " "));
        ToolResultBlock nonString = call(List.of("用户能注册登录", (Object) 42));

        assertThat(blank.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(nonString.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(facts.consume("42")).as("空片/非字符串不落计划事实").isNull();
    }

    @Test
    void given_blank_padded_slice_when_called_then_trimmed_and_recorded() {
        // 切片描述前后空白作净化（不因空格误判空片）
        ToolResultBlock result = call(List.of("  用户能注册登录  "));

        assertThat(result.getState()).isNotEqualTo(ToolResultState.ERROR);
        assertThat(facts.consume("42")).isEqualTo(new BuildPlan(List.of("用户能注册登录")));
    }

    // ---------- 内部 ----------

    private ToolResultBlock call(List<?> slices) {
        Map<String, Object> input = slices != null ? Map.of("slices", slices) : Map.of();
        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(new ToolUseBlock("tc-1", SaveBuildPlanTool.NAME, input, null))
                .input(input)
                .build();
        return Mono.from(tool.callAsync(param)).block();
    }

    private static String resultText(ToolResultBlock result) {
        return ((TextBlock) result.getOutput().get(0)).getText();
    }
}
