import type { components } from "@/lib/api/schema";

/**
 * 项目列表（issue #20）：四态过滤 + 摘要归一。注意同名不同义——
 * **query 参数 `status`** 是过滤视图（1=进行中 active / 2=待处理 pending / 3=已归档
 * archived），**响应字段 `status`** 是项目派生三态（1=开发中 2=已交付 3=已归档）；
 * 前者只出现在 FILTER_STATUS 这一处，枚举 code 不散进消费逻辑（spec 0002 §6）。
 */

/** swagger ProjectResponse 原始形状（字段全可缺）。 */
export type ProjectResponse = components["schemas"]["ProjectResponse"];

/** 列表消费口径的摘要（缺省字段防御归一）。 */
export type ProjectSummary = {
  id: string;
  name: string;
  /** 当前段稳定键。 */
  stage: string;
  stageLabel: string;
  statusName?: string;
  stageTaskCount?: number;
  archived: boolean;
  createdAt?: string;
};

export function normalizeProjectSummary(raw: ProjectResponse): ProjectSummary {
  return {
    id: raw.id ?? "",
    name: raw.name ?? "",
    stage: raw.stage ?? "",
    stageLabel: raw.stageLabel ?? "",
    statusName: raw.statusName,
    stageTaskCount: raw.stageTaskCount,
    archived: raw.archived === true,
    createdAt: raw.createdAt,
  };
}

/** Segmented 四态（呈现序）。 */
export const PROJECT_LIST_FILTERS = [
  { key: "all", label: "全部" },
  { key: "active", label: "进行中" },
  { key: "pending", label: "待处理" },
  { key: "archived", label: "已归档" },
] as const;

export type ProjectListFilterKey = (typeof PROJECT_LIST_FILTERS)[number]["key"];

/** 选中态 → 列表 `status` 参数（Integer code）；「全部」= undefined（缺省 all）。 */
export const FILTER_STATUS: Record<ProjectListFilterKey, number | undefined> = {
  all: undefined,
  active: 1,
  pending: 2,
  archived: 3,
};

/**
 * 「全部」视图本地过滤已归档项——不信后端 all 的归档语义（防御性，spec 0002
 * §3）；其余视图由服务端按 `status` 过滤，此处原样透传。
 */
export function visibleProjects<T extends { archived?: boolean }>(
  items: T[],
  filter: ProjectListFilterKey,
): T[] {
  return filter === "all" ? items.filter((p) => p.archived !== true) : items;
}

/**
 * 「最近的项目」（spec 0002 §3.1 落地页）：createdAt 倒序取前 limit 条，
 * 缺 createdAt 沉底不抛（不改写原数组）。
 */
export function recentProjects<T extends { createdAt?: string }>(
  items: T[],
  limit = 4,
): T[] {
  return [...items]
    .sort((a, b) => {
      const ta = a.createdAt ? Date.parse(a.createdAt) : NaN;
      const tb = b.createdAt ? Date.parse(b.createdAt) : NaN;
      if (Number.isNaN(ta)) return 1;
      if (Number.isNaN(tb)) return -1;
      return tb - ta;
    })
    .slice(0, limit);
}
