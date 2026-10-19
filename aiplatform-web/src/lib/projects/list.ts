import type { components } from "@/lib/api/schema";

import { ORDER_STATUS } from "@/lib/orders/lock";
import { currentStageOf, type DeliveryStageKey } from "@/lib/projects/delivery-stages";

/**
 * 我的项目 P08 读模型：全量拉取后在本地完成生命周期阶段、交易副行、待办、
 * 最近更新排序与归档折叠。阶段门序复用项目空间壳的 currentStageOf，本文件是
 * 列表呈现的唯一推导点；后端两态 status/statusName 不直接作用户面阶段词表。
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
  /** 未终结订单状态（#28：缺省 = 无订单；交易子态与付款门推导输入）。 */
  activeOrderStatus?: number;
  /** 已确认 PRD 版本存在性（P08 生命周期阶段推导输入；缺省 = 旧后端/未确认）。 */
  prdConfirmed?: boolean;
  /** Demo 确认指针是否落位（P08 生命周期阶段推导输入）。 */
  demoConfirmed?: boolean;
  /** 项目级「需用户补充」标志（P08 待用户处理事项优先项）。 */
  needsUserSupplement?: boolean;
  /** 终点类型 code（#299 项目卡弱标识取数面：1=设计 2=系统 3=系统＋设计；缺省 =
   * 旧后端，弱标识不出）。 */
  endpointType?: number;
  /** 终点类型名（后端 *Name 随行——弱标识文字直取）。 */
  endpointTypeName?: string;
};

export function normalizeProjectSummary(raw: ProjectResponse): ProjectSummary {
  return {
    id: raw.id ?? "",
    name: raw.name ?? "",
    archived: raw.archived === true,
    createdAt: raw.createdAt,
    updatedAt: raw.updatedAt,
    activeOrderStatus: raw.activeOrder?.status ?? undefined,
    prdConfirmed: raw.prdConfirmed === true,
    demoConfirmed: raw.demoConfirmed === true,
    needsUserSupplement: raw.needsUserSupplement === true,
    endpointType: raw.endpointType,
    endpointTypeName: raw.endpointTypeName,
  };
}

/** P08 阶段徽标词表：交易子态不再占用主阶段位。 */
const STAGE_LABELS = {
  prd: "需求分析",
  demo: "Demo 体验",
  contract: "签约付款",
  production: "生产承接",
  archived: "已归档",
} as const;

export type ProjectStageKey = DeliveryStageKey | "archived";

export function stageLabel(stage: ProjectStageKey): string {
  return STAGE_LABELS[stage];
}

/** 当前阶段与项目空间壳共用门序；已归档作为独立保留态优先。 */
export function projectStage(
  project: Pick<ProjectSummary, "archived" | "activeOrderStatus" | "prdConfirmed" | "demoConfirmed">,
): ProjectStageKey {
  if (project.archived) return "archived";
  // 订单是已经走到签约/生产的直接事实：存量单缺双确认版本时不得回判前置阶段。
  if (project.activeOrderStatus === ORDER_STATUS.pendingQuote
    || project.activeOrderStatus === ORDER_STATUS.quoted) return "contract";
  if (project.activeOrderStatus === ORDER_STATUS.paid
    || project.activeOrderStatus === ORDER_STATUS.archived) return "production";
  return currentStageOf({
    prdConfirmed: project.prdConfirmed === true,
    demoConfirmed: project.demoConfirmed === true,
    orderPaid: false,
  });
}

/** 交易子态只作「签约付款」阶段副行；其他阶段不占位。 */
export function projectTransactionSubstate(project: ProjectSummary): string | undefined {
  if (projectStage(project) !== "contract") return undefined;
  if (project.activeOrderStatus === ORDER_STATUS.pendingQuote) return "待报价";
  if (project.activeOrderStatus === ORDER_STATUS.quoted) return "待支付";
  return undefined;
}

/** P08「待用户处理事项」：需补充盖帽；等待平台/交付时如实写暂无，不伪造成用户动作。 */
export function projectPendingAction(project: ProjectSummary): string {
  if (project.archived) return "已归档，无待处理事项";
  if (project.needsUserSupplement) return "补充资料或确认事项";
  switch (projectStage(project)) {
    case "prd":
      return "继续梳理需求";
    case "demo":
      return "体验并确认 Demo 版本";
    case "contract":
      if (project.activeOrderStatus == null) return "进入签约付款";
      if (project.activeOrderStatus === ORDER_STATUS.pendingQuote) return "暂无，等待平台报价";
      if (project.activeOrderStatus === ORDER_STATUS.quoted) return "核对范围并完成签约付款";
      return "查看签约付款进展";
    case "production":
      return "暂无，等待交付更新";
    case "archived":
      return "已归档，无待处理事项";
  }
}

/** P08 列表分区：未归档主网格＋已归档折叠组；两组默认按最后更新新到旧。 */
export function projectListSections<
  T extends Pick<ProjectSummary, "archived" | "createdAt" | "updatedAt">,
>(items: T[]): { main: T[]; archivedGroup: T[] } {
  const sorted = recentProjects(items, items.length);
  return {
    main: sorted.filter((project) => !project.archived),
    archivedGroup: sorted.filter((project) => project.archived),
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
