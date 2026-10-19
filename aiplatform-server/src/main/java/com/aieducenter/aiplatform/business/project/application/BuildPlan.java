package com.aieducenter.aiplatform.business.project.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 构建计划（生成编排交接物，ADR 0009）：主智能体产出 PRD 后顺带产出的有序纵向
 * 切片清单——每片一句用户语言「用户能 X」。平台只执行、不解析 PRD 自由文本
 * （切片数量是 PRD 切片结构的涌现结果，不是平台旋钮）。计划随生成轨道表落库
 * （#220 {@code prj_generation_segments}，生命周期跟 PRD 版本走——PRD 演进即
 * 重产、进程重启不丢），本 record 是交接物形状与轨道执行的读形。
 *
 * <p>阶段 0（先起服骨架）是平台固定的水平工序，不在本计划内——本计划只含纵向
 * 切片；生成轨道（#104）把「阶段 0 + 切片」拼成多 run 轨。</p>
 *
 * <p>无计划不兜假计划（#220 删 minimalFallback）：计划缺失 = 重派主智能体按
 * PRD 补产，见 {@link GenerationAppService}。</p>
 *
 * <p><b>切片并行（#351）</b>：切片清单扩展为「描述 + 依赖」——每片可声明依赖
 * 前面哪几片（{@code dependsOn}，1-based 片序，1 = 首片），平台据此算并行波
 * （{@link SliceSchedule}）。旧平面清单构造保留（全串行 = 每片依赖前一片），
 * 既有调用与测试零改动——依赖是结构化交接物的自然延伸（不解析自由文本）。</p>
 */
public record BuildPlan(List<String> slices, List<Set<Integer>> dependsOn) {

    /** 旧平面清单构造（全串行 = 每片依赖前一片）：既有调用/测试零改动的兼容入口。 */
    public BuildPlan(List<String> slices) {
        this(slices, sequentialDependencies(slices));
    }

    public BuildPlan {
        List<String> copied = List.copyOf(slices);
        if (copied.isEmpty()) {
            throw new IllegalArgumentException("构建计划不能为空：至少一个纵向切片");
        }
        List<Set<Integer>> resolved = dependsOn == null
                ? sequentialDependencies(copied)
                : dependsOn.stream().map(Set::copyOf).toList();
        if (resolved.size() != copied.size()) {
            throw new IllegalArgumentException("依赖列表长度须与切片数一致");
        }
        for (int i = 0; i < copied.size(); i++) {
            int ord = i + 1; // 1-based 片序
            for (int dep : resolved.get(i)) {
                if (dep < 1 || dep > copied.size() || dep == ord) {
                    throw new IllegalArgumentException(
                            "非法依赖片序（越界或自依赖）：切片 " + ord + " 依赖 " + dep);
                }
            }
        }
        slices = copied;
        dependsOn = resolved;
    }

    /** 第 {@code index}（0-based）片依赖的片序集（1-based；空 = 无依赖可并行）。 */
    public Set<Integer> dependsOn(int index) {
        return dependsOn.get(index);
    }

    /** 全串行依赖（旧平面清单口径）：每片依赖前一片（首片无依赖、恒先行）。 */
    public static List<Set<Integer>> sequentialDependencies(List<String> slices) {
        List<Set<Integer>> deps = new ArrayList<>(slices.size());
        for (int i = 0; i < slices.size(); i++) {
            // 片 i（0-based）＝ 片序 i+1；串行 = 依赖前一片（片序 i）
            deps.add(i == 0 ? Set.of() : Set.of(i));
        }
        return deps;
    }
}
