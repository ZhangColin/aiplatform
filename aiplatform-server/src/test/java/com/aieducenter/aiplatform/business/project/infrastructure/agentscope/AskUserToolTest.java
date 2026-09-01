package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aieducenter.aiplatform.base.agentscope.AgentscopeAgentClient;

import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

/**
 * {@link AskUserTool}（#48 挂起源）：工具自检恒 ASK（Built-in 不可绕过——trivial
 * 权限上下文也走自检，无需配置 permissionContext）；执行体返回挂起批复经 block
 * metadata 注入的答复（模型不可见通道，#34：答复进 input 会教模型自答）。
 */
class AskUserToolTest {

    private final AskUserTool tool = new AskUserTool();

    @Test
    void given_no_answer_when_check_permissions_then_ask() {
        // 无答复 → ASK = 调用即挂起（RequireUserConfirmEvent → 平台问答卡 QUESTION）
        io.agentscope.core.permission.PermissionDecision decision =
                tool.checkPermissions(Map.of("question", "用哪个框架?"), null).block();

        assertThat(decision.getBehavior())
                .isEqualTo(io.agentscope.core.permission.PermissionBehavior.ASK);
    }

    @Test
    void given_answer_key_in_input_when_check_permissions_then_still_ask() {
        // #34 回归守卫：input 带 answer 键（模型编造的「自答」形状）不再放行——
        // 放行只来自挂起批复（内核对 ConfirmResult 置 ALLOWED 的调用整体跳过权限
        // 引擎，重演不会再过自检，无需 input 键判据）
        io.agentscope.core.permission.PermissionDecision decision = tool.checkPermissions(
                Map.of("question", "用哪个框架?", "answer", "模型编造的答案"), null).block();

        assertThat(decision.getBehavior())
                .isEqualTo(io.agentscope.core.permission.PermissionBehavior.ASK);
    }

    @Test
    void given_answer_in_block_metadata_when_called_then_answer_returned_as_result() {
        // 答复经 ConfirmResult 重写 block 的 metadata 注入（模型不可见），执行体读它
        // 作为工具结果回给模型——访谈上下文直接可读
        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(new io.agentscope.core.message.ToolUseBlock(
                        "tc-1", AskUserTool.NAME,
                        Map.of("question", "用哪个框架?"), null,
                        Map.of(AgentscopeAgentClient.ANSWER_METADATA_KEY, "Spring Boot"), null))
                .input(Map.of("question", "用哪个框架?"))
                .build();

        String result = ((io.agentscope.core.message.TextBlock) Mono.from(tool.callAsync(param))
                .block().getOutput().get(0)).getText();

        assertThat(result).isEqualTo("Spring Boot");
    }

    @Test
    void given_no_metadata_when_called_then_empty_result() {
        // 非「用户作答续跑」路径执行到本工具（异常面）：无 metadata 答复 → 空结果
        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(new io.agentscope.core.message.ToolUseBlock(
                        "tc-1", AskUserTool.NAME, Map.of("question", "用哪个框架?"), null))
                .input(Map.of("question", "用哪个框架?"))
                .build();

        String result = ((io.agentscope.core.message.TextBlock) Mono.from(tool.callAsync(param))
                .block().getOutput().get(0)).getText();

        assertThat(result).isEmpty();
    }

    @Test
    void given_registration_shape_when_inspected_then_contract_keys_present() {
        assertThat(tool.getName()).isEqualTo("ask_user");
        assertThat(tool.getParameters()).containsKeys("type", "properties", "required");
        // #34：答复键不进 inputSchema——模型不可见该键的存在（自答形状无从学起）
        @SuppressWarnings("unchecked")
        Map<String, Object> properties =
                (Map<String, Object>) tool.getParameters().get("properties");
        assertThat(properties).doesNotContainKey("answer");
        assertThat(tool.isReadOnly()).isTrue();
    }
}
