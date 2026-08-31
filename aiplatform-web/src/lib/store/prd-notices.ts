import { create } from "zustand";

/**
 * PRD 更新提示 store（#20 修订回路，SSE 相关 store——桥为唯一事件写入方，
 * ADR 0003 状态三分法）：document-updated(PRD) 到达时按「是否已见过该项目
 * 产出」分岔——首次产出只登记 seen（成果区长出本身即信号，不打扰）；此后
 * 每次写入置 pending（指令区「需求文档有更新 · 去看看」胶囊与 PRD 卡
 * 「已更新」标记的显隐源）。「去看看」即认领（acknowledge 清 pending）。
 *
 * <p>「这次写入是不是修订」是 REST 重查拿不到的语义（prd_produced_at 每次
 * 写出都刷新，首产/修订不可分）——这正是 ADR 0003 的载荷展示例外形态。
 * 内存态：刷新即逝为 v1 取舍（通知通道无 replay，不复活；对话史同口径）。</p>
 */
export type PrdNoticesState = {
  /** 已见过 PRD 产出的项目（首次写入或挂载兜底登记）。 */
  seen: Record<string, true>;
  /** 有未认领修订的项目 → 修订到达时点（ms）。 */
  pending: Record<string, number>;
  // ---- SSE 侧（bridge 唯一写入方） ----
  /** document-updated(PRD) 到达：seen 则置 pending（修订），未见则登记 seen（首次产出）。 */
  notePrdWritten: (projectId: string, at?: number) => void;
  // ---- 消费侧 ----
  /** 成果区挂出 PRD 的兜底登记（页面加载已产出项目——后续修订才该出胶囊）。 */
  markSeen: (projectId: string) => void;
  /** 「去看看」认领：清 pending（seen 保留——后续修订仍要出胶囊）。 */
  acknowledge: (projectId: string) => void;
};

export const usePrdNoticesStore = create<PrdNoticesState>((set) => ({
  seen: {},
  pending: {},

  notePrdWritten: (projectId, at = Date.now()) =>
    set((state) =>
      state.seen[projectId]
        ? { pending: { ...state.pending, [projectId]: at } }
        : { seen: { ...state.seen, [projectId]: true } },
    ),

  markSeen: (projectId) =>
    set((state) =>
      state.seen[projectId] ? state : { seen: { ...state.seen, [projectId]: true } },
    ),

  acknowledge: (projectId) =>
    set((state) => {
      if (state.pending[projectId] === undefined) return state;
      const pending = { ...state.pending };
      delete pending[projectId];
      return { pending };
    }),
}));
