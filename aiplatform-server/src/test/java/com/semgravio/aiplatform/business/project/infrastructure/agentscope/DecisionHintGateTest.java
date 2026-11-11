package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.semgravio.aiplatform.business.project.domain.decisionhint.DecisionHintKind;
import com.semgravio.aiplatform.business.project.domain.decisionhint.HintPolicy;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;

/**
 * 判定门缝（#453-3 降频件）：{@link TrajectorySnapshots#mayProduceSignal} 的
 * 双向口径——门断必空（false＝判定必返空，可安全跳过重评估）与各规则触发形态
 * 的放行（true）。门是单向保守的：只许漏跳过（多评估一回，行为不变）、
 * 不许漏放行（漏跳过一个真信号＝行为回归）。阈值/窗口/模式常量与
 * {@code DecisionHints} 同源——口径漂移由本缝与规则缝的双向断言对齐。
 */
class DecisionHintGateTest {

    private static final HintPolicy DEFAULTS = HintPolicy.defaults();

    private static Msg user(String text) {
        return Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text(text).build())
                .build();
    }

    /** 单调用迭代：一次调用＋结果（状态/文本可定制）。 */
    private static void singleCall(List<Msg> messages, int seq, ToolResultState state,
            String resultText) {
        parallelCalls(messages, seq, 1, state, resultText);
    }

    /** 多调用迭代：count 个并行调用＋各自结果（同状态/文本）。 */
    private static void parallelCalls(List<Msg> messages, int seq, int count,
            ToolResultState state, String resultText) {
        List<ToolUseBlock> calls = new ArrayList<>();
        List<ToolResultBlock> results = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ToolUseBlock call = new ToolUseBlock("c" + seq + "-" + i, "execute",
                    Map.of("cmd", "echo " + seq));
            calls.add(call);
            results.add(new ToolResultBlock(call.getId(), call.getName(),
                    TextBlock.builder().text(resultText).build()).withState(state));
        }
        messages.add(Msg.builder().role(MsgRole.ASSISTANT).content(List.copyOf(calls)).build());
        messages.add(Msg.builder().role(MsgRole.TOOL).content(List.copyOf(results)).build());
    }

    /** 健康轨迹：N 个双调用全成功小结果迭代（并行 streak 恒被 2 调用宽迭代截断）。 */
    private static List<Msg> healthy(int iterations) {
        List<Msg> messages = new ArrayList<>();
        messages.add(user("做订单页"));
        for (int i = 0; i < iterations; i++) {
            parallelCalls(messages, i, 2, ToolResultState.SUCCESS, "ok " + i);
        }
        return messages;
    }

    @Test
    void given_healthy_trajectory_when_gate_then_no_signal_possible() {
        // 长健康段 → 门必空（失败旧账被成功截断、窗口无 mock 痕迹、结果量小、宽迭代截 streak）
        assertThat(TrajectorySnapshots.mayProduceSignal(healthy(12), DEFAULTS)).isFalse();
    }

    @Test
    void given_tail_failure_when_gate_then_passed_to_full_eval() {
        List<Msg> messages = healthy(3);
        singleCall(messages, 99, ToolResultState.ERROR, "Error: boom");

        assertThat(TrajectorySnapshots.mayProduceSignal(messages, DEFAULTS)).isTrue();
    }

    @Test
    void given_failure_truncated_by_success_when_gate_then_no_signal() {
        // 失败被最近一次成功截断——失败止损扫描首个即 break，信号不可能
        List<Msg> messages = new ArrayList<>();
        messages.add(user("做订单页"));
        singleCall(messages, 1, ToolResultState.ERROR, "Error: boom");
        singleCall(messages, 2, ToolResultState.SUCCESS, "ok");

        assertThat(TrajectorySnapshots.mayProduceSignal(messages, DEFAULTS)).isFalse();
    }

    @Test
    void given_pending_call_without_result_when_gate_then_passed() {
        // 尾部调用结果未到达（无结果＝失败面）→ 放行（保守不漏）
        List<Msg> messages = healthy(2);
        messages.add(Msg.builder().role(MsgRole.ASSISTANT)
                .content(new ToolUseBlock("pending-1", "execute", Map.of("cmd", "echo x")))
                .build());

        assertThat(TrajectorySnapshots.mayProduceSignal(messages, DEFAULTS)).isTrue();
    }

    @Test
    void given_mock_pattern_in_recent_assistant_text_when_gate_then_passed() {
        List<Msg> messages = healthy(2);
        messages.add(Msg.builder().role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text("先用 lorem ipsum 占位").build())
                .build());

        assertThat(TrajectorySnapshots.mayProduceSignal(messages, DEFAULTS)).isTrue();
    }

    @Test
    void given_mock_pattern_beyond_window_when_gate_then_no_signal() {
        // 痕迹只存在于 3 个迭代窗口之外（前 2 迭代）——反 mock 规则不追打，门同步关闭
        List<Msg> messages = new ArrayList<>();
        messages.add(user("做订单页"));
        for (int i = 0; i < 5; i++) {
            String resultText = i < 2 ? "placeholder content " + i : "ok " + i;
            parallelCalls(messages, i, 2, ToolResultState.SUCCESS, resultText);
        }

        assertThat(TrajectorySnapshots.mayProduceSignal(messages, DEFAULTS)).isFalse();
    }

    @Test
    void given_oversized_last_result_when_gate_then_passed() {
        List<Msg> messages = healthy(2);
        singleCall(messages, 99, ToolResultState.SUCCESS, "x".repeat(20_001));

        assertThat(TrajectorySnapshots.mayProduceSignal(messages, DEFAULTS)).isTrue();
    }

    @Test
    void given_cumulative_results_over_threshold_when_gate_then_passed() {
        // 单次均不超大（8k < 20k），累计 8 迭代 × 2 调用 × 8k=128k > 120k → 累计判据命中
        List<Msg> messages = new ArrayList<>();
        messages.add(user("做词库"));
        for (int i = 0; i < 8; i++) {
            parallelCalls(messages, i, 2, ToolResultState.SUCCESS, "w".repeat(8_000));
        }

        assertThat(TrajectorySnapshots.mayProduceSignal(messages, DEFAULTS)).isTrue();
    }

    @Test
    void given_cumulative_below_threshold_when_gate_then_no_signal() {
        // 6 迭代 × 2 调用 × 8k=96k < 120k，单次 8k < 20k，宽迭代截 streak → 门闭
        List<Msg> messages = new ArrayList<>();
        messages.add(user("做词库"));
        for (int i = 0; i < 6; i++) {
            parallelCalls(messages, i, 2, ToolResultState.SUCCESS, "w".repeat(8_000));
        }

        assertThat(TrajectorySnapshots.mayProduceSignal(messages, DEFAULTS)).isFalse();
    }

    @Test
    void given_single_call_streak_when_gate_then_passed() {
        // 尾部连续 4 个迭代恰 1 调用 → 并行工具信号
        List<Msg> messages = new ArrayList<>();
        messages.add(user("做订单页"));
        for (int i = 0; i < 4; i++) {
            singleCall(messages, i, ToolResultState.SUCCESS, "ok " + i);
        }

        assertThat(TrajectorySnapshots.mayProduceSignal(messages, DEFAULTS)).isTrue();
    }

    @Test
    void given_streak_broken_by_parallel_iteration_when_gate_then_no_signal() {
        // 宽迭代（2 调用）截断 streak → 并行信号不可能
        List<Msg> messages = new ArrayList<>();
        messages.add(user("做订单页"));
        singleCall(messages, 1, ToolResultState.SUCCESS, "ok");
        parallelCalls(messages, 2, 2, ToolResultState.SUCCESS, "ok");
        singleCall(messages, 3, ToolResultState.SUCCESS, "ok");
        singleCall(messages, 4, ToolResultState.SUCCESS, "ok");

        assertThat(TrajectorySnapshots.mayProduceSignal(messages, DEFAULTS)).isFalse();
    }

    @Test
    void given_previous_round_failures_when_gate_then_cleared_at_round_boundary() {
        // 轮界：真实用户消息清栈——上一轮的失败条带不带进本轮判定
        List<Msg> messages = new ArrayList<>();
        messages.add(user("第一轮"));
        singleCall(messages, 1, ToolResultState.ERROR, "Error: boom");
        singleCall(messages, 2, ToolResultState.ERROR, "Error: boom");
        messages.add(user("第二轮"));
        parallelCalls(messages, 3, 2, ToolResultState.SUCCESS, "ok");

        assertThat(TrajectorySnapshots.mayProduceSignal(messages, DEFAULTS)).isFalse();
    }

    @Test
    void given_all_kinds_disabled_when_gate_then_no_signal_even_on_failures() {
        HintPolicy allDisabled = new HintPolicy(3, 3, Set.of(DecisionHintKind.values()));
        List<Msg> messages = new ArrayList<>();
        messages.add(user("做订单页"));
        singleCall(messages, 1, ToolResultState.ERROR, "Error: boom");

        assertThat(TrajectorySnapshots.mayProduceSignal(messages, allDisabled)).isFalse();
    }

    @Test
    void given_stop_loss_disabled_when_gate_then_tail_failure_not_passed() {
        // 仅失败止损禁用：尾部失败不再放行；其余规则无触发形态 → 门闭
        HintPolicy noStopLoss = new HintPolicy(3, 3, Set.of(DecisionHintKind.FAILURE_STOP_LOSS));
        List<Msg> messages = new ArrayList<>();
        messages.add(user("做订单页"));
        parallelCalls(messages, 1, 2, ToolResultState.ERROR, "Error: boom");

        assertThat(TrajectorySnapshots.mayProduceSignal(messages, noStopLoss)).isFalse();
    }

    @Test
    void given_null_policy_when_gate_then_passed_conservatively() {
        assertThat(TrajectorySnapshots.mayProduceSignal(healthy(1), null)).isTrue();
    }

    @Test
    void given_empty_messages_when_gate_then_no_signal() {
        assertThat(TrajectorySnapshots.mayProduceSignal(List.of(), DEFAULTS)).isFalse();
    }
}
