package com.aieducenter.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * {@link StageDurations}（#111 阶段耗时不定形快照）：零值与跨段合并（converse +
 * 续跑各段 plus）语义——计数相加、分桶按键求和、快照不可变。
 */
class StageDurationsTest {

    @Test
    void given_zero_when_queried_then_all_empty() {
        StageDurations zero = StageDurations.zero();

        assertThat(zero.llmMs()).isZero();
        assertThat(zero.toolsMs()).isEmpty();
        assertThat(zero.commandMs()).isEmpty();
        assertThat(zero.subagentMs()).isEmpty();
    }

    @Test
    void given_two_segments_when_plus_then_counts_summed_and_buckets_merged() {
        StageDurations first = new StageDurations(100,
                Map.of("write_file", 40L), Map.of("install", 1_000L), Map.of("self-test", 300L));
        StageDurations second = new StageDurations(50,
                Map.of("write_file", 10L, "read_file", 5L), Map.of("dev", 800L), Map.of());

        StageDurations merged = first.plus(second);

        assertThat(merged.llmMs()).isEqualTo(150);
        assertThat(merged.toolsMs()).containsExactly(
                Map.entry("write_file", 50L), Map.entry("read_file", 5L));
        assertThat(merged.commandMs()).containsExactly(
                Map.entry("install", 1_000L), Map.entry("dev", 800L));
        assertThat(merged.subagentMs()).containsExactly(Map.entry("self-test", 300L));
    }

    @Test
    void given_source_maps_when_construct_then_snapshot_immutable() {
        HashMap<String, Long> tools = new HashMap<>();
        tools.put("write_file", 40L);
        StageDurations durations = new StageDurations(0, tools, Map.of(), Map.of());

        tools.put("edit_file", 9L);

        assertThat(durations.toolsMs()).containsExactly(Map.entry("write_file", 40L));
    }
}
