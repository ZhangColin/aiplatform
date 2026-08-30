import type { components } from "@/lib/api/schema";

/**
 * 主链段序列推导（共享层资产，issue #19）：`stages[]` 是主链定义的唯一源
 * （spec 0002 §6），代码不写死六段——本模块只做「当前段 → 每段状态」的推导，
 * 呈现（开发平台 stepper / 需求端旅程映射）都从这份推导出发。
 */

/** 详情 `stages[]` 元素（swagger StageView 投影）：name 稳定键 / label 展示名 / gateActor 有门时的拍板方 / terminal 终段。 */
export type StageView = components["schemas"]["StageView"];

export type StageStatus = "done" | "current" | "future";

export type StageStep = {
  index: number;
  stage: StageView;
  status: StageStatus;
};

export type StageProgress = {
  steps: StageStep[];
  /** 当前段下标；-1 = 详情 stage 未命中序列（键漂移防御，呈现层走兜底文案）。 */
  currentIndex: number;
  current: StageView | null;
  /** 当前段即终段（验收门通过收口）——页面按终态渲染。 */
  terminal: boolean;
};

/**
 * 按详情 `stage`（稳定键）在 `stages[]` 中定位并推导每段状态：
 * 命中前的段 done / 命中段 current / 其后 future。未命中或序列缺失不抛，
 * 返回空 / 全 future 的防御形态，正确性由 REST 重查兜底。
 */
export function deriveStageProgress(
  stages: StageView[] | undefined,
  currentStage: string | undefined,
): StageProgress {
  const list = stages ?? [];
  const currentIndex =
    currentStage === undefined ? -1 : list.findIndex((s) => s.name === currentStage);
  const current = currentIndex >= 0 ? list[currentIndex] ?? null : null;
  return {
    steps: list.map((stage, index) => ({
      index,
      stage,
      status:
        currentIndex < 0
          ? "future"
          : index < currentIndex
            ? "done"
            : index === currentIndex
              ? "current"
              : "future",
    })),
    currentIndex,
    current,
    terminal: current?.terminal === true,
  };
}
