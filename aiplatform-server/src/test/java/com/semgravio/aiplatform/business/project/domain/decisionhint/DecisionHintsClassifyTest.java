package com.semgravio.aiplatform.business.project.domain.decisionhint;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * 决策时微指令分类器判定缝（#437，#432 Testing Decisions 预定的独立单测缝——
 * 「判定+选指令纯函数」，不进编排缝）：输入＝轨迹快照（迭代序列＋本会话已注入
 * 微指令键），输出＝命中信号清单。判定规则全部确定性（规则分类器，零模型调用
 * ——flash 档研究口径由 #437 报告说明），信号含理由文本供注入与观测。
 */
class DecisionHintsClassifyTest {

    private static ToolCallOutcome ok(String tool, int resultChars) {
        return new ToolCallOutcome(tool, false, null, resultChars);
    }

    private static ToolCallOutcome fail(String tool, String errorText) {
        return new ToolCallOutcome(tool, true, errorText, 0);
    }

    private static Iteration iteration(ToolCallOutcome... calls) {
        return new Iteration("", List.of(calls));
    }

    private static List<HintSignal> classify(TrajectorySnapshot snapshot) {
        return DecisionHints.classify(snapshot, HintPolicy.defaults());
    }

    // ---------- 失败止损：同类错误连续 N 次（缺省 3）→ 信号 ----------

    @Test
    void given_same_error_repeated_at_threshold_when_classify_then_stop_loss_signal() {
        TrajectorySnapshot snapshot = new TrajectorySnapshot(List.of(
                iteration(ok("write_file", 120), ok("read_file", 100)),
                iteration(fail("execute", "Error: port 8081 in use")),
                iteration(fail("execute", "Error: port 8081 in use")),
                iteration(fail("execute", "Error: port 8081 in use"))),
                List.of());

        List<HintSignal> signals = classify(snapshot);

        assertThat(signals).extracting(HintSignal::kind)
                .containsExactly(DecisionHintKind.FAILURE_STOP_LOSS);
        assertThat(signals.get(0).reason()).contains("3");
    }

    @Test
    void given_same_error_below_threshold_when_classify_then_no_signal() {
        TrajectorySnapshot snapshot = new TrajectorySnapshot(List.of(
                iteration(fail("execute", "Error: port 8081 in use")),
                iteration(fail("execute", "Error: port 8081 in use"))),
                List.of());

        assertThat(classify(snapshot)).isEmpty();
    }

    @Test
    void given_success_resets_streak_when_classify_then_no_signal() {
        TrajectorySnapshot snapshot = new TrajectorySnapshot(List.of(
                iteration(fail("execute", "Error: port 8081 in use")),
                iteration(fail("execute", "Error: port 8081 in use")),
                iteration(ok("execute", 80), ok("execute", 60)),
                iteration(fail("execute", "Error: port 8081 in use")),
                iteration(fail("execute", "Error: port 8081 in use"))),
                List.of());

        assertThat(classify(snapshot)).isEmpty();
    }

    @Test
    void given_different_error_breaks_streak_when_classify_then_no_signal() {
        // 同类错误才算同一止损序列：错误内容变了（不同根因）即换序列
        TrajectorySnapshot snapshot = new TrajectorySnapshot(List.of(
                iteration(fail("execute", "Error: port 8081 in use"),
                        fail("execute", "Error: port 8081 in use")),
                iteration(fail("execute", "TypeError: undefined is not a function")),
                iteration(fail("execute", "TypeError: undefined is not a function"))),
                List.of());

        assertThat(classify(snapshot)).isEmpty();
    }

    @Test
    void given_error_case_differs_only_when_classify_then_same_signature() {
        // 签名归一化：错误首行去空白、忽略大小写——仅大小写差异仍算同类
        TrajectorySnapshot snapshot = new TrajectorySnapshot(List.of(
                iteration(fail("execute", "  error: Port 8081 in use  ")),
                iteration(fail("execute", "Error: port 8081 in use")),
                iteration(fail("execute", "error: PORT 8081 IN USE"))),
                List.of());

        assertThat(classify(snapshot)).extracting(HintSignal::kind)
                .containsExactly(DecisionHintKind.FAILURE_STOP_LOSS);
    }

    @Test
    void given_custom_threshold_when_classify_then_uses_policy_value() {
        TrajectorySnapshot snapshot = new TrajectorySnapshot(List.of(
                iteration(fail("execute", "boom")),
                iteration(fail("execute", "boom"))),
                List.of());

        HintPolicy policy = new HintPolicy(2, 3, Set.of());
        assertThat(DecisionHints.classify(snapshot, policy))
                .extracting(HintSignal::kind)
                .containsExactly(DecisionHintKind.FAILURE_STOP_LOSS);
    }

    @Test
    void given_disabled_kind_when_classify_then_no_signal() {
        TrajectorySnapshot snapshot = new TrajectorySnapshot(List.of(
                iteration(fail("execute", "boom")),
                iteration(fail("execute", "boom")),
                iteration(fail("execute", "boom"))),
                List.of());

        HintPolicy policy = new HintPolicy(3, 3, Set.of(DecisionHintKind.FAILURE_STOP_LOSS));
        assertThat(DecisionHints.classify(snapshot, policy)).isEmpty();
    }
}
