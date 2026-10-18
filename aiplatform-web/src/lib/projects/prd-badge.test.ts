import { describe, expect, it } from "vitest";

import { prdBadgeOf, prdBadgeText } from "@/lib/projects/prd-badge";

/**
 * PRD 版本徽标推导（#346，spec #320 ⑥ 需求分析页；用户故事 13：「草稿」或
 * 整数版本号 vN，分清活稿与已确认版本）：输入＝PRD 在否＋已确认版本列表
 * （#330）＋范围级新草稿停驻（#334 draftChangeSummary）——徽标四态：
 * 无 PRD 不出徽标／草稿待确认（从未确认）／vN 已确认／新草稿待确认（基于 vN）。
 */
describe("prdBadgeOf（版本徽标四态）", () => {
  it("无 PRD：不出徽标", () => {
    expect(prdBadgeOf({ hasPrd: false, latestVersionNo: 3, draftPending: false })).toBeNull();
  });

  it("PRD 在场但从未确认：草稿待确认", () => {
    expect(prdBadgeOf({ hasPrd: true, latestVersionNo: null, draftPending: false })).toEqual({
      kind: "draft",
    });
  });

  it("已确认且无停驻新草稿：vN 已确认（取最新版本号）", () => {
    expect(prdBadgeOf({ hasPrd: true, latestVersionNo: 2, draftPending: false })).toEqual({
      kind: "confirmed",
      versionNo: 2,
    });
  });

  it("已确认但有范围级新草稿停驻（#334 改需求回路）：新草稿待确认、基于 vN", () => {
    expect(prdBadgeOf({ hasPrd: true, latestVersionNo: 2, draftPending: true })).toEqual({
      kind: "redraft",
      versionNo: 2,
    });
  });

  it("停驻草稿但从未确认过（边界：确认失败残留）：按草稿待确认", () => {
    expect(prdBadgeOf({ hasPrd: true, latestVersionNo: null, draftPending: true })).toEqual({
      kind: "draft",
    });
  });
});

describe("prdBadgeText（徽标话术）", () => {
  it("草稿待确认（原型「草稿待确认」形，无版本号可挂）", () => {
    expect(prdBadgeText({ kind: "draft" })).toBe("PRD 草稿待确认");
  });

  it("vN 已确认", () => {
    expect(prdBadgeText({ kind: "confirmed", versionNo: 3 })).toBe("PRD v3 已确认");
  });

  it("新草稿待确认（基于 vN）", () => {
    expect(prdBadgeText({ kind: "redraft", versionNo: 3 })).toBe("PRD 新草稿待确认 · 当前 v3");
  });
});
