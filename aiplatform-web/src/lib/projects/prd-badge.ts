/**
 * PRD 版本徽标（#346，spec #320 ⑥ 需求分析页粘性 header；用户故事 13；
 * #462 去确认门改造，ADR-0039）：徽标三态推导的单一来源——无 PRD 不出徽标／
 * 草稿（已产出、尚无构建基准记行）／vN 已记行（最新构建基准快照——版本号＝
 * 构建基准序：Demo 类 run 起跑自动记行）。版本号只挂记行版本（草稿没有版本号
 * ——原型「PRD v1.2 (草稿待确认)」的 v1.2 是虚构演示数据，不照造）。
 */
export type PrdBadge = { kind: "draft" } | { kind: "confirmed"; versionNo: number };

export function prdBadgeOf(input: {
  /** PRD 产出在否（prdProducedAt 落定 / 文档加载在场）。 */
  hasPrd: boolean;
  /** 最新构建基准版本号（null/undefined = 尚无记行）。 */
  latestVersionNo?: number | null;
}): PrdBadge | null {
  if (!input.hasPrd) return null;
  if (input.latestVersionNo == null) return { kind: "draft" };
  return { kind: "confirmed", versionNo: input.latestVersionNo };
}

/** 徽标话术（草稿/已记行两形）。 */
export function prdBadgeText(badge: PrdBadge): string {
  if (badge.kind === "draft") return "PRD 草稿";
  return `PRD 构建基准 v${badge.versionNo}`;
}
