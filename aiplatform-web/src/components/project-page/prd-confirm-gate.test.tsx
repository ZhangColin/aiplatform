import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import { PrdConfirmGate } from "./prd-confirm-gate";

// 确认门（#330 确认即成版 ＋ #332 生成加门）：PRD 面板主 CTA 契约——mutation 面
// mock 掉（SSR 断言呈现，不跑点击）。
vi.mock("@/hooks/use-confirm-prd", () => ({
  useConfirmPrd: () => ({ isPending: false, mutate: vi.fn() }),
}));

describe("PrdConfirmGate · 确认门呈现（#330/#332）", () => {
  it("PRD 在场即出「确认此版 PRD 并生成 Demo」主 CTA（首个构建 run 的唯一触发；不留门关了没按钮的中间态）", () => {
    const html = renderToStaticMarkup(
      <PrdConfirmGate
        projectId="p1"
        readiness={{ blocking: [], minor: [], dimensions: [] }}
      />,
    );

    expect(html).toContain("确认此版 PRD 并生成 Demo");
    // 旧「生成无门」时代的按钮词已退役
    expect(html).not.toContain("开始做系统");
    expect(html).not.toContain("开始生成");
  });
});
