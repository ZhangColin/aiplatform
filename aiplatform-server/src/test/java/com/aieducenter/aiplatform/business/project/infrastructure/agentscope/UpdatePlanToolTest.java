package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

/**
 * {@link UpdatePlanTool}（#236）：输入校验给模型纠偏信号（全量快照形状——每步
 * id/title/state 三键齐全、状态三值域）+ 权限自检恒放行（步骤清单是过程呈现的
 * 预期动作）+ readOnly 零副作用（呈现归部件映射表，本工具无登记事实）。
 */
class UpdatePlanToolTest {

    private final UpdatePlanTool tool = new UpdatePlanTool();

    @Test
    void given_registration_shape_when_inspected_then_contract_keys_present() {
        assertThat(tool.getName()).isEqualTo("update_plan");
        assertThat(tool.getParameters()).containsKeys("type", "properties", "required");
        assertThat(String.valueOf(tool.getParameters().get("required"))).contains("steps");
        assertThat(tool.isReadOnly()).isTrue(); // 呈现走部件映射表，零登记副作用
    }

    @Test
    void given_any_call_when_check_permissions_then_always_allow() {
        // 步骤清单是过程呈现的预期动作：工具点不放确认
        PermissionDecision decision = tool
                .checkPermissions(Map.of("steps", List.of()), null).block();

        assertThat(decision.getBehavior()).isEqualTo(PermissionBehavior.ALLOW);
    }

    @Test
    void given_full_snapshot_when_called_then_ack() {
        ToolResultBlock result = call(Map.of("steps", List.of(
                Map.of("id", "s1", "title", "读取现有配色", "state", "completed"),
                Map.of("id", "s2", "title", "调整主题色", "state", "in_progress"))));

        assertThat(result.getState()).isNotEqualTo(ToolResultState.ERROR);
        assertThat(resultText(result)).contains("已更新");
    }

    @Test
    void given_missing_empty_or_non_array_steps_when_called_then_error() {
        assertThat(call(Map.of()).getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(call(Map.of("steps", List.of())).getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(call(Map.of("steps", "s1")).getState()).isEqualTo(ToolResultState.ERROR);
    }

    @Test
    void given_malformed_step_entries_when_called_then_error_with_position() {
        // 纠偏信号带位置：模型可据错误文本自修重发
        assertThat(call(Map.of("steps", List.of(
                Map.of("id", "s1", "title", "读文件", "state", "completed"),
                Map.of("id", "s2", "title", "改色"))))
                .getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(call(Map.of("steps", List.of(
                Map.of("id", "s1", "title", "改色", "state", "done"))))
                .getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(call(Map.of("steps", List.of("not an object")))
                .getState()).isEqualTo(ToolResultState.ERROR);
    }

    // ---------- 内部 ----------

    private ToolResultBlock call(Map<String, Object> input) {
        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(new io.agentscope.core.message.ToolUseBlock(
                        "tc-1", UpdatePlanTool.NAME, input, null))
                .input(input)
                .build();
        return Mono.from(tool.callAsync(param)).block();
    }

    private static String resultText(ToolResultBlock result) {
        return ((TextBlock) result.getOutput().get(0)).getText();
    }
}
