import type { components } from "@/lib/api/schema";
import { toAmount } from "@/lib/orders/price";

/** swagger PaymentPlanResponse 原始形状（字段全可缺）。 */
export type PaymentPlanResponse = components["schemas"]["PaymentPlanResponse"];

/** swagger PaymentNodeResponse 原始形状。 */
export type PaymentNodeResponse = components["schemas"]["PaymentNodeResponse"];

/** swagger PaymentVoucherResponse 原始形状。 */
export type PaymentVoucherResponse = components["schemas"]["PaymentVoucherResponse"];

/** PaymentNodeStatus code（#337 五态照交接包原样立）。 */
export const PAYMENT_NODE_STATUS = {
  pending: 1,
  processing: 2,
  failedOrClosed: 3,
  awaitingVerification: 4,
  received: 5,
} as const;

/**
 * PaymentChannelType code（#337 渠道接口位最小形态）：v1 仅线下转账走通，
 * 扫码占位（渠道真接后启用）。
 */
export const PAYMENT_CHANNEL = {
  offlineTransfer: 1,
  qrCode: 2,
} as const;

/** 凭证上传前端预检面（省注定 400 的上传；后端为准）：四格式。 */
export const VOUCHER_UPLOAD_ACCEPT = ".pdf,.png,.jpg,.jpeg";

/** 凭证前端预检上限（10MB，对齐后端业务上限）。 */
export const VOUCHER_MAX_BYTES = 10 * 1024 * 1024;

/** 转账凭证（上传仅代表已提交——核验结论在后台留痕行）。 */
export type PaymentVoucher = {
  id: string;
  nodeId?: string;
  fileName?: string;
  contentType?: string;
  byteSize?: number;
  createdAt?: string;
};

/** 付款节点（消费口径）：五态/附言码/开工门/渠道位/还需支付推导面。 */
export type PaymentNode = {
  id: string;
  seq?: number;
  name?: string;
  /** 金额（分；Long 线上实为字符串，容错折数）。 */
  amount?: number;
  /** 占比（整数百分比，展示换算）。 */
  percentage?: number;
  /** PaymentNodeStatus code：1=待支付 2=支付处理中 3=支付失败或关闭 4=线下转账待核验 5=已到账。 */
  status?: number;
  statusName?: string;
  remarkCode?: string;
  kickoffGate?: boolean;
  channelType?: number;
  channelTypeName?: string;
  channelRefNo?: string;
  settledAmount?: number;
  /** 还需支付（分；前台「还需支付金额与对应节点」的推导面）。 */
  remainingAmount?: number;
  receivedAt?: string;
  vouchers: PaymentVoucher[];
};

/** 付款计划（消费口径）：挂订单、随合同冻结同刻创建；活跃＝订单名下最新行。 */
export type PaymentPlan = {
  id: string;
  orderId?: string;
  contractId?: string;
  contractNo?: string;
  totalAmount?: number;
  totalSettled?: number;
  createdAt?: string;
  nodes: PaymentNode[];
};

/** 信封解包后的付款计划 → 消费口径（缺省字段防御归一）。 */
export function normalizePaymentPlan(raw: PaymentPlanResponse): PaymentPlan {
  return {
    id: raw.id ?? "",
    orderId: raw.orderId,
    contractId: raw.contractId,
    contractNo: raw.contractNo,
    totalAmount: toAmount(raw.totalAmount),
    totalSettled: toAmount(raw.totalSettled),
    createdAt: raw.createdAt,
    nodes: (raw.nodes ?? []).map((node) => ({
      id: node.id ?? "",
      seq: node.seq ?? undefined,
      name: node.name,
      amount: toAmount(node.amount),
      percentage: node.percentage ?? undefined,
      status: node.status ?? undefined,
      statusName: node.statusName,
      remarkCode: node.remarkCode,
      kickoffGate: node.kickoffGate ?? false,
      channelType: node.channelType ?? undefined,
      channelTypeName: node.channelTypeName,
      channelRefNo: node.channelRefNo,
      settledAmount: toAmount(node.settledAmount) ?? 0,
      remainingAmount: toAmount(node.remainingAmount) ?? 0,
      receivedAt: node.receivedAt,
      vouchers: (node.vouchers ?? []).map((voucher) => ({
        id: voucher.id ?? "",
        nodeId: voucher.nodeId,
        fileName: voucher.fileName,
        contentType: voucher.contentType,
        byteSize: toAmount(voucher.byteSize),
        createdAt: voucher.createdAt,
      })),
    })),
  };
}

/** 节点是否可上传凭证（待支付/待核验——已到账不收，「处理中/失败」v1 不可达）。 */
export function nodeAcceptsVoucher(node: PaymentNode): boolean {
  return (
    node.status === PAYMENT_NODE_STATUS.pending ||
    node.status === PAYMENT_NODE_STATUS.awaitingVerification
  );
}

/**
 * 付款面板的「还需支付」推导（#337 AC：前台可推导差额与节点）：未到账节点
 * 的 remainingAmount 合计＋对应节点清单（已含核到账但不足的节点——差额即
 * 补齐目标）。全部到账返回 null（面板转「款项已全部到账」）。
 */
export function outstandingPaymentOf(
  plan: PaymentPlan,
): { total: number; nodes: PaymentNode[] } | null {
  const outstanding = plan.nodes.filter(
    (node) => node.status !== PAYMENT_NODE_STATUS.received && (node.remainingAmount ?? 0) > 0,
  );
  if (outstanding.length === 0) {
    return null;
  }
  return {
    total: outstanding.reduce((sum, node) => sum + (node.remainingAmount ?? 0), 0),
    nodes: outstanding,
  };
}

/** 上传前预检（省注定 400 的上传；判定以后端为准）：格式四扩展名＋≤10MB。 */
export function voucherFileAcceptable(file: File): string | null {
  const name = file.name.toLowerCase();
  if (!/\.(pdf|png|jpe?g)$/.test(name)) {
    return "凭证只支持 PDF / PNG / JPG 格式";
  }
  if (file.size > VOUCHER_MAX_BYTES) {
    return "凭证文件不能超过 10MB";
  }
  return null;
}
