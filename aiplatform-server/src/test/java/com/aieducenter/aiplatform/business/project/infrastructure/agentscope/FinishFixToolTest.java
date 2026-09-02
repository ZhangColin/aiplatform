package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import com.aieducenter.aiplatform.business.project.application.FinishFixFacts;

/**
 * {@link FinishFixTool}（#46）：判定事实登记（changed + 说明/原因，后写胜出）+
 * 参数缺失回错误结果（模型可见可重试）+ 权限自检恒放行（判定收口是修正协议的
 * 预期终点）。readOnly（不动工作区，效果仅平台侧事实）。
 */
class FinishFixToolTest {

    private final FinishFixFacts facts = new FinishFixFacts();
    private final FinishFixTool tool = new FinishFixTool("42", facts);

    @Test
    void given_registration_shape_when_inspected_then_contract_keys_present() {
        assertThat(tool.getName()).isEqualTo("finish_fix");
        assertThat(tool.getParameters()).containsKeys("type", "properties", "required");
        assertThat(String.valueOf(tool.getParameters().get("required")))
                .contains("changed").contains("text");
        assertThat(tool.isReadOnly()).isTrue(); // 不动工作区，仅平台侧事实登记
    }

    @Test
    void given_any_call_when_check_permissions_then_always_allow() {
        // 判定收口是修正协议的预期终点：工具点不放确认
        PermissionDecision decision = tool
                .checkPermissions(Map.of("changed", true, "text", "改了主色"), null).block();

        assertThat(decision.getBehavior()).isEqualTo(PermissionBehavior.ALLOW);
    }

    @Test
    void given_changed_true_when_called_then_fact_recorded() {
        ToolResultBlock result = call(Map.of("changed", true, "text", "已把主色调改为绿色"));

        assertThat(result.getState()).isNotEqualTo(ToolResultState.ERROR);
        assertThat(facts.consume("42")).isEqualTo(new FinishFixFacts.Fact(true, "已把主色调改为绿色"));
        assertThat(resultText(result)).contains("已落实");
    }

    @Test
    void given_changed_false_when_called_then_fact_recorded() {
        // 不动系统也必调（changed=false + 原因）——「未动系统」如实呈现的事实源
        ToolResultBlock result = call(Map.of("changed", false, "text", "纯文档性修订，系统现状已满足"));

        assertThat(result.getState()).isNotEqualTo(ToolResultState.ERROR);
        assertThat(facts.consume("42"))
                .isEqualTo(new FinishFixFacts.Fact(false, "纯文档性修订，系统现状已满足"));
        assertThat(resultText(result)).contains("无需改动");
    }

    @Test
    void given_second_call_when_repeated_then_last_write_wins() {
        call(Map.of("changed", true, "text", "先记一版"));
        call(Map.of("changed", false, "text", "复查后判定无需改动"));

        assertThat(facts.consume("42"))
                .isEqualTo(new FinishFixFacts.Fact(false, "复查后判定无需改动"));
    }

    @Test
    void given_blank_text_when_called_then_error_without_record() {
        ToolResultBlock result = call(Map.of("changed", false, "text", " "));

        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(facts.consume("42")).isNull();
    }

    @Test
    void given_missing_or_non_boolean_changed_when_called_then_error_without_record() {
        assertThat(call(Map.of("text", "缺 changed")).getState())
                .isEqualTo(ToolResultState.ERROR);
        assertThat(call(Map.of("changed", "true", "text", "字符串非布尔")).getState())
                .isEqualTo(ToolResultState.ERROR);
        assertThat(facts.consume("42")).isNull();
    }

    // ---------- 内部 ----------

    private ToolResultBlock call(Map<String, Object> input) {
        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(new io.agentscope.core.message.ToolUseBlock(
                        "tc-1", FinishFixTool.NAME, input, null))
                .input(input)
                .build();
        return Mono.from(tool.callAsync(param)).block();
    }

    private static String resultText(ToolResultBlock result) {
        return ((TextBlock) result.getOutput().get(0)).getText();
    }
}
