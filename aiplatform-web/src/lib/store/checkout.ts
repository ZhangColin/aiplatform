import { create } from "zustand";

/**
 * 模拟收银台弹窗开合挂点（#534 计费④）：全局一份（挂 root layout，邻 M01），
 * 三处触发共用——「我的账户」页账户充值（projectId=null＝账户上下文）、项目
 * 账单节项目充值、试用拦截卡「先去充值」。上下文只记 projectId：目标池由
 * 项目账单的 checkoutTarget 推导（只充当前扣减源池——账户源项目引导充我的
 * 账户，非源池选项不呈现）。
 *
 * <p>未登录先行 M01（CONTEXT「模拟收银台」：须先登录）：触发侧持 useMe 判定，
 * 访客先开 M01（checkout-login 来路文案）、登录成功经 resume 回调重开收银台
 * ——resume 语义同签约门「绑定后放行」先例，本 store 不感知登录态。</p>
 *
 * <p>onDone（#544 引导流）：岔路口「继续讨论」的到账续跑——收银台支付成功即
 * 回调（空 source 重判翻转），中途关闭不触发（放弃充值＝卡留岔路口）。</p>
 */
export type CheckoutState = {
  open: boolean;
  /** 充值上下文：null＝我的账户（账户池）；否则项目 id（项目池/按目标推导）。 */
  projectId: string | null;
  /** 到账续跑回调（#544 引导流「到账即翻转」——充值成功即触发，免用户重走选择；
   *  可空：无续跑语义的来路〔我的账户页/项目账单节/拦截卡〕不传）。 */
  onDone: (() => void) | null;
  openCheckout: (projectId?: string | null, onDone?: () => void) => void;
  closeCheckout: () => void;
};

export const useCheckoutStore = create<CheckoutState>((set) => ({
  open: false,
  projectId: null,
  onDone: null,
  openCheckout: (projectId = null, onDone) => set({ open: true, projectId, onDone: onDone ?? null }),
  closeCheckout: () => set({ open: false, onDone: null }),
}));
