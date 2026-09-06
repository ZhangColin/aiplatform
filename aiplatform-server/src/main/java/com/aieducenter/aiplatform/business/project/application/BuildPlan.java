package com.aieducenter.aiplatform.business.project.application;

import java.util.List;

/**
 * 构建计划（生成编排交接物，ADR 0009）：主智能体产出 PRD 后顺带产出的有序纵向
 * 切片清单——每片一句用户语言「用户能 X」。平台只顺序执行、不解析 PRD 自由文本
 * （切片数量是 PRD 切片结构的涌现结果，不是平台旋钮）。进程内交接物（run 无表
 * 口径）：重启即清，与切片计划事实（{@link BuildPlanFacts}）同取舍。
 *
 * <p>阶段 0（先起服骨架）是平台固定的水平工序，不在本计划内——本计划只含纵向
 * 切片；生成轨道（#104）把「阶段 0 + 逐片」拼成多 run 轨。</p>
 */
public record BuildPlan(List<String> slices) {

    public BuildPlan {
        slices = List.copyOf(slices);
        if (slices.isEmpty()) {
            throw new IllegalArgumentException("构建计划不能为空：至少一个纵向切片");
        }
    }

    /**
     * 无计划兜底（主智能体未产出切片计划）：退化为最小一段——整个系统作为一个纵向
     * 切片（配平台固定的阶段 0 即「最小两段」）。守卫不派会倒退 #101 生成无门
     * （PRD 产出即自动生成），故取退化而非守卫。
     */
    public static BuildPlan minimalFallback() {
        return new BuildPlan(List.of("用户能使用 PRD 描述的全部功能"));
    }
}
