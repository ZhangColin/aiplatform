import type { components } from "@/lib/api/schema";

import type { ActiveOrderFact } from "@/lib/orders/lock";

/** 详情响应（swagger ProjectDetailResponse 原始形状，字段全可缺）。 */
export type ProjectDetailResponse = components["schemas"]["ProjectDetailResponse"];

/**
 * 生成态四态投影（#222，ADR-0020）：REST 详情派生态（轨道表＋generated_at＋在途
 * 标记）——与 SSE 会话态无关，刷新/回访后档位仍正确；前端档位与「继续生成」出口
 * 的推导输入（lib/preview/state 档位投影）。REST 传 Integer code（1..4），此处
 * 归一为字面量联合（未知 code 缺省 undefined——防御旧后端）。
 */
export type GenerationState = "never" | "generating" | "interrupted" | "generated";

const GENERATION_STATES: Record<number, GenerationState> = {
  1: "never",
  2: "generating",
  3: "interrupted",
  4: "generated",
};

/** code → 四态（未知/缺省 → undefined，消费侧按「从未生成」之外的档位自行兜底）。 */
export function generationStateOf(raw: number | undefined | null): GenerationState | undefined {
  return raw == null ? undefined : GENERATION_STATES[raw];
}

/** 消费口径的项目详情（缺省字段防御归一）：壳态只取骨架所需字段，随切片增补。 */
export type ProjectDetail = {
  id: string;
  name: string;
  statusName?: string;
  archived?: boolean;
  createdAt?: string;
  /** PRD 产出时点（成果区长出判据；缺省 = 闲聊期，对话区占满全宽）。 */
  prdProducedAt?: string | null;
  /** 首次生成时点（run 成功收口单向置位；缺省 = 未生成过——生成自动发起或失败重发）。 */
  generatedAt?: string | null;
  /** 生成态四态投影（#222；缺省 = 后端未透出，按会话态兜底）。 */
  generationState?: GenerationState;
  /** 未终结订单事实（#28：订单存在即冻结迭代——锁定式矩阵与「确认下单」可见性的输入）。 */
  activeOrder?: ActiveOrderFact | null;
  /** 最近一张订单事实（#30：归档终态订单卡挂它出完整记录；从未下单 = null）。 */
  latestOrder?: ActiveOrderFact | null;
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
    generationState: generationStateOf(raw.generationState),
    activeOrder: normalizeActiveOrder(raw.activeOrder),
    latestOrder: normalizeActiveOrder(raw.latestOrder),
  };
}

/** 嵌入的订单摘要（activeOrder/latestOrder 同构）→ 消费口径（无 id 视为无单）。 */
function normalizeActiveOrder(
  raw: ProjectDetailResponse["activeOrder"] | ProjectDetailResponse["latestOrder"],
): ActiveOrderFact | null {
  if (!raw || raw.id == null) return null;
  return {
    id: raw.id,
    status: raw.status ?? undefined,
    statusName: raw.statusName ?? undefined,
  };
}
