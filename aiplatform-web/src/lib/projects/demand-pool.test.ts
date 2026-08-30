import { describe, expect, it } from "vitest";

import { DEMAND_CONTENT_MAX, sortDemandEntriesNewestFirst, validateDemandContent } from "./demand-pool";

describe("想法池内容校验（issue #20：空内容拦截 / ≤2000）", () => {
  it("空串与纯空白拦截", () => {
    expect(validateDemandContent("")).toContain("不能为空");
    expect(validateDemandContent("   \n\t ")).toContain("不能为空");
  });

  it("超过上限拦截并给出当前字数", () => {
    const error = validateDemandContent("长".repeat(DEMAND_CONTENT_MAX + 1));
    expect(error).toContain(String(DEMAND_CONTENT_MAX));
  });

  it("合法内容（含恰好上限、首尾空白）返回 null，可提交", () => {
    expect(validateDemandContent("给列表加个导出按钮")).toBeNull();
    expect(validateDemandContent("长".repeat(DEMAND_CONTENT_MAX))).toBeNull();
    expect(validateDemandContent("  有首尾空白  ")).toBeNull();
  });
});

describe("想法池条目排序（新→旧，客户端防御）", () => {
  it("按 createdAt 倒序；缺失 createdAt 沉底不抛", () => {
    const sorted = sortDemandEntriesNewestFirst([
      { id: "old", content: "旧", createdAt: "2026-08-01T00:00:00Z" },
      { id: "none", content: "无时间" },
      { id: "new", content: "新", createdAt: "2026-08-22T00:00:00Z" },
    ]);
    expect(sorted.map((e) => e.id)).toEqual(["new", "old", "none"]);
  });

  it("不改变入参数组（纯函数）", () => {
    const input = [
      { id: "a", createdAt: "2026-08-01T00:00:00Z" },
      { id: "b", createdAt: "2026-08-02T00:00:00Z" },
    ];
    sortDemandEntriesNewestFirst(input);
    expect(input.map((e) => e.id)).toEqual(["a", "b"]);
  });
});
