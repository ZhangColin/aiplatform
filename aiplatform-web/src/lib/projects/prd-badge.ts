/**
 * PRD 版本徽标（#346，spec #320 ⑥ 需求分析页粘性 header；用户故事 13）：
 * 徽标四态推导的单一来源——无 PRD 不出徽标／草稿待确认（从未确认）／
 * vN 已确认（已确认版本列表最新版）／新草稿待确认（#334 范围级新草稿停驻，
 * 基于最近已确认 vN）。版本号只挂已确认版本（草稿没有版本号——原型
 * 「PRD v1.2 (草稿待确认)」的 v1.2 是虚构演示数据，不照造）。
 */
export type PrdBadge =
  | { kind: "draft" }
  | { kind: "confirmed"; versionNo: number }
  | { kind: "redraft"; versionNo: number };

export function prdBadgeOf(input: {
  /** PRD 产出在否（prdProducedAt 落定 / 文档加载在场）。 */
  hasPrd: boolean;
  /** 已确认版本列表的最新版本号（null/undefined = 从未确认）。 */
  latestVersionNo?: number | null;
  /** 范围级新草稿停驻待确认（draftChangeSummary 在场，#334 改需求回路）。 */
  draftPending: boolean;
}): PrdBadge | null {
  if (!input.hasPrd) return null;
  if (input.latestVersionNo == null) return { kind: "draft" };
  if (input.draftPending) return { kind: "redraft", versionNo: input.latestVersionNo };
  return { kind: "confirmed", versionNo: input.latestVersionNo };
}

/** 徽标话术（草稿/已确认/新草稿三形）。 */
export function prdBadgeText(badge: PrdBadge): string {
  if (badge.kind === "draft") return "PRD 草稿待确认";
  if (badge.kind === "redraft") return `PRD 新草稿待确认 · 当前 v${badge.versionNo}`;
  return `PRD v${badge.versionNo} 已确认`;
}
