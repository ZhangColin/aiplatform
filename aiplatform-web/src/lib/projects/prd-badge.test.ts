import { describe, expect, it } from "vitest";

import { prdBadgeOf, prdBadgeText } from "@/lib/projects/prd-badge";

/**
 * PRD 版本徽标推导（#346，spec #320 ⑥ 需求分析页；用户故事 13；#462 去确认门
 * 改造 ADR-0039）：输入＝PRD 在否＋最新构建基准版本号——徽标三态：无 PRD 不出
 * 徽标／草稿（尚无构建基准记行）／vN 已记行（版本号＝构建基准序：Demo 类 run
 * 起跑自动记行，无确认动作）。
 */
describe("prdBadgeOf（版本徽标三态）", () => {
  it("无 PRD：不出徽标", () => {
    expect(prdBadgeOf({ hasPrd: false, latestVersionNo: 3 })).toBeNull();
  });

  it("PRD 在场但尚无记行：草稿", () => {
    expect(prdBadgeOf({ hasPrd: true, latestVersionNo: null })).toEqual({
      kind: "draft",
    });
  });

  it("已记行：vN 构建基准（取最新版本号）", () => {
    expect(prdBadgeOf({ hasPrd: true, latestVersionNo: 2 })).toEqual({
      kind: "confirmed",
      versionNo: 2,
    });
  });
});

describe("prdBadgeText（徽标话术）", () => {
  it("草稿（无版本号可挂）", () => {
    expect(prdBadgeText({ kind: "draft" })).toBe("PRD 草稿");
  });

  it("vN 构建基准", () => {
    expect(prdBadgeText({ kind: "confirmed", versionNo: 3 })).toBe("PRD 构建基准 v3");
  });
});
