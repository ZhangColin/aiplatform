package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.semgravio.aiplatform.business.project.application.ModificationHandoffFacts;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

/**
 * {@link SubmitModificationTool}（#623）：对话面唯一修改出口——只把自足指令＋
 * 用户原话（＋可选定位）登记为工具调用事实，不写工作区、不起 run；载荷缺
 * instruction/userWords 回模型可见错误结果且不登记。
 */
class SubmitModificationToolTest {

    private final ModificationHandoffFacts facts = new ModificationHandoffFacts();
    private final SubmitModificationTool tool = new SubmitModificationTool("42", facts);

    @Test
    void given_full_instruction_when_called_then_recorded_for_handoff() {
        ToolResultBlock result = call(Map.of(
                "instruction", "把首页顶部的搜索按钮改成蓝色，与导航栏一致",
                "userWords", "那个搜索按钮改成蓝的",
                "location", "圈选：页面区域 (x=12, y=40, 宽=120, 高=32)，文本「搜索」"));

        assertThat(result.getState()).isNotEqualTo(ToolResultState.ERROR);
        assertThat(facts.consume("42")).containsExactly(
                new ModificationHandoffFacts.Instruction(
                        "把首页顶部的搜索按钮改成蓝色，与导航栏一致",
                        "那个搜索按钮改成蓝的",
                        "圈选：页面区域 (x=12, y=40, 宽=120, 高=32)，文本「搜索」"));
    }

    @Test
    void given_no_location_when_called_then_recorded_without_location() {
        call(Map.of("instruction", "给预约列表加分页", "userWords", "列表加分页"));

        assertThat(facts.consume("42")).containsExactly(
                new ModificationHandoffFacts.Instruction("给预约列表加分页", "列表加分页", null));
    }

    @Test
    void given_missing_instruction_when_called_then_error_and_not_recorded() {
        ToolResultBlock result = call(Map.of("userWords", "改一下"));

        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(resultText(result)).contains("instruction");
        assertThat(facts.consume("42")).isEmpty();
    }

    @Test
    void given_missing_user_words_when_called_then_error_and_not_recorded() {
        ToolResultBlock result = call(Map.of("instruction", "给预约列表加分页"));

        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(resultText(result)).contains("userWords");
        assertThat(facts.consume("42")).isEmpty();
    }

    @Test
    void given_registration_shape_when_inspected_then_contract_keys_present() {
        assertThat(tool.getName()).isEqualTo("submit_modification");
        assertThat(tool.isReadOnly()).isTrue();
        assertThat(tool.getParameters()).containsKeys("type", "properties", "required");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties =
                (Map<String, Object>) tool.getParameters().get("properties");
        assertThat(properties).containsKeys("instruction", "userWords", "location");
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) tool.getParameters();
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) root.get("required");
        assertThat(required).containsExactly("instruction", "userWords");
    }

    @SuppressWarnings("unchecked")
    private ToolResultBlock call(Map<String, Object> input) {
        return Mono.from(tool.callAsync(ToolCallParam.builder()
                .toolUseBlock(new ToolUseBlock("tc-1", SubmitModificationTool.NAME, input, null))
                .input(input)
                .build())).block();
    }

    private static String resultText(ToolResultBlock result) {
        return ((TextBlock) result.getOutput().get(0)).getText();
    }
}
