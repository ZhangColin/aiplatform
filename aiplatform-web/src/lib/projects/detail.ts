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

/**
 * 生成轨道片事实（#225 计划区只读透出）：片 = 阶段 0（ord 0）+ 切片计划逐片
 * （1..N），status =「最近一次尝试的结局」（REST 传 Integer code 1..3，此处归一
 * 为字面量联合；未知 code 防御回落 pending——多跑向安全）。无在途派生态：「当前片」
 * 由工作消息的 run-start 切片序号驱动，读模型不猜。
 */
export type GenerationSegmentFact = {
  ord: number;
  description: string;
  status: "pending" | "closed" | "failed";
};

const SEGMENT_STATUSES: Record<number, GenerationSegmentFact["status"]> = {
  1: "pending",
  2: "closed",
  3: "failed",
  // 设计物件状态扩值（#291 定稿）：已定稿在计划区同「已收口」位（推进完成——
  // 定稿标记的呈现归设计稿范式，读模型 finalizedPath 另行透出）
  4: "closed",
};

/** 片清单 → 消费口径（缺行/缺 ord 防御剔除；空 = 无计划）。 */
function normalizeSegments(
  raw: ProjectDetailResponse["segments"],
): GenerationSegmentFact[] | null {
  if (!raw || raw.length === 0) return null;
  return raw
    .filter((seg) => seg.ord != null && !!seg.description)
    .map((seg) => ({
      ord: seg.ord as number,
      description: seg.description as string,
      status: SEGMENT_STATUSES[seg.status ?? 1] ?? "pending",
    }));
}

/**
 * 设计轨道件清单 → 消费口径（#290 计划区对偶透出）：designer 直播卡的计划区与
 * 切片清单同构——复用 {@link GenerationSegmentFact} 形状（title 即计划区行文本，
 * 与 description 同位）；状态码表与片清单同集（待跑/已收口/失败）。
 */
/** 设计物件 → 计划区消费口径（#290；#291 已定稿位＝closed）。定稿稿路径
 * （finalizedPath）不进本形状——定稿标记的呈现归设计稿范式（#293/#294），详情
 * 域经 REST 原样可取。 */
function normalizeDesignItems(
  raw: ProjectDetailResponse["designItems"],
): GenerationSegmentFact[] | null {
  if (!raw || raw.length === 0) return null;
  return raw
    .filter((item) => item.ord != null && !!item.title)
    .map((item) => ({
      ord: item.ord as number,
      description: item.title as string,
      status: SEGMENT_STATUSES[item.status ?? 1] ?? "pending",
    }));
}

/** 消费口径的项目详情（缺省字段防御归一）：壳态只取骨架所需字段，随切片增补。 */
export type ProjectDetail = {
  id: string;
  name: string;
  statusName?: string;
  archived?: boolean;
  createdAt?: string;
  /** 终点类型（#285，Integer code：1=设计 2=系统 3=系统＋设计；缺省 = 旧后端，按系统兜底）。 */
  endpointType?: number;
  /** 终点类型名（后端 *Name 随行；缺省时消费端用 ENDPOINT_OPTIONS 文案兜底）。 */
  endpointTypeName?: string;
  /** 设计范围（系统＋设计的页面锚定；null = 无页面锚定）。 */
  designScope?: { type: number; typeName?: string; pages?: string[] | null } | null;
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
  /** 生成轨道片清单（#225 计划区；null = 无现行计划——锚过期/未落库，不伪造计划）。 */
  segments?: GenerationSegmentFact[] | null;
  /** 设计轨道件清单（#290 designer 直播卡计划区；null = 无现行清单，不伪造计划）。 */
  designItems?: GenerationSegmentFact[] | null;
};

/** 信封解包后的详情 → 消费口径（缺省字段防御归一）。 */
export function normalizeProjectDetail(raw: ProjectDetailResponse): ProjectDetail {
  return {
    id: raw.id ?? "",
    name: raw.name ?? "",
    statusName: raw.statusName,
    archived: raw.archived,
    createdAt: raw.createdAt,
    endpointType: raw.endpointType,
    endpointTypeName: raw.endpointTypeName,
    designScope: normalizeDesignScope(raw.designScope),
    prdProducedAt: raw.prdProducedAt,
    generatedAt: raw.generatedAt,
    generationState: generationStateOf(raw.generationState),
    activeOrder: normalizeActiveOrder(raw.activeOrder),
    latestOrder: normalizeActiveOrder(raw.latestOrder),
    segments: normalizeSegments(raw.segments),
    designItems: normalizeDesignItems(raw.designItems),
  };
}

/** 设计范围（#285）→ 消费口径（无 type 视为无范围）。 */
function normalizeDesignScope(
  raw: ProjectDetailResponse["designScope"],
): ProjectDetail["designScope"] {
  if (!raw || raw.type == null) return null;
  return {
    type: raw.type,
    typeName: raw.typeName ?? undefined,
    pages: raw.pages ?? null,
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
