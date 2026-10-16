import { create } from "zustand";

/**
 * 保存状态（#343，spec #320 ⑥ 顶栏保存胶囊 / #316 呈现口径）：全局壳顶栏
 * 胶囊真实三态的唯一状态源——「保存中 / 已保存 / 保存失败，请重试」。
 *
 * <p>接线挂点：访客会话保存事实（#326 访客会话域）落地前无人写「保存中/
 * 失败」转移，胶囊停在缺省「已保存」（登录会话下平台侧即时落库，原型静态
 * 形即此态）；#326 在保存动作处调 {@link SaveStateState.markSaving}/
 * {@link SaveStateState.markFailed} 即接真。不在此存「何时保存了什么」——
 * 胶囊只读档位，保存事实本体在会话域。</p>
 */
export type SaveState = "saving" | "saved" | "failed";

export type SaveStateState = {
  state: SaveState;
  /** 保存动作开始（访客会话写穿透前）。 */
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
