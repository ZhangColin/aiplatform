package com.semgravio.aiplatform.business.project.domain.decisionhint;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 决策时微指令分类器其余三条规则（#437）：反 mock 数据／上下文经济性／并行
 * 工具调用——各自触发/不触发/截窗口径。与失败止损规则同缝（纯函数判定）。
 */
class DecisionHintsOtherRulesTest {

    private static ToolCallOutcome ok(String tool, int resultChars) {
        return new ToolCallOutcome(tool, false, null, resultChars);
    }

    private static Iteration text(String assistantText, ToolCallOutcome... calls) {
        return new Iteration(assistantText, List.of(calls));
    }

    private static List<HintSignal> classify(TrajectorySnapshot snapshot) {
        return DecisionHints.classify(snapshot, HintPolicy.defaults());
    }

    // ---------- 反 mock：近期产出出现占位痕迹 → 信号 ----------

    @Test
    void given_placeholder_written_recently_when_classify_then_anti_mock_signal() {
        TrajectorySnapshot snapshot = new TrajectorySnapshot(List.of(
                text("我在写首页", ok("write_file", 800)),
                text("继续写列表页，先放个 lorem ipsum 占位",
                        ok("write_file", 900))),
                List.of());

        assertThat(classify(snapshot)).extracting(HintSignal::kind)
                .containsExactly(DecisionHintKind.ANTI_MOCK);
    }

    @Test
    void given_placeholder_in_tool_result_when_classify_then_anti_mock_signal() {
        // 信号扫描面＝assistant 文本＋成功结果文本；write_file 的结果回显内容也算
        TrajectorySnapshot snapshot = new TrajectorySnapshot(List.of(
                text("", ok("write_file", 500)),
                text("", new ToolCallOutcome("write_file", false, "TODO: implement later", 500))),
                List.of());

        assertThat(classify(snapshot)).extracting(HintSignal::kind)
                .containsExactly(DecisionHintKind.ANTI_MOCK);
    }

    @Test
    void given_placeholder_only_in_old_iteration_when_classify_then_no_signal() {
        // 扫描窗口＝近期 3 个迭代：更早的痕迹不再追打（避免栈位被旧账长占）
        TrajectorySnapshot snapshot = new TrajectorySnapshot(List.of(
                text("lorem ipsum 先占位", ok("write_file", 500)),
                text("补真实数据", ok("write_file", 500), ok("write_file", 300)),
                text("继续", ok("write_file", 500)),
                text("再写一个组件", ok("write_file", 500))),
                List.of());

        assertThat(classify(snapshot)).isEmpty();
    }

    @Test
    void given_clean_content_when_classify_then_no_anti_mock_signal() {
        TrajectorySnapshot snapshot = new TrajectorySnapshot(List.of(
                text("写用户注册页面", ok("write_file", 1200)),
                text("写登录接口", ok("write_file", 900))),
                List.of());

        assertThat(classify(snapshot)).isEmpty();
    }

    // ---------- 上下文经济性：最近一次结果过大 → 信号 ----------

    @Test
    void given_huge_latest_result_when_classify_then_context_economy_signal() {
        TrajectorySnapshot snapshot = new TrajectorySnapshot(List.of(
                text("", ok("read_file", 12_000)),
                text("", ok("read_file", 50_000))),
                List.of());

        assertThat(classify(snapshot)).extracting(HintSignal::kind)
                .containsExactly(DecisionHintKind.CONTEXT_ECONOMY);
        assertThat(classify(snapshot).get(0).reason()).contains("50000");
    }

    @Test
    void given_huge_result_not_latest_when_classify_then_no_signal() {
        // 只打最近一次读取：下一步决策 relevant 的是刚发生的 oversized 读取
        TrajectorySnapshot snapshot = new TrajectorySnapshot(List.of(
                text("", ok("read_file", 50_000)),
                text("", ok("read_file", 300))),
                List.of());

        assertThat(classify(snapshot)).isEmpty();
    }

    @Test
    void given_result_below_large_threshold_when_classify_then_no_signal() {
        TrajectorySnapshot snapshot = new TrajectorySnapshot(List.of(
                text("", ok("read_file", 19_999))),
                List.of());

        assertThat(classify(snapshot)).isEmpty();
    }

    // ---------- 并行工具调用：尾部连续多迭代单调用 → 信号 ----------

    @Test
    void given_single_call_streak_when_classify_then_parallel_tools_signal() {
        TrajectorySnapshot snapshot = new TrajectorySnapshot(List.of(
                text("", ok("read_file", 300)),
                text("", ok("read_file", 300)),
                text("", ok("read_file", 300)),
                text("", ok("read_file", 300))),
                List.of());

        assertThat(classify(snapshot)).extracting(HintSignal::kind)
                .containsExactly(DecisionHintKind.PARALLEL_TOOLS);
    }

    @Test
    void given_below_single_call_streak_when_classify_then_no_signal() {
        TrajectorySnapshot snapshot = new TrajectorySnapshot(List.of(
                text("", ok("read_file", 300)),
                text("", ok("read_file", 300)),
                text("", ok("read_file", 300))),
                List.of());

        assertThat(classify(snapshot)).isEmpty();
    }

    @Test
    void given_multi_call_iteration_breaks_streak_when_classify_then_no_signal() {
        // 模型已经并行过 → 信号清除（堆叠语义：条件消失即释放槽位）
        TrajectorySnapshot snapshot = new TrajectorySnapshot(List.of(
                text("", ok("read_file", 300)),
                text("", ok("read_file", 300), ok("read_file", 300)),
                text("", ok("read_file", 300))),
                List.of());

        assertThat(classify(snapshot)).isEmpty();
    }

    @Test
    void given_mixed_signals_when_classify_then_sorted_by_priority() {
        // 同轮多信号并存：失败止损 > 反 mock > 上下文经济 > 并行（priority 序）；
        // 反 mock 痕迹须在近期 3 迭代扫描窗口内（本例骑在倒数第 2 个失败迭代的
        // assistant 文本上——止损序列仍连续）
        ToolCallOutcome boom = new ToolCallOutcome("execute", true, "boom", 0);
        TrajectorySnapshot snapshot = new TrajectorySnapshot(List.of(
                text("", boom),
                text("", boom),
                text("placeholder 先顶着", boom),
                text("", boom)),
                List.of());

        assertThat(classify(snapshot)).extracting(HintSignal::kind).containsExactly(
                DecisionHintKind.FAILURE_STOP_LOSS,
                DecisionHintKind.ANTI_MOCK,
                DecisionHintKind.PARALLEL_TOOLS);
    }
}
