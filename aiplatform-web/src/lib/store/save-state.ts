import { create } from "zustand";

/**
 * 保存状态（#343，spec #320 ⑥ 顶栏保存胶囊 / #316 呈现口径）：全局壳顶栏
 * 胶囊真实三态的唯一状态源——「保存中 / 已保存 / 保存失败，请重试」。
 *
 * <p>接线挂点（B2 已接真）：项目内发消息/作答的持久化动作——use-chat 的两处
 * mutation 在 onMutate 调 {@link SaveStateState.markSaving}、成功 markSaved、
 * 失败 markFailed，顶栏胶囊三态随真实提交走。不在此存「何时保存了什么」——
 * 胶囊只读档位，保存事实本体在服务端会话域。</p>
 */
export type SaveState = "saving" | "saved" | "failed";

export type SaveStateState = {
  state: SaveState;
  /** 保存动作开始（提交持久化动作发出前）。 */
  markSaving: () => void;
  /** 保存落定（含平台侧自动保存确认）。 */
  markSaved: () => void;
  /** 保存失败（胶囊文案自带「请重试」指引，重试动作归会话域重试入口）。 */
  markFailed: () => void;
};

export const useSaveStateStore = create<SaveStateState>((set) => ({
  state: "saved",
  markSaving: () => set({ state: "saving" }),
  markSaved: () => set({ state: "saved" }),
  markFailed: () => set({ state: "failed" }),
}));
