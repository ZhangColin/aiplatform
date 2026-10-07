import { create } from "zustand";

import { ENTRY_MODE, type EntryMode } from "@/lib/modes";

/**
 * 入口两档分流态（#299，ADR-0029，SSE 无关的一次性 UI 状态——Zustand 状态
 * 三分法，对偶圈注/改稿作用域 store 先例）：首页 hero 切换件、发送框类型下拉、
 * 侧栏「能做这些」三处同源——侧栏点「做设计」＝去首页并预设设计态。
 *
 * <p>缺省做系统（主链路不动摇一等断言：不选即系统主线、「生成无门」守恒）；
 * 会话内保持、不持久化（回访从默认主线起——分流是新建时的一次语义，项目内
 * 终点变更唯一位＝设置 tab）。</p>
 */
export type EntryModeState = {
  mode: EntryMode;
  /** 就地切换（不跳页不弹层；同一 store 写面，三处呈现自然同步）。 */
  setMode: (mode: EntryMode) => void;
};

export const useEntryModeStore = create<EntryModeState>((set) => ({
  mode: ENTRY_MODE.system,
  setMode: (mode) => set({ mode }),
}));
