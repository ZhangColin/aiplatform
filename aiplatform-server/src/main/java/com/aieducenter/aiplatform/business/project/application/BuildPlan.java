package com.aieducenter.aiplatform.business.project.application;

import java.util.List;

/**
 * 构建计划（生成编排交接物，ADR 0009）：主智能体产出 PRD 后顺带产出的有序纵向
 * 切片清单——每片一句用户语言「用户能 X」。平台只顺序执行、不解析 PRD 自由文本
 * （切片数量是 PRD 切片结构的涌现结果，不是平台旋钮）。计划随生成轨道表落库
 * （#220 {@code prj_generation_segments}，生命周期跟 PRD 版本走——PRD 演进即
 * 重产、进程重启不丢），本 record 是交接物形状与轨道执行的读形。
 *
 * <p>阶段 0（先起服骨架）是平台固定的水平工序，不在本计划内——本计划只含纵向
 * 切片；生成轨道（#104）把「阶段 0 + 逐片」拼成多 run 轨。</p>
 *
 * <p>无计划不兜假计划（#220 删 minimalFallback）：计划缺失 = 重派主智能体按
 * PRD 补产，见 {@link GenerationAppService}。</p>
 */
public record BuildPlan(List<String> slices) {

    public BuildPlan {
        slices = List.copyOf(slices);
        if (slices.isEmpty()) {
            throw new IllegalArgumentException("构建计划不能为空：至少一个纵向切片");
        }
    }
}
