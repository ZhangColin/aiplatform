import { create } from "zustand";

/**
 * M01 登录与项目绑定弹窗触发挂点（#343 留缝，#327/#328 落地弹窗本体）：顶栏
 * 「保存项目」与「进入签约付款」两处触发（#316/#318）共用的开合状态——弹窗
 * 落地后读此 store 挂载（手机号/邮箱验证＋「验证并继续」；取消后继续当前体验）。
 * trigger 记录来路，供弹窗文案区分。
 *
 * <p>resume：被门拦下的动作的续跑回调（#328 签约门「绑定后放行」）——调用方
 * （下单入口）在收到 ORD_017 时携重试回调开门，弹窗验码成功即回调续跑，免用户
 * 二次点击；无续跑语义的来路（「保存项目」、401 出口）不传。</p>
 */
export type M01Trigger = "save-project" | "stage-gate";

export type M01State = {
  open: boolean;
  trigger: M01Trigger | null;
  /** 登录成功后的续跑回调（可空：无续跑语义的来路不传）。 */
  resume: (() => void) | null;
  openM01: (trigger: M01Trigger, resume?: () => void) => void;
  closeM01: () => void;
};

export const useM01Store = create<M01State>((set) => ({
  open: false,
  trigger: null,
  resume: null,
  openM01: (trigger, resume) => set({ open: true, trigger, resume: resume ?? null }),
  closeM01: () => set({ open: false, trigger: null, resume: null }),
}));
