package com.semgravio.aiplatform.business.project.domain.decisionhint;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * 选指令＋堆叠上限缝（#437）：从命中信号到单条注入决策的纯函数——未注入过的
 * 新鲜信号按优先级竞争单注入位；本会话已注入的键不重灌（条件清除后重新命中
 * 也不重灌，防振荡）；活跃栈（已注入且条件仍在的键）达上限即不再加新。
 */
class DecisionHintsSelectTest {

    private static HintSignal signal(DecisionHintKind kind) {
        return new HintSignal(kind, "理由");
    }

    private static Optional<HintDecision> select(List<HintSignal> signals,
            List<String> injectedKeys, HintPolicy policy) {
        return DecisionHints.select(signals, injectedKeys, policy);
    }

    @Test
    void given_fresh_signal_when_select_then_decision_with_library_instruction() {
        Optional<HintDecision> decision = select(
                List.of(signal(DecisionHintKind.FAILURE_STOP_LOSS)), List.of(),
                HintPolicy.defaults());

        assertThat(decision).isPresent();
        assertThat(decision.get().kind()).isEqualTo(DecisionHintKind.FAILURE_STOP_LOSS);
        assertThat(decision.get().instruction())
                .isEqualTo(DecisionHintKind.FAILURE_STOP_LOSS.instructionTemplate()
                        .replace("{0}", "理由"));
        assertThat(decision.get().reason()).isEqualTo("理由");
    }

    @Test
    void given_already_injected_kind_when_select_then_not_reinjected() {
        // 已注入过的键（条件仍在）不重复注入
        Optional<HintDecision> decision = select(
                List.of(signal(DecisionHintKind.ANTI_MOCK)),
                List.of(DecisionHintKind.ANTI_MOCK.key()),
                HintPolicy.defaults());

        assertThat(decision).isEmpty();
    }

    @Test
    void given_injected_kind_cleared_and_refiring_when_select_then_still_not_reinjected() {
        // 本会话已注入即不再注入（即使条件曾清除后重新命中——防振荡重灌）
        Optional<HintDecision> decision = select(
                List.of(signal(DecisionHintKind.PARALLEL_TOOLS)),
                List.of(DecisionHintKind.PARALLEL_TOOLS.key()),
                HintPolicy.defaults());

        assertThat(decision).isEmpty();
    }

    @Test
    void given_stack_full_when_select_then_no_new_injection() {
        // 活跃栈＝已注入且条件仍在的键：3 条全活跃 + 第 4 条新鲜信号 → 不加新
        List<String> injected = List.of(DecisionHintKind.FAILURE_STOP_LOSS.key(),
                DecisionHintKind.ANTI_MOCK.key(), DecisionHintKind.PARALLEL_TOOLS.key());
        List<HintSignal> signals = List.of(
                signal(DecisionHintKind.FAILURE_STOP_LOSS),
                signal(DecisionHintKind.ANTI_MOCK),
                signal(DecisionHintKind.PARALLEL_TOOLS),
                signal(DecisionHintKind.CONTEXT_ECONOMY));

        assertThat(select(signals, injected, HintPolicy.defaults())).isEmpty();
    }

    @Test
    void given_cleared_kind_frees_slot_when_select_then_fresh_kind_injected() {
        // A 已注入但条件不再（信号缺失）→ 活跃栈 0；B 新鲜 → 注入 B
        Optional<HintDecision> decision = select(
                List.of(signal(DecisionHintKind.CONTEXT_ECONOMY)),
                List.of(DecisionHintKind.FAILURE_STOP_LOSS.key()),
                HintPolicy.defaults());

        assertThat(decision).isPresent();
        assertThat(decision.get().kind()).isEqualTo(DecisionHintKind.CONTEXT_ECONOMY);
    }

    @Test
    void given_multiple_fresh_signals_when_select_then_highest_priority_wins() {
        Optional<HintDecision> decision = select(
                List.of(signal(DecisionHintKind.PARALLEL_TOOLS),
                        signal(DecisionHintKind.FAILURE_STOP_LOSS),
                        signal(DecisionHintKind.ANTI_MOCK)),
                List.of(), HintPolicy.defaults());

        assertThat(decision).isPresent();
        assertThat(decision.get().kind()).isEqualTo(DecisionHintKind.FAILURE_STOP_LOSS);
    }

    @Test
    void given_no_signals_when_select_then_empty() {
        assertThat(select(List.of(), List.of(), HintPolicy.defaults())).isEmpty();
    }

    @Test
    void given_custom_stack_cap_when_select_then_cap_respected() {
        // 上限 1：一条活跃 + 新鲜 → 不加新；活跃条件消失 → 可加
        HintPolicy capOne = new HintPolicy(3, 1, Set.of());
        List<HintSignal> fresh = List.of(signal(DecisionHintKind.CONTEXT_ECONOMY));
        assertThat(select(fresh, List.of(DecisionHintKind.ANTI_MOCK.key()), capOne))
                .isEmpty();
        assertThat(select(fresh, List.of(), capOne)).isPresent();
    }

    @Test
    void given_decide_composition_when_snapshot_then_classify_and_select() {
        // decide = classify + select 一体入口（中间件唯一调用点）
        TrajectorySnapshot snapshot = new TrajectorySnapshot(List.of(
                new Iteration("", List.of(new ToolCallOutcome("execute", true, "boom", 0))),
                new Iteration("", List.of(new ToolCallOutcome("execute", true, "boom", 0))),
                new Iteration("", List.of(new ToolCallOutcome("execute", true, "boom", 0)))),
                List.of());

        Optional<HintDecision> decision = DecisionHints.decide(snapshot, HintPolicy.defaults());

        assertThat(decision).isPresent();
        assertThat(decision.get().kind()).isEqualTo(DecisionHintKind.FAILURE_STOP_LOSS);
    }
}
