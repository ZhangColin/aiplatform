import { describe, expect, it } from "vitest";

import { prdBadgeOf, prdBadgeText } from "@/lib/projects/prd-badge";

/**
 * PRD 徽标推导（#346，spec #320 ⑥ 需求分析页；#630 旧 PRD 版本面退场后收为
 * 单态）：输入＝PRD 在否——无 PRD 不出徽标／已产出「PRD 草稿」；旧「构建基准
 * vN」版本徽标随旧 PRD 版本表与端点一并退场。
 */
describe("prdBadgeOf（徽标推导）", () => {
  it("无 PRD：不出徽标", () => {
    expect(prdBadgeOf({ hasPrd: false })).toBeNull();
  });

  it("PRD 在场：草稿", () => {
    expect(prdBadgeOf({ hasPrd: true })).toEqual({ kind: "draft" });
  });
});

describe("prdBadgeText（徽标话术）", () => {
  it("草稿", () => {
    expect(prdBadgeText()).toBe("PRD 草稿");
  });
});
