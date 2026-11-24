/**
 * PRD 徽标（#346，spec #320 ⑥ 需求分析页粘性 header；#630 旧 PRD 版本面退场后
 * 收为单态）：徽标推导的单一来源——无 PRD 不出徽标／已产出「PRD 草稿」。旧「构建
 * 基准 vN」版本徽标随旧 PRD 版本表与端点一并退场（版本面只剩用户面 git 单轴）。
 */
export type PrdBadge = { kind: "draft" };

export function prdBadgeOf(input: { hasPrd: boolean }): PrdBadge | null {
  return input.hasPrd ? { kind: "draft" } : null;
}

/** 徽标话术（#630 单态：只有草稿）。 */
export function prdBadgeText(): string {
  return "PRD 草稿";
}
