import type { components } from "@/lib/api/schema";
import { toAmount } from "@/lib/orders/price";

/** swagger ContractResponse 原始形状（字段全可缺）。 */
export type ContractResponse = components["schemas"]["ContractResponse"];

/** ContractStatus code（#336 六态，交接包原样）。 */
export const CONTRACT_STATUS = {
  preparing: 1,
  awaitingSignature: 2,
  signing: 3,
  signed: 4,
  failedOrExpired: 5,
  withdrawnOrVoided: 6,
} as const;

/** 终态清单（签署完成/签署失败或过期/已撤回或作废）——终态后停轮询。 */
export const CONTRACT_TERMINAL_CODES: readonly number[] = [
  CONTRACT_STATUS.signed,
  CONTRACT_STATUS.failedOrExpired,
  CONTRACT_STATUS.withdrawnOrVoided,
];

/**
 * 消费口径的合同详情（缺省字段防御归一）：预览字段集（双方主体/项目范围/
 * 付款计划——交付清单/验收方式/工期口径随范围快照冻结面、费用随冻结金额）
 * ＋六态＋双方签署位＋「需更新」派生标志（改价/改范围后待后台重录重新冻结）。
 */
export type ContractDetail = {
  id: string;
  contractNo?: string;
  orderId?: string;
  /** ContractStatus code：1=准备中 2=待核对与签署 3=签署进行中 4=签署完成 5=签署失败或过期 6=已撤回或作废。 */
  status?: number;
  statusName?: string;
  /** 合同版本（草稿同号重录 +1；重建从 1 起）。 */
  version?: number;
  /** 「需更新」派生标志：范围或报价变动后待后台重新冻结。 */
  needsUpdate: boolean;
  partyA?: string;
  partyB?: string;
  projectScope?: string;
  paymentPlan?: string;
  /** 范围快照·交付清单（冻结副本）。 */
  scopeDeliverables?: string;
  /** 范围快照·验收方式（冻结副本）。 */
  scopeAcceptance?: string;
  /** 范围快照·工期口径（冻结副本）。 */
  scopeTimeline?: string;
  /** 冻结金额（分；Long 线上实为字符串，容错折数）。 */
  amount?: number;
  currency?: string;
  prdVersionNo?: number;
  demoVersionNo?: number;
  partyASigned: boolean;
  partyBSigned: boolean;
  createdAt?: string;
};

/** 信封解包后的合同详情 → 消费口径（缺省字段防御归一）。 */
export function normalizeContract(raw: ContractResponse): ContractDetail {
  return {
    id: raw.id ?? "",
    contractNo: raw.contractNo,
    orderId: raw.orderId,
    status: raw.status ?? undefined,
    statusName: raw.statusName,
    version: raw.version ?? undefined,
    needsUpdate: raw.needsUpdate ?? false,
    partyA: raw.partyA,
    partyB: raw.partyB,
    projectScope: raw.projectScope,
    paymentPlan: raw.paymentPlan,
    scopeDeliverables: raw.scopeDeliverables,
    scopeAcceptance: raw.scopeAcceptance,
    scopeTimeline: raw.scopeTimeline,
    amount: toAmount(raw.amount),
    currency: raw.currency,
    prdVersionNo: raw.prdVersionNo ?? undefined,
    demoVersionNo: raw.demoVersionNo ?? undefined,
    partyASigned: raw.partyASigned ?? false,
    partyBSigned: raw.partyBSigned ?? false,
    createdAt: raw.createdAt,
  };
}
