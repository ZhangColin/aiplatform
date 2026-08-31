import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import { OutputsArea } from "./outputs-area";

// 成果区（#20 长出 / #22 系统模式长出）：文件 / 系统 / 项目三模式——文件模式 =
// PRD 文件呈现（可挂「开始做系统」操作条），系统模式 = SystemPanel（断言归
// system-panel.test）。PRD 读口 mock 掉，正文断言归 prd-panel.test。
vi.mock("@/hooks/use-prd", () => ({
  usePrd: () => ({
    data: { content: "# 需求背景\n宠物医院预约系统。", updatedAt: "2026-08-31T08:00:00Z" },
    isPending: false,
  }),
}));

vi.mock("@/hooks/use-project-preview", () => ({
  useProjectPreview: () => ({ data: undefined, isPending: false, isError: false }),
}));

// 直播侧栏装配哨兵（#23）：OutputsArea 布局挂 LiveRail（跨模式常驻）；其内部
// 呈现与生命周期归 live-panel.test（client render 覆盖）
vi.mock("./live-panel", () => ({
  LiveRail: () => <div data-testid="live-rail-stub" />,
}));

function renderArea(tab = "files") {
  return renderToStaticMarkup(
    <QueryClientProvider client={new QueryClient()}>
      <OutputsArea projectId="p1" tab={tab} onTabChange={() => {}} onGenerated={() => {}} />
    </QueryClientProvider>,
  );
}

describe("OutputsArea · 成果区三模式（#20 文件模式 / #22 系统模式）", () => {
  it("三模式页签就位：文件 / 系统 / 项目", () => {
    const html = renderArea();

    expect(html).toContain('data-slot="tabs-trigger"');
    expect(html).toContain("文件");
    expect(html).toContain("系统");
    expect(html).toContain("项目");
  });

  it("文件模式为默认：PRD 正文直出（docs/PRD.md 呈现）", () => {
    const html = renderArea();

    expect(html).toContain("docs/PRD.md");
    expect(html).toContain("宠物医院预约系统。");
  });

  it("系统模式（tab 受控）：空白浏览器窗 + 未生成提示（无进度剧场）", () => {
    const html = renderArea("system");

    // 浏览器窗栏（占位地址）与一句提示；iframe 未挂（未生成）
    expect(html).toContain("你的系统");
    expect(html).toContain("开始做系统后，这里会出现可以操作的你的系统");
    expect(html).not.toContain("<iframe");
  });

  it("直播侧栏挂进成果区布局（跨模式常驻，#23）", () => {
    const html = renderArea();

    expect(html).toContain('data-testid="live-rail-stub"');
  });
});
