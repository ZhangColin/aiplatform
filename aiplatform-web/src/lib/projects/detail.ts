import type { components } from "@/lib/api/schema";

import type { ActiveOrderFact } from "@/lib/orders/lock";

/** 详情响应（swagger ProjectDetailResponse 原始形状，字段全可缺）。 */
export type ProjectDetailResponse = components["schemas"]["ProjectDetailResponse"];

/** 消费口径的项目详情（缺省字段防御归一）：壳态只取骨架所需字段，随切片增补。 */
export type ProjectDetail = {
  id: string;
  name: string;
  statusName?: string;
  archived?: boolean;
  createdAt?: string;
  /** PRD 产出时点（成果区长出判据；缺省 = 闲聊期，指令区占满全宽）。 */
  prdProducedAt?: string | null;
  /** 首次生成时点（run 成功收口单向置位；缺省 = 未生成过——「开始做系统」可发起）。 */
  generatedAt?: string | null;
  /** 未终结订单事实（#28：订单存在即冻结迭代——锁定式矩阵与「确认下单」可见性的输入）。 */
  activeOrder?: ActiveOrderFact | null;
};

/** 信封解包后的详情 → 消费口径（缺省字段防御归一）。 */
export function normalizeProjectDetail(raw: ProjectDetailResponse): ProjectDetail {
  return {
    id: raw.id ?? "",
    name: raw.name ?? "",
    statusName: raw.statusName,
    archived: raw.archived,
    createdAt: raw.createdAt,
    prdProducedAt: raw.prdProducedAt,
    generatedAt: raw.generatedAt,
    activeOrder: normalizeActiveOrder(raw.activeOrder),
  };
}

/** 嵌入的未终结订单摘要 → 消费口径（无 id 视为无订单——矩阵按进行中兜底）。 */
function normalizeActiveOrder(
  raw: ProjectDetailResponse["activeOrder"],
): ActiveOrderFact | null {
  if (!raw || raw.id == null) return null;
  return {
    id: raw.id,
    status: raw.status ?? undefined,
    statusName: raw.statusName ?? undefined,
  };
}
