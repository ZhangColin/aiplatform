import type { components } from "@/lib/api/schema";

import { ORDER_STATUS } from "@/lib/orders/lock";

/**
 * 项目列表四态视图（issue #21 骨架 + #28 订单态接线）：全量拉取 + 本地分区
 * （v1 不分页，过滤/折叠消化规模）。四态是用户面口径，与后端两态派生状态
 * （status/statusName）分属两层——本文件是四态唯一推导点。
 */

/** swagger ProjectResponse 原始形状（字段全可缺）。 */
export type ProjectResponse = components["schemas"]["ProjectResponse"];

/** 列表消费口径的摘要（缺省字段防御归一）。 */
export type ProjectSummary = {
  id: string;
  name: string;
  archived: boolean;
  createdAt?: string;
  updatedAt?: string;
  /** 未终结订单状态（#28：缺省 = 无订单；待报价/待支付态的推导输入）。 */
  activeOrderStatus?: number;
};

export function normalizeProjectSummary(raw: ProjectResponse): ProjectSummary {
  return {
    id: raw.id ?? "",
    name: raw.name ?? "",
    archived: raw.archived === true,
    createdAt: raw.createdAt,
    updatedAt: raw.updatedAt,
    activeOrderStatus: raw.activeOrder?.status ?? undefined,
  };
}

/** 四态标签（单一事实源；呈现序见 PROJECT_STAGES）。 */
const STAGE_LABELS = {
  in_progress: "进行中",
  awaiting_quote: "待报价",
  awaiting_payment: "待支付",
  archived: "已归档",
} as const satisfies Record<string, string>;

export type ProjectStageKey = keyof typeof STAGE_LABELS;

export function stageLabel(stage: ProjectStageKey): string {
  return STAGE_LABELS[stage];
}

/** 过滤位呈现序（#21）：活跃三态在前、归档殿后。 */
export const PROJECT_STAGES: ReadonlyArray<{ key: ProjectStageKey; label: string }> = (
  ["in_progress", "awaiting_quote", "awaiting_payment", "archived"] as const
).map((key) => ({ key, label: STAGE_LABELS[key] }));

/**
 * 四态推导口径（#28 订单态接线）：已归档优先；未终结订单在 → 待报价（1）/
 * 待支付（2=已报价）；否则进行中。订单状态缺省/未知按进行中兜底（与详情
 * 嵌入的防御归一同口径）。
 */
export function projectStage(project: Pick<ProjectSummary, "archived" | "activeOrderStatus">): ProjectStageKey {
  if (project.archived) return "archived";
  if (project.activeOrderStatus === ORDER_STATUS.pendingQuote) return "awaiting_quote";
  if (project.activeOrderStatus === ORDER_STATUS.quoted) return "awaiting_payment";
  return "in_progress";
}

/** 列表页分区：主网格 = 选中态项目；历史归档折叠分组 = 已归档项目（选中态已
 * 是归档时主网格即全量，分组不重复出）。 */
export function projectListSections<
  T extends Pick<ProjectSummary, "archived" | "activeOrderStatus">,
>(items: T[], stage: ProjectStageKey): { main: T[]; archivedGroup: T[] } {
  return {
    main: items.filter((p) => projectStage(p) === stage),
    archivedGroup:
      stage === "archived" ? [] : items.filter((p) => projectStage(p) === "archived"),
  };
}

/**
 * 「最近的项目」（首页）：更新时间新→旧取前 limit 条（两列全缺沉底不抛；
 * 不改写原数组）。
 */
export function recentProjects<T extends { createdAt?: string; updatedAt?: string }>(
  items: T[],
  limit = 4,
): T[] {
  return [...items]
    .sort((a, b) => {
      const ta = Date.parse(lastTouchedAt(a) ?? "");
      const tb = Date.parse(lastTouchedAt(b) ?? "");
      if (Number.isNaN(ta)) return 1;
      if (Number.isNaN(tb)) return -1;
      return tb - ta;
    })
    .slice(0, limit);
}

/** 项目最近动静时点（ISO）：updatedAt 缺失/畸形以 createdAt 代，两列全缺
 * undefined（排序与首页行文案共用的回退口径，收在此一处）。 */
export function lastTouchedAt(project: {
  createdAt?: string;
  updatedAt?: string;
}): string | undefined {
  for (const iso of [project.updatedAt, project.createdAt]) {
    if (!iso) continue;
    if (!Number.isNaN(Date.parse(iso))) return iso;
  }
  return undefined;
}
