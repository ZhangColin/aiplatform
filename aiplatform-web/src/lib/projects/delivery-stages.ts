import { ORDER_STATUS } from "@/lib/orders/lock";
import type { ProjectDetail } from "@/lib/projects/detail";

/**
 * 交付生命周期阶段（#343，spec #320 ⑥ 全局壳 / #318 IA）：项目空间四阶段
 * 的词表与可达性判定单一来源——阶段侧栏与全局顶栏五行导航共用（与侧栏重复
 * 亦保留，原型如此设计）。灰置条件＝「未满足条件的步骤要说明原因」（交接包），
 * 门序＝spec #320 依赖骨架：Demo 前置＝PRD 已确认（确认门 #330）、签约付款
 * 前置＝Demo 已确认（Demo 确认门 #333）、生产承接前置＝付款完成（已支付＝
 * 开工条件满足，ADR-0002 重对位）。
 *
 * <p>门事实（{@link StageGateFacts}）是语义正本位；从 REST 事实的投影
 * （{@link stageGateFactsOf}）只接现有事实——PRD 已确认＝已确认版本列表非空
 * （#330 已落地的 GET …/prd/versions）；Demo 确认（#333 Demo 版本化）尚未
 * 落地，诚实映射 false（无确认概念不伪造，落地后在投影处接真）；付款门接
 * 现有订单事实。判定函数不动。阶段页路由约定：需求分析＝项目页本路由，其余
 * 阶段页＝项目路由下路径段（/projects/[id]/demo｜contract｜production——
 * #347/#348/#349 各自落页）。</p>
 */

/** 四阶段词表（序号/名称逐字照原型；segment＝项目空间路径段）。 */
export const DELIVERY_STAGES = [
  { key: "prd", seq: 1, label: "需求分析 (PRD)", segment: null },
  { key: "demo", seq: 2, label: "Demo 体验", segment: "demo" },
  { key: "contract", seq: 3, label: "签约付款", segment: "contract" },
  { key: "production", seq: 4, label: "生产承接", segment: "production" },
] as const;

export type DeliveryStage = (typeof DELIVERY_STAGES)[number];
export type DeliveryStageKey = DeliveryStage["key"];

/**
 * 阶段门事实（推导输入的语义位）：PRD 已确认＝存在已确认 PRD 版本（#330）；
 * Demo 已确认＝存在确认标记的 Demo 版本（#333）；订单已支付＝付款完成
 * （已支付/已归档——已支付即开工条件满足）。
 */
export type StageGateFacts = {
  prdConfirmed: boolean;
  demoConfirmed: boolean;
  orderPaid: boolean;
};

/** 灰置原因（用户语言，点击说明——门与该去哪一步都说清）。 */
const LOCK_REASONS: Partial<Record<DeliveryStageKey, string>> = {
  demo: "PRD 确认后开放：请先在需求分析页确认此版 PRD",
  contract: "Demo 确认后开放：请先在 Demo 体验页确认此版本",
  production: "付款完成后开放：请先完成签约付款",
};

/** 已确认 PRD 版本列表的最小消费形状（versionNo 只用于存在性，不在此呈现）。 */
type PrdVersionPresence = readonly { versionNo?: number }[];

/**
 * REST 事实 → 门事实投影：PRD 已确认＝已确认版本列表非空（#330 接真——空/缺省
 * 即从未确认）；Demo 确认待 #333 Demo 版本化，诚实映射 false；付款门接现有订单
 * 事实——未终结单已支付、或项目/最近单已归档（支付完成归档即终点，生产承接页
 * 仍可回访）。
 */
export function stageGateFactsOf(
  detail: ProjectDetail | undefined | null,
  prdVersions?: PrdVersionPresence | null,
): StageGateFacts {
  const status = detail?.activeOrder?.status ?? detail?.latestOrder?.status;
  return {
    prdConfirmed: (prdVersions?.length ?? 0) > 0,
    demoConfirmed: false,
    orderPaid:
      !!detail?.archived ||
      status === ORDER_STATUS.paid ||
      status === ORDER_STATUS.archived,
  };
}

/** 阶段 → 项目空间路径（需求分析＝项目页本路由；其余＝项目路由下路径段）。 */
export function stageHrefOf(projectId: string, stage: DeliveryStage): string {
  return stage.segment === null
    ? `/projects/${projectId}`
    : `/projects/${projectId}/${stage.segment}`;
}

/** 侧栏/顶栏导航行（可达性判定的消费形状）。 */
export type StageNavItem = {
  key: DeliveryStageKey;
  seq: number;
  label: string;
  href: string;
  /** 完成 ✓（需求分析✓PRD 确认、Demo 体验✓Demo 确认、签约付款✓已支付；生产承接为终阶段无 ✓）。 */
  completed: boolean;
  /** 灰置（不可达）——需求分析是入口永不灰置。 */
  locked: boolean;
  /** 灰置原因（locked 时必有）。 */
  lockReason?: string;
};

/** 四阶段导航行推导：门只挡门后、完成 ✓ 按各自完成事实。 */
export function deliveryStageNav(projectId: string, facts: StageGateFacts): StageNavItem[] {
  const completedOf: Record<DeliveryStageKey, boolean> = {
    prd: facts.prdConfirmed,
    demo: facts.demoConfirmed,
    contract: facts.orderPaid,
    production: false,
  };
  const gateOf: Record<DeliveryStageKey, boolean> = {
    prd: false,
    demo: !facts.prdConfirmed,
    contract: !facts.demoConfirmed,
    production: !facts.orderPaid,
  };
  return DELIVERY_STAGES.map((stage) => {
    const locked = gateOf[stage.key];
    return {
      key: stage.key,
      seq: stage.seq,
      label: stage.label,
      href: stageHrefOf(projectId, stage),
      completed: completedOf[stage.key],
      locked,
      ...(locked ? { lockReason: LOCK_REASONS[stage.key] } : {}),
    };
  });
}

/** 当前阶段（脉冲位）＝首个未完成阶段；全完成落在生产承接（终阶段）。 */
export function currentStageOf(facts: StageGateFacts): DeliveryStageKey {
  if (!facts.prdConfirmed) return "prd";
  if (!facts.demoConfirmed) return "demo";
  if (!facts.orderPaid) return "contract";
  return "production";
}

/**
 * 路由激活阶段（侧栏高亮与顶栏五行导航共用）：路径命中某阶段页即激活
 * （需求分析＝项目页本路由精确匹配，其余＝路径段前缀——阶段页内的子路径
 * 仍属该阶段）；不在项目空间任何阶段页 → null。
 */
export function activeStageOf(projectId: string, pathname: string): DeliveryStageKey | null {
  for (const stage of DELIVERY_STAGES) {
    const href = stageHrefOf(projectId, stage);
    if (stage.segment === null ? pathname === href : pathname.startsWith(`${href}/`)) {
      return stage.key;
    }
  }
  return null;
}
