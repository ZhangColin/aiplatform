import { create } from "zustand";

import {
  asDispatchStage,
  nextDispatchBarState,
  type DispatchBarState,
} from "@/lib/chat/dispatch-stage";

/**
 * 派发阶段 store（#50 阶段状态条，SSE 相关 store——桥为唯一事件写入方，
 * ADR 0003 状态三分法）：按项目记意见 / 咨询链的当前阶段（项目内最新
 * dispatch-stage 帧即当前阶段——链跨 run 推进，帧序即阶段序）。写入方两路
 * （同 chat store 先例）：SSE 侧 = 桥（帧）；发送侧 = use-chat（新发言 / 作答
 * 即重置——上一链终态不滞留到下一链开口，分类 / 起跑期间不显示旧状态）。
 *
 * 刷新后由 agent 流通道重放缓冲重建（重放按原序收帧，态幂等收敛）；失败链唯一
 * 的终态帧是 dispatch-failed（#51 派发失败如实告知重提），其余失败无终态帧、
 * 状态条停在事发阶段（error 帧另行呈现，「链路断了」与「不需要改」可区分）。
 */
export type DispatchStageState = {
  stages: Record<string, DispatchBarState>;
  // ---- SSE 侧（bridge 唯一写入方） ----
  /** dispatch-stage 帧到达（stage 名册外忽略——前向兼容不炸）。 */
  noteStage: (projectId: string, stage: unknown, changed: unknown) => void;
  // ---- 发送侧（use-chat） ----
  /** 新发言 / 作答重置（新链开口前清旧终态）。 */
  noteSubmitted: (projectId: string) => void;
};

export const useDispatchStageStore = create<DispatchStageState>((set) => ({
  stages: {},

  noteStage: (projectId, stage, changed) => {
    const resolved = asDispatchStage(stage);
    if (!resolved) return;
    const state = useDispatchStageStore.getState();
    const prev = state.stages[projectId];
    const next = nextDispatchBarState(prev, {
      stage: resolved,
      changed: resolved === "done" ? changed === true : undefined,
    });
    if (next === prev) return;
    set({ stages: { ...state.stages, [projectId]: next } });
  },

  noteSubmitted: (projectId) => {
    const state = useDispatchStageStore.getState();
    if (!(projectId in state.stages)) return;
    const stages = { ...state.stages };
    delete stages[projectId];
    set({ stages });
  },
}));
