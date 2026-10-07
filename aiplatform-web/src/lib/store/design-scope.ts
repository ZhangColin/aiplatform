import { create } from "zustand";

/**
 * 设计改稿作用域 store（#294 点哪改哪，SSE 相关之外的一次性 UI 状态——Zustand
 * 状态三分法，对偶圈注 store 先例）：画布点选稿卡 → 写入「发送前」的作用域
 * （后续发言直达该件设计会话改稿，POST /messages 携 designItem），发送框上方
 * chip 呈现（可取消）。作用域＝设计物（改稿对该件出新一代——stitch 语义，非
 * 具体稿卡）；发散度三档（微调/探索/大胆）与作用域同场呈现、随作用域发言同句
 * 发出（divergence 字段——经对话或画布 chip 调、同一语义通道）。
 *
 * <p>发送<b>不</b>清作用域（stitch 挑选语义：选中即工作集，连续改稿零重复点选）；
 * 点选他件即换目标、X 即退出作用域回常规三分类。回访/刷新不保留（会话内态）。</p>
 */

/** 发散度三档（后端 DesignDivergence 的前端镜像；api 值＝枚举名）。 */
export type DesignDivergence = "refine" | "explore" | "reimagine";

/** 档位呈现正本（label/说明与后端枚举 label 同源语义）。 */
export const DIVERGENCE_LEVELS: {
  value: DesignDivergence;
  api: string;
  label: string;
}[] = [
  { value: "refine", api: "REFINE", label: "微调" },
  { value: "explore", api: "EXPLORE", label: "探索" },
  { value: "reimagine", api: "REIMAGINE", label: "大胆" },
];

/** 作用域事实：目标设计物（ord＝REST 路由键；itemTitle＝chip 呈现）。 */
export type DesignScope = {
  ord: number;
  itemTitle: string;
};

export type DesignScopeState = {
  scopes: Record<string, DesignScope | undefined>;
  divergences: Record<string, DesignDivergence>;
  /** 画布点选稿卡 → 选中该件为改稿作用域（点他件即换目标）。 */
  pick: (projectId: string, scope: DesignScope) => void;
  /** 退出作用域（chip 的 X）——回常规三分类。 */
  clear: (projectId: string) => void;
  /** 调发散度档位（chip 三档）。 */
  setDivergence: (projectId: string, level: DesignDivergence) => void;
};

export const useDesignScopeStore = create<DesignScopeState>((set) => ({
  scopes: {},
  divergences: {},

  pick: (projectId, scope) =>
    set((state) => ({ scopes: { ...state.scopes, [projectId]: scope } })),

  clear: (projectId) =>
    set((state) => ({ scopes: { ...state.scopes, [projectId]: undefined } })),

  setDivergence: (projectId, level) =>
    set((state) => ({ divergences: { ...state.divergences, [projectId]: level } })),
}));
