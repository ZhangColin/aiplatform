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
 */
export type CheckoutState = {
  open: boolean;
  /** 充值上下文：null＝我的账户（账户池）；否则项目 id（项目池/按目标推导）。 */
  projectId: string | null;
  openCheckout: (projectId?: string | null) => void;
  closeCheckout: () => void;
};

export const useCheckoutStore = create<CheckoutState>((set) => ({
  open: false,
  projectId: null,
  openCheckout: (projectId = null) => set({ open: true, projectId }),
  closeCheckout: () => set({ open: false }),
}));
