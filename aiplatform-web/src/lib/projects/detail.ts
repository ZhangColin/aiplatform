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
 * 设计物件 → 消费口径（#290 计划区；#293 画布随读）：状态四值（待跑/已收口/
 * 失败/已定稿——计划区呈现经 {@link planAreaOfDesignItems} 投影：已定稿在同
 * 「已收口」位；定稿标记与 finalizedPath 的消费面是设计稿画布：定稿徽记＋
 * 悬卡删除的不可删判据）。
 */
export type DesignItemFact = {
  ord: number;
  title: string;
  status: "pending" | "closed" | "failed" | "finalized";
  /** 定稿稿路径（工作区锚定形，仅已定稿件携带）。 */
  finalizedPath?: string;
};

const DESIGN_ITEM_STATUSES: Record<number, DesignItemFact["status"]> = {
  1: "pending",
  2: "closed",
  3: "failed",
  4: "finalized",
};

/** 件清单 → 消费口径（缺行/缺 ord 防御剔除；空 = 无现行清单，不伪造计划）。 */
function normalizeDesignItems(
  raw: ProjectDetailResponse["designItems"],
): DesignItemFact[] | null {
  if (!raw || raw.length === 0) return null;
  return raw.flatMap((item) =>
    item.ord != null && !!item.title
      ? [{
          ord: item.ord as number,
          title: item.title as string,
          status: DESIGN_ITEM_STATUSES[item.status ?? 1] ?? "pending",
          finalizedPath: item.finalizedPath || undefined,
        }]
      : [],
  );
}

/**
 * 计划区投影（#290/#291）：件清单 → 计划区行形状（GenerationSegmentFact 同构
 * ——title 即行文本；已定稿投影为「已收口」位：推进完成，定稿呈现归画布）。
 */
export function planAreaOfDesignItems(
  items: DesignItemFact[] | null | undefined,
): GenerationSegmentFact[] | null {
  if (!items || items.length === 0) return null;
  return items.map((item) => ({
    ord: item.ord,
    description: item.title,
    status: item.status === "finalized" ? "closed" : item.status,
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
  /** 设计轨道件清单（#290 计划区；null = 无现行清单，不伪造计划）。计划区消费经
   * planAreaOfDesignItems 投影；#293 画布直取富形状（finalized/finalizedPath）。 */
  designItems?: DesignItemFact[] | null;
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
