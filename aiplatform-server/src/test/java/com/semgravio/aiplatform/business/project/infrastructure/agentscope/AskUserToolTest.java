package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.semgravio.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.semgravio.aiplatform.business.project.infrastructure.InterviewToolGate;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

/**
 * {@link AskUserTool}（#48 挂起源；2026-11-17 提问侧额度闸）：工具自检按提问侧闸
 * 分形——五问未答满 ASK 挂起（RequireUserConfirmEvent → 平台问答卡 QUESTION）；
 * 五问答满 PASSTHROUGH 由执行体回错误结果（不挂起、不出卡）；闸查询异常 fail-open。
 * 挂起批复的答复经 block metadata 注入执行体（模型不可见通道，#34：答复不进 input）。
 */
class AskUserToolTest {

    /** 闸替身：预设拒因/异常，记录提问侧查询次数。 */
    private static final class StubGate extends InterviewToolGate {
        String blockReason;
        RuntimeException failure;
        final AtomicInteger questionChecks = new AtomicInteger();

        StubGate() {
            super(null, null, null);
        }

        @Override
        public String questionBlockReason(String workspaceId) {
            questionChecks.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return blockReason;
        }
    }

    private final StubGate gate = new StubGate();
    private final AskUserTool tool = new AskUserTool("42", gate);

    @Test
    void given_quota_open_when_check_permissions_then_ask() {
        // 未答满五问（闸 null）→ ASK = 调用即挂起（RequireUserConfirmEvent → 问答卡）
        var decision = tool.checkPermissions(Map.of("question", "用哪个框架?"), null).block();

        assertThat(decision.getBehavior()).isEqualTo(PermissionBehavior.ASK);
        assertThat(gate.questionChecks).hasValue(1);
    }

    @Test
    void given_answer_key_in_input_when_check_permissions_then_still_ask() {
        // #34 回归守卫：input 带 answer 键（模型编造的「自答」形状）不放行——
        // 放行只来自挂起批复（内核置 ALLOWED 整体跳过权限引擎）
        var decision = tool.checkPermissions(
                Map.of("question", "用哪个框架?", "answer", "模型编造的答案"), null).block();

        assertThat(decision.getBehavior()).isEqualTo(PermissionBehavior.ASK);
    }

    @Test
    void given_quota_exhausted_when_check_permissions_then_passthrough_not_ask() {
        // 提问侧额度闸：五问答满 → PASSTHROUGH（不挂起、不发 RequireUserConfirmEvent、
        // 不出第 6 张问答卡——实测缺陷 381073661124370156 的工具点拦截）
        gate.blockReason = "平台拒绝本次 ask_user：五问额度已用尽（已答 5/5 问）……savePrd";

        var decision = tool.checkPermissions(Map.of("question", "第六问?"), null).block();

        assertThat(decision.getBehavior()).isEqualTo(PermissionBehavior.PASSTHROUGH);
    }

    @Test
    void given_quota_exhausted_when_called_without_answer_then_error_result_with_reason() {
        // PASSTHROUGH 进执行体：无批复答复 → 闸拒因作为错误结果回模型（同 savePrd 闸拒
        // 形态），指引当轮停止提问转 savePrd；不挂起
        gate.blockReason = "平台拒绝本次 ask_user：五问额度已用尽（已答 5/5 问），立即 savePrd";

        ToolResultBlock result = Mono.from(tool.callAsync(param(Map.of("question", "第六问?"), null)))
                .block();

        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(resultText(result)).contains("5/5").contains("savePrd");
    }

    @Test
    void given_gate_failure_when_check_permissions_then_fail_open_ask() {
        // 闸查询异常（库抖动）：fail-open 照常挂起——轮落定收窄链仍是兜底，不卡死访谈
        gate.failure = new RuntimeException("db down");

        var decision = tool.checkPermissions(Map.of("question", "用哪个框架?"), null).block();

        assertThat(decision.getBehavior()).isEqualTo(PermissionBehavior.ASK);
    }

    @Test
    void given_answer_in_block_metadata_when_called_then_answer_returned_without_gate_check() {
        // 挂起批复续跑：答复经 block metadata（模型不可见）直读回模型，优先于闸——
        // 已合法挂起的问答不受其后额度状态变化影响，且不重复查闸
        ToolResultBlock result = Mono.from(tool.callAsync(param(
                Map.of("question", "用哪个框架?"),
                Map.of(AgentscopeAgentClient.ANSWER_METADATA_KEY, "Spring Boot")))).block();

        assertThat(result.getState()).isNotEqualTo(ToolResultState.ERROR);
        assertThat(resultText(result)).isEqualTo("Spring Boot");
        assertThat(gate.questionChecks).hasValue(0);
    }

    @Test
    void given_no_metadata_and_quota_open_when_called_then_empty_result() {
        // 非「用户作答续跑」且闸放行（异常面）：空结果
        ToolResultBlock result = Mono.from(tool.callAsync(param(Map.of("question", "用哪个框架?"), null)))
                .block();

        assertThat(resultText(result)).isEmpty();
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
        // #459 推荐答案通道：recommended 入参位（推荐项，缺省不标＝行为兼容）
        assertThat(properties).containsKey("recommended");
        assertThat(tool.isReadOnly()).isTrue();
    }

    // ---------- 内部 ----------

    private static ToolCallParam param(Map<String, Object> input, Map<String, Object> metadata) {
        ToolUseBlock block = metadata == null
                ? new ToolUseBlock("tc-1", AskUserTool.NAME, input, null)
                : new ToolUseBlock("tc-1", AskUserTool.NAME, input, null, metadata);
        return ToolCallParam.builder()
                .toolUseBlock(block)
                .input(input)
                .build();
    }

    private static String resultText(ToolResultBlock result) {
        return ((io.agentscope.core.message.TextBlock) result.getOutput().get(0)).getText();
    }
}
