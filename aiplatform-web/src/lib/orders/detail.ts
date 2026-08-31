import type { components } from "@/lib/api/schema";

/** swagger OrderResponse 原始形状（字段全可缺）。 */
export type OrderResponse = components["schemas"]["OrderResponse"];

/** 消费口径的订单详情（缺省字段防御归一）：本片只立状态面与时间戳组。 */
export type OrderDetail = {
  id: string;
  projectId?: string;
  /** OrderStatus code：1=待报价 2=已报价 3=已支付 4=已归档 5=已取消。 */
  status?: number;
  statusName?: string;
  createdAt?: string;
  cancelledAt?: string | null;
};

/** 信封解包后的订单详情 → 消费口径（缺省字段防御归一）。 */
export function normalizeOrder(raw: OrderResponse): OrderDetail {
  return {
    id: raw.id ?? "",
    projectId: raw.projectId,
    status: raw.status ?? undefined,
    statusName: raw.statusName,
    createdAt: raw.createdAt,
    cancelledAt: raw.cancelledAt ?? null,
  };
}
