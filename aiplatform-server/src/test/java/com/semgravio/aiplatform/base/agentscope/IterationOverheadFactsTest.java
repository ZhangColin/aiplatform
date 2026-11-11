package com.semgravio.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * {@link IterationOverheadFacts}（#453 每迭代平台开销观察面）：三桶纳秒累计取整
 * 毫秒、门计数、迭代数注入、负值丢弃、六键齐备（缺席为 0）。
 */
class IterationOverheadFactsTest {

    @Test
    void given_nothing_recorded_when_snapshot_then_all_keys_zero() {
        IterationOverheadFacts facts = new IterationOverheadFacts();

        Map<String, Long> snapshot = facts.snapshot(0);

        assertThat(snapshot).containsExactly(
                Map.entry(IterationOverheadFacts.CONFIG_MS, 0L),
                Map.entry(IterationOverheadFacts.EVAL_MS, 0L),
                Map.entry(IterationOverheadFacts.EVENT_PIPELINE_MS, 0L),
                Map.entry(IterationOverheadFacts.GATE_SKIPPED, 0L),
                Map.entry(IterationOverheadFacts.GATE_EVALUATED, 0L),
                Map.entry(IterationOverheadFacts.ITERATIONS, 0L));
    }

    @Test
    void given_nanosecond_recordings_when_snapshot_then_millis_rounded_and_summed() {
        IterationOverheadFacts facts = new IterationOverheadFacts();
        facts.addConfigNanos(1_500_000);      // 1.5ms → 1
        facts.addConfigNanos(2_000_000);      // 累计 3.5ms → 3
        facts.addEvalNanos(3_000_000);        // 3ms
        facts.addEventPipelineNanos(999_999); // 0.999ms → 0（不足 1ms 如实取整）

        Map<String, Long> snapshot = facts.snapshot(7);

        assertThat(snapshot.get(IterationOverheadFacts.CONFIG_MS)).isEqualTo(3);
        assertThat(snapshot.get(IterationOverheadFacts.EVAL_MS)).isEqualTo(3);
        assertThat(snapshot.get(IterationOverheadFacts.EVENT_PIPELINE_MS)).isZero();
        assertThat(snapshot.get(IterationOverheadFacts.ITERATIONS)).isEqualTo(7);
    }

    @Test
    void given_negative_nanos_when_recorded_then_discarded() {
        // 时钟回拨/测量噪声不产生负账
        IterationOverheadFacts facts = new IterationOverheadFacts();
        facts.addConfigNanos(-5);
        facts.addEvalNanos(-1);
        facts.addEventPipelineNanos(-9);

        assertThat(facts.snapshot(0).values()).allSatisfy(value -> assertThat(value).isNotNegative());
    }

    @Test
    void given_gate_counters_when_snapshot_then_counts_carried() {
        IterationOverheadFacts facts = new IterationOverheadFacts();
        facts.noteGateSkipped();
        facts.noteGateSkipped();
        facts.noteGateEvaluated();

        Map<String, Long> snapshot = facts.snapshot(0);

        assertThat(snapshot.get(IterationOverheadFacts.GATE_SKIPPED)).isEqualTo(2);
        assertThat(snapshot.get(IterationOverheadFacts.GATE_EVALUATED)).isEqualTo(1);
    }
}
