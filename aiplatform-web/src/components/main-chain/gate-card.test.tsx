import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import type { GateView } from "@/lib/main-chain/project";

import { GateCard } from "./gate-card";

// 门卡（共享层资产，issue #19 / #43）：`gate` 非 null 才渲染；通过 / 驳回走
// useApproveStage/useRejectStage（react-query mutation，故需 QueryClientProvider）。
// 断言只看外部可见行为：null 不渲染、出现即可操作（锁定分支已删，挂卡点就绪
// 才挂——issue #58）、heading/actor 文案口径。
function render(gate: GateView | null, copy?: Parameters<typeof GateCard>[0]["copy"]) {
  return renderToStaticMarkup(
    <QueryClientProvider client={new QueryClient()}>
      <GateCard projectId="p1" stageLabel="测试" gate={gate} copy={copy} />
    </QueryClientProvider>,
  );
}

describe("GateCard", () => {
  it("gate === null 不渲染", () => {
    expect(render(null)).toBe("");
  });

  it("门就绪：开发平台口径标题「决策门」+ 通过 / 驳回", () => {
    const html = render({ actor: "DEV", ready: true });
    expect(html).toContain("决策门");
    expect(html).toContain("通过");
    expect(html).toContain("驳回");
    expect(html).toContain("DEV");
    expect(html).not.toContain("未就绪");
  });

  it("锁定分支已删（#58）：ready false / 缺失均无未就绪态，操作钮可操作", () => {
    for (const gate of [{ actor: "DEV", ready: false }, { actor: "DEV" }] as const) {
      const html = render(gate);
      expect(html).toContain("决策门");
      expect(html).not.toContain("未就绪");
      expect(html).not.toContain("门禁条件未满足");
      // 操作钮可操作 = 无 disabled 属性（类名里的 disabled: 变体不算）
      expect(html).not.toContain('disabled=""');
    }
  });

  it("copy 覆盖 heading / 动作文案并隐藏 actor（需求端口径）", () => {
    const html = render(
      { actor: "DEV", ready: true },
      {
        heading: "系统已可以体验，等你验收",
        showActor: false,
        approveLabel: "验收通过",
        rejectToggleLabel: "驳回反馈",
      },
    );
    expect(html).toContain("系统已可以体验，等你验收");
    expect(html).toContain("验收通过");
    expect(html).toContain("驳回反馈");
    expect(html).not.toContain("决策门");
    expect(html).not.toContain("DEV");
  });
});
