import type { components } from "@/lib/api/schema";

/** swagger OrderResponse 原始形状（字段全可缺）。 */
export type OrderResponse = components["schemas"]["OrderResponse"];

/** swagger PriceEntryResponse 原始形状。 */
export type PriceEntryResponse = components["schemas"]["PriceEntryResponse"];

/**
 * 消费口径的价目行（改价历史条目：时间 + 金额 + 备注）。后端 Long 全局序列化
 * 为字符串（schema 声明 number、线上实为 string），amount 在归一层容错折数。
 */
export type OrderPriceEntry = {
  id: string;
  amount?: number;
  currency?: string;
  note?: string;
  createdAt?: string;
};

/**
 * 消费口径的订单详情（缺省字段防御归一）：状态面与时间戳组（#28）+ 金额面
 * （#29：总价/币种/后台备注/报价时点/改价历史——待报价态金额缺省、历史为空）
 * + 支付/归档时点（#30：已支付为事务内瞬态，paidAt 与 archivedAt 同拍——
 * 归档终态「完整记录」的一环）。
 */
export type OrderDetail = {
  id: string;
  projectId?: string;
  /** OrderStatus code：1=待报价 2=已报价 3=已支付 4=已归档 5=已取消。 */
  status?: number;
  statusName?: string;
  /** 当前总价（分；待报价缺省）。 */
  amount?: number;
  /** 币种（v1 恒 CNY；待报价缺省）。 */
  currency?: string;
  /** 当前后台备注（最新价目行 note；待报价缺省）。 */
  note?: string;
  /** 首次报价时点（改价不刷新；待报价缺省）。 */
  quotedAt?: string;
  /** 改价历史（新 → 旧，后端排序口径；只追加不改写）。 */
  priceEntries: OrderPriceEntry[];
  createdAt?: string;
  cancelledAt?: string | null;
  /** 支付成功时点（未支付缺省；与 archivedAt 同拍）。 */
  paidAt?: string;
  /** 归档时点（未归档缺省）。 */
  archivedAt?: string;
};

/** Long 字段线上实为字符串（Jackson Long→String），容错折数。 */
function toAmount(value: number | string | null | undefined): number | undefined {
  if (value == null) {
    return undefined;
  }
  const parsed = typeof value === "number" ? value : Number(value);
  return Number.isFinite(parsed) ? parsed : undefined;
}

function normalizeEntry(raw: PriceEntryResponse): OrderPriceEntry {
  return {
    id: raw.id ?? "",
    amount: toAmount(raw.amount),
    currency: raw.currency,
    note: raw.note,
    createdAt: raw.createdAt,
  };
}

/** 信封解包后的订单详情 → 消费口径（缺省字段防御归一）。 */
export function normalizeOrder(raw: OrderResponse): OrderDetail {
  return {
    id: raw.id ?? "",
    projectId: raw.projectId,
    status: raw.status ?? undefined,
    statusName: raw.statusName,
    amount: toAmount(raw.amount),
    currency: raw.currency,
    note: raw.note,
    quotedAt: raw.quotedAt,
    priceEntries: (raw.priceEntries ?? []).map(normalizeEntry),
    createdAt: raw.createdAt,
    cancelledAt: raw.cancelledAt ?? null,
    paidAt: raw.paidAt ?? undefined,
    archivedAt: raw.archivedAt ?? undefined,
  };
}
