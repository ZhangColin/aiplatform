package com.aieducenter.aiplatform.business.project.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 切片并行调度（#351）：把依赖感知的构建计划拆成并行波。阶段 0（先起服）与切片 1
 * （界面先行）恒单独前置（跑在主工作区，预览先活），此后切片 2..N 按拓扑分层——
 * 片层 = 1 + max(依赖片的层)，无依赖片的层 = 1。同层（波）无互依赖即并行跑；层间
 * 串行（后层依赖前层合并产物）。
 *
 * <p>纯函数、无副作用（单测覆盖点）。依赖成环抛 {@link IllegalArgumentException}
 * ——非法计划不派 run（由计划补产兜底重产，见 {@link GenerationAppService}）。</p>
 */
public final class SliceSchedule {

    private SliceSchedule() {
    }

    /**
     * 并行波划分（切片 2..N）：返回按层升序的波列表，每波 = 一组可并行跑的 1-based
     * 片序（升序）。切片数 &le; 1（仅首片或无片）返回空列表——无并行段。
     */
    public static List<List<Integer>> parallelWaves(BuildPlan plan) {
        int n = plan.slices().size();
        if (n <= 1) {
            return List.of();
        }
        // level[ord] = 拓扑层（ord 1-based）；state：0 未访 / 1 访中（环检测）/ 2 已定
        int[] level = new int[n + 1];
        int[] state = new int[n + 1];
        level[1] = 0;
        state[1] = 2; // 首片固定层 0（先行，不进并行波）
        for (int ord = 2; ord <= n; ord++) {
            computeLevel(ord, plan, level, state);
        }
        Map<Integer, List<Integer>> grouped = new TreeMap<>();
        for (int ord = 2; ord <= n; ord++) {
            grouped.computeIfAbsent(level[ord], key -> new ArrayList<>()).add(ord);
        }
        return grouped.values().stream().map(List::copyOf).toList();
    }

    /** 递归求层（记忆化 + 环检测）：层 = 1 + max(依赖片的层)；无依赖 = 1。 */
    private static int computeLevel(int ord, BuildPlan plan, int[] level, int[] state) {
        if (state[ord] == 1) {
            throw new IllegalArgumentException("切片计划存在循环依赖，无法并行调度");
        }
        if (state[ord] == 2) {
            return level[ord];
        }
        state[ord] = 1;
        int maxDep = 0;
        for (int dep : plan.dependsOn(ord - 1)) {
            maxDep = Math.max(maxDep, computeLevel(dep, plan, level, state));
        }
        level[ord] = maxDep + 1;
        state[ord] = 2;
        return level[ord];
    }
}
