import { create } from "zustand";

/**
 * M01 登录与项目绑定弹窗触发挂点（#343 留缝，弹窗本体归 #327/#328）：顶栏
 * 「保存项目」与「进入签约付款」两处触发（#316/#318）共用的开合状态——
 * 弹窗落地后读此 store 挂载（手机号/邮箱验证＋「验证并继续」；取消后继续
 * 当前体验）。trigger 记录来路，供弹窗文案/埋点区分。
 */
export type M01Trigger = "save-project" | "stage-gate";

export type M01State = {
  open: boolean;
  trigger: M01Trigger | null;
  openM01: (trigger: M01Trigger) => void;
  closeM01: () => void;
};

export const useM01Store = create<M01State>((set) => ({
  open: false,
  trigger: null,
  openM01: (trigger) => set({ open: true, trigger }),
  closeM01: () => set({ open: false, trigger: null }),
}));
