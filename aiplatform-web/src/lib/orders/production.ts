import type { components } from "@/lib/api/schema";

/** swagger DeliveryStatusResponse 原始形状（字段全可缺）。 */
export type DeliveryStatusResponse = components["schemas"]["DeliveryStatusResponse"];

/** DeliveryState code（#338 前台六态——订单＋合同＋付款＋承接四源纯推导）。 */
export const DELIVERY_STATE = {
  /** 待签约：合同未签署完成（无/草稿/签署中/旁落终态待重建）。 */
  awaitingContract: 1,
  /** 待付款：合同已签、开工门节点未全到账。 */
  awaitingPayment: 2,
  /** 款项核验中：有节点处线下转账待核验。 */
  verifyingPayment: 3,
  /** 待安排生产：开工条件满足（已支付＝新语义），承接待排期。 */
  awaitingProduction: 4,
  /** 生产中：承接已推进。 */
  inProduction: 5,
  /** 需用户补充：项目级标志盖帽——处理入口在对话区。 */
  needsUserSupplement: 6,
} as const;

/**
 * 消费口径的承接字段视图（#338 人工措辞面）：可空段如实缺省——呈现层
 * 「仅真实值显示」（预计开始时间空＝待安排，不虚构日期）。
 */
export type ProductionHandoffView = {
  status?: number;
  statusName?: string;
  contact?: string;
  nextStep?: string;
  progressNote?: string;
  estimatedStartAt?: string | null;
};

/**
 * 消费口径的交付状态（缺省字段防御归一）：六态（state null＝待报价，六态
 * 不覆盖——订单面「待确认报价」占位态照旧）＋需用户补充标志（盖帽态与标志
 * 同报，卡片双行呈现）＋承接字段（未建承接缺省）。
 */
export type DeliveryStatus = {
  orderId: string;
  orderStatus?: number;
  orderStatusName?: string;
  /** DeliveryState code：1=待签约 2=待付款 3=款项核验中 4=待安排生产 5=生产中 6=需用户补充；null＝待报价。 */
  state?: number;
  stateName?: string;
  needsUserSupplement: boolean;
  handoff: ProductionHandoffView | null;
};

/** 信封解包后的交付状态 → 消费口径（缺省字段防御归一）。 */
export function normalizeDeliveryStatus(raw: DeliveryStatusResponse): DeliveryStatus {
  return {
    orderId: raw.orderId ?? "",
    orderStatus: raw.orderStatus ?? undefined,
    orderStatusName: raw.orderStatusName,
    state: raw.state ?? undefined,
    stateName: raw.stateName,
    needsUserSupplement: raw.needsUserSupplement ?? false,
    handoff: raw.handoff ?? null,
  };
}
