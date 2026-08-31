import { describe, expect, it } from "vitest";

import { confirmOrderVisible } from "./confirm-order";

// 「确认下单」可见性规则（#26 验收：前端纯逻辑测试）：首次生成完成即常驻、
// 零迭代可点；归档终态不可见；仅无未终结订单时显示（订单事实归交易环①接出）。

describe("confirmOrderVisible · 确认下单可见性（#26 迭代环①）", () => {
  it("未生成（PRD 期 / 生成中）：不可见——按钮随首次生成完成才出现", () => {
    expect(confirmOrderVisible({ generatedAt: null })).toBe(false);
    expect(confirmOrderVisible({})).toBe(false);
  });

  it("首次生成完成：可见（零迭代可点——满意就下单，不被迭代拉长）", () => {
    expect(confirmOrderVisible({ generatedAt: "2026-09-01T00:00:00Z" })).toBe(true);
  });

  it("迭代任意轮后仍常驻：可见性与迭代次数无关", () => {
    expect(confirmOrderVisible({ generatedAt: "2026-08-31T08:00:00Z" })).toBe(true);
  });

  it("已归档（支付完成终态）：不可见——指令区已关闭", () => {
    expect(
      confirmOrderVisible({ generatedAt: "2026-09-01T00:00:00Z", archived: true }),
    ).toBe(false);
  });

  it("存在未终结订单：不可见（订单期间指令区转订单状态视图，交易环①口径）", () => {
    expect(
      confirmOrderVisible({
        generatedAt: "2026-09-01T00:00:00Z",
        activeOrderId: "123",
      }),
    ).toBe(false);
  });

  it("订单事实未接出（交易环①前）：视为无未终结订单——生成完成即可见", () => {
    expect(
      confirmOrderVisible({ generatedAt: "2026-09-01T00:00:00Z", activeOrderId: null }),
    ).toBe(true);
    expect(
      confirmOrderVisible({ generatedAt: "2026-09-01T00:00:00Z", activeOrderId: undefined }),
    ).toBe(true);
  });
});
