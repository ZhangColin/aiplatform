import { describe, expect, it } from "vitest";

import {
  TOKEN_USAGE_ROWS,
  costEntries,
  formatCost,
  formatTokenCount,
  iterationLabel,
  iterationRemainder,
  roleUsageLabel,
  sortedIterations,
  tokenCount,
  usageTotalTokens,
} from "./usage";

const tokens = {
  input: 1_000,
  output: 2_000,
  cacheRead: 3_000,
  cacheWrite: 400,
  reasoning: 50,
};

describe("用量五档（issue #20：input/output/cacheRead/cacheWrite/reasoning）", () => {
  it("五档定义有序且键与 TokenUsage 契约一致", () => {
    expect(TOKEN_USAGE_ROWS.map((r) => r.key)).toEqual([
      "input",
      "output",
      "cacheRead",
      "cacheWrite",
      "reasoning",
    ]);
  });

  it("tokenCount：缺省归 0（防御后端可缺字段）", () => {
    expect(tokenCount(tokens, "reasoning")).toBe(50);
    expect(tokenCount(undefined, "input")).toBe(0);
    expect(tokenCount({ input: 7 }, "output")).toBe(0);
  });

  it("usageTotalTokens：五档求和；undefined = 0", () => {
    expect(usageTotalTokens(tokens)).toBe(6_450);
    expect(usageTotalTokens(undefined)).toBe(0);
  });

  it("formatTokenCount：千分位", () => {
    expect(formatTokenCount(1_234_567)).toBe("1,234,567");
    expect(formatTokenCount(0)).toBe("0");
  });
});

describe("平台成本（issue #24：币种分桶平铺，不相加不折算）", () => {
  it("formatCost：固定 4 位小数（BigDecimal 序列化可能带多位小数）", () => {
    expect(formatCost(0.123)).toBe("0.1230");
    expect(formatCost(4.56)).toBe("4.5600");
    expect(formatCost(0)).toBe("0.0000");
    expect(formatCost(0.123456789)).toBe("0.1235");
    expect(formatCost(1_234.5)).toBe("1,234.5000");
  });

  it("costEntries：按币种码排序；缺省/空对象归空序列", () => {
    expect(costEntries({ CNY: 4.56, USD: 0.123 })).toEqual([
      ["CNY", 4.56],
      ["USD", 0.123],
    ]);
    expect(costEntries({})).toEqual([]);
    expect(costEntries(undefined)).toEqual([]);
  });
});

describe("角色桶展示名（issue #24：roleLabel=null 的用途标记桶映射）", () => {
  it("roleLabel 非空时一律走展示名字段", () => {
    expect(roleUsageLabel("DEV", "开发")).toBe("开发");
    expect(roleUsageLabel("FIX", "修复师")).toBe("修复师");
  });

  it("roleLabel 缺失时按用途标记 code 映射", () => {
    expect(roleUsageLabel("FIX", null)).toBe("期后修复");
    expect(roleUsageLabel("RESUME", undefined)).toBe("恢复执行");
    expect(roleUsageLabel("SOMETHING_ELSE", null)).toBe("—");
    expect(roleUsageLabel(undefined, undefined)).toBe("—");
  });
});

describe("按期聚合（issue #24：seq 升序，null 沉底；期后修复差额机械减法）", () => {
  const it1 = { iterationId: "a", seq: 1, tokens: { input: 100, output: 10 } };
  const it2 = { iterationId: "b", seq: 2, tokens: { input: 200, output: 20 } };
  const itOld = { iterationId: "c", seq: null, tokens: { input: 50 } };

  it("iterationLabel：seq → 第 N 期；null/undefined → 往期", () => {
    expect(iterationLabel(3)).toBe("第 3 期");
    expect(iterationLabel(null)).toBe("往期");
    expect(iterationLabel(undefined)).toBe("往期");
  });

  it("sortedIterations：seq 升序，null 沉底，原数组不被改写", () => {
    const input = [itOld, it2, it1];
    expect(sortedIterations(input)).toEqual([it1, it2, itOld]);
    expect(input.map((i) => i.iterationId)).toEqual(["c", "b", "a"]);
    expect(sortedIterations(undefined)).toEqual([]);
  });

  it("iterationRemainder：total − Σ期桶逐档机械减法（缺档归 0）", () => {
    const total = { input: 400, output: 40, cacheRead: 5 };
    expect(iterationRemainder(total, [it1, it2, itOld])).toEqual({
      input: 50,
      output: 10,
      cacheRead: 5,
      cacheWrite: 0,
      reasoning: 0,
    });
  });

  it("iterationRemainder：期桶合计等于 total 时差额全零（不显差额行）", () => {
    const total = { input: 350, output: 30 };
    const remainder = iterationRemainder(total, [it1, it2, itOld]);
    expect(usageTotalTokens(remainder)).toBe(0);
  });

  it("iterationRemainder：total 缺省按零桶计", () => {
    expect(usageTotalTokens(iterationRemainder(undefined, undefined))).toBe(0);
  });
});
