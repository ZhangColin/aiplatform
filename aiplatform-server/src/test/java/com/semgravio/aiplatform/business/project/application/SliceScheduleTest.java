package com.semgravio.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * {@link SliceSchedule}（#351 切片并行）：依赖感知计划的并行波划分纯函数——首片
 * 恒先行（不进并行波），切片 2..N 按拓扑分层；有环拒跑。
 */
class SliceScheduleTest {

    @Test
    void given_flat_sequential_plan_when_schedule_then_one_slice_per_wave() {
        // 旧口径全串行：每片依赖前一片 → 每波一片（并行度 0 降级，行为等价串行）
        BuildPlan plan = new BuildPlan(List.of("首片", "第二片", "第三片"));

        assertThat(SliceSchedule.parallelWaves(plan))
                .containsExactly(List.of(2), List.of(3));
    }

    @Test
    void given_independent_slices_when_schedule_then_single_parallel_wave() {
        BuildPlan plan = new BuildPlan(
                List.of("首片", "独立甲", "独立乙", "独立丙"),
                List.of(Set.of(), Set.of(), Set.of(), Set.of()));

        assertThat(SliceSchedule.parallelWaves(plan))
                .containsExactly(List.of(2, 3, 4));
    }

    @Test
    void given_chain_when_schedule_then_three_waves() {
        // 首片 → 2 → 3 → 4 链式：三波各一片
        BuildPlan plan = new BuildPlan(
                List.of("首片", "依赖首片", "依赖二片", "依赖三片"),
                List.of(Set.of(), Set.of(1), Set.of(2), Set.of(3)));

        assertThat(SliceSchedule.parallelWaves(plan))
                .containsExactly(List.of(2), List.of(3), List.of(4));
    }

    @Test
    void given_mixed_dependencies_when_schedule_then_levels_grouped() {
        // 首片(1) → {2, 3} 并行；4 依赖 2；5 依赖 2 和 3 → 波1={2,3}，波2={4,5}
        BuildPlan plan = new BuildPlan(
                List.of("首片", "登录", "商品浏览", "下单", "个人中心"),
                List.of(
                        Set.of(),
                        Set.of(1),
                        Set.of(1),
                        Set.of(2),
                        Set.of(2, 3)));

        assertThat(SliceSchedule.parallelWaves(plan))
                .containsExactly(List.of(2, 3), List.of(4, 5));
    }

    @Test
    void given_single_slice_plan_when_schedule_then_no_parallel_waves() {
        BuildPlan plan = new BuildPlan(List.of("唯一片"));

        assertThat(SliceSchedule.parallelWaves(plan)).isEmpty();
    }

    @Test
    void given_cyclic_dependencies_when_schedule_then_rejected() {
        // 2 依赖 3、3 依赖 2 → 环
        BuildPlan plan = new BuildPlan(
                List.of("首片", "甲", "乙"),
                List.of(Set.of(), Set.of(3), Set.of(2)));

        assertThatThrownBy(() -> SliceSchedule.parallelWaves(plan))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("循环依赖");
    }

    @Test
    void given_out_of_range_or_self_dependency_when_construct_then_rejected() {
        assertThatThrownBy(() -> new BuildPlan(
                List.of("首片", "甲"),
                List.of(Set.of(), Set.of(2)))) // 自依赖
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BuildPlan(
                List.of("首片", "甲"),
                List.of(Set.of(), Set.of(9)))) // 越界
                .isInstanceOf(IllegalArgumentException.class);
    }
}
