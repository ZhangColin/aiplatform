package com.semgravio.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.LinkedHashMap;
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
        assertThat(zero.overheadMs()).isEmpty();
    }

    @Test
    void given_four_arg_construction_when_queried_then_overhead_empty() {
        // #453 四参便捷形（既有调用点/测试桩）：开销归因桶缺省空图
        StageDurations durations = new StageDurations(7, Map.of(), Map.of(), Map.of());

        assertThat(durations.llmMs()).isEqualTo(7);
        assertThat(durations.overheadMs()).isEmpty();
    }

    @Test
    void given_overhead_buckets_when_plus_then_merged_by_key() {
        // #453 跨段合并：开销归因桶按键求和（converse + 续跑同口径）；来源用
        // LinkedHashMap——Map.of 多键不保序，保序断言须有序来源
        Map<String, Long> firstOverhead = new LinkedHashMap<>();
        firstOverhead.put("configMs", 3L);
        firstOverhead.put("evalMs", 5L);
        Map<String, Long> secondOverhead = new LinkedHashMap<>();
        secondOverhead.put("configMs", 2L);
        secondOverhead.put("eventPipelineMs", 9L);
        StageDurations first = new StageDurations(0, Map.of(), Map.of(), Map.of(), firstOverhead);
        StageDurations second = new StageDurations(0, Map.of(), Map.of(), Map.of(), secondOverhead);

        StageDurations merged = first.plus(second);

        assertThat(merged.overheadMs()).containsExactly(
                Map.entry("configMs", 5L),
                Map.entry("evalMs", 5L),
                Map.entry("eventPipelineMs", 9L));
    }

    @Test
    void given_source_overhead_map_when_construct_then_snapshot_immutable() {
        // #453 来源 map 的防御拷贝同样适用开销面（观察面继续累积不回流快照）
        Map<String, Long> overhead = new HashMap<>();
        overhead.put("configMs", 3L);
        StageDurations durations = new StageDurations(0, Map.of(), Map.of(), Map.of(), overhead);

        overhead.put("evalMs", 9L);

        assertThat(durations.overheadMs()).containsExactly(Map.entry("configMs", 3L));
    }

    @Test
    void given_two_segments_when_plus_then_counts_summed_and_buckets_merged() {
        StageDurations first = new StageDurations(100,
                Map.of("write_file", 40L), Map.of("install", 1_000L), Map.of("verify", 300L));
        StageDurations second = new StageDurations(50,
                Map.of("write_file", 10L, "read_file", 5L), Map.of("dev", 800L), Map.of());

        StageDurations merged = first.plus(second);

        assertThat(merged.llmMs()).isEqualTo(150);
        assertThat(merged.toolsMs()).containsExactly(
                Map.entry("write_file", 50L), Map.entry("read_file", 5L));
        assertThat(merged.commandMs()).containsExactly(
                Map.entry("install", 1_000L), Map.entry("dev", 800L));
        assertThat(merged.subagentMs()).containsExactly(Map.entry("verify", 300L));
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
