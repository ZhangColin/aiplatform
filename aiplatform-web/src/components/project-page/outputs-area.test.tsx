import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import { OutputsArea, useOutputsTabs } from "./outputs-area";
import type { ParadigmCtx } from "./paradigms";

// 成果区（#79 呼出式 tab 簇）：tab 条即标题条——默认挂载「系统」「文档」、
// 激活 = 系统（SystemPanel 空态；系统面板断言归 system-panel.test）、「+
// 新标签页」与收起键就位、直播侧栏跨范式常驻。挂载/关闭/切换的交互契约归
// outputs-area.interaction.test（happy-dom）。数据口 mock 掉。
vi.mock("@/hooks/use-project-files", () => ({
  useProjectFiles: () => ({
    data: [{ path: "AGENTS.md", size: 7 }, { path: "docs/PRD.md", size: 12 }],
    isPending: false,
  }),
}));

vi.mock("@/hooks/use-prd", () => ({
  usePrd: () => ({
    data: { content: "# 需求背景\n宠物医院预约系统。", updatedAt: "2026-08-31T08:00:00Z" },
    isPending: false,
  }),
}));

vi.mock("@/hooks/use-project-preview", () => ({
  useProjectPreview: () => ({ data: undefined, isPending: false, isError: false }),
}));

// 直播侧栏装配哨兵（#23）：成果区主体挂 LiveRail（跨范式常驻）；其内部
// 呈现与生命周期归 live-panel.test（client render 覆盖）
vi.mock("./live-panel", () => ({
  LiveRail: () => <div data-testid="live-rail-stub" />,
}));

const CTX: ParadigmCtx = { projectId: "p1", onGenerated: () => {} };

/** Harness：装配层持 tab 簇状态（useOutputsTabs）喂 OutputsArea。 */
function Area() {
  const tabs = useOutputsTabs();
  return <OutputsArea tabs={tabs} ctx={CTX} onClose={() => {}} />;
}

function renderArea() {
  return renderToStaticMarkup(
    <QueryClientProvider client={new QueryClient()}>
      <Area />
    </QueryClientProvider>,
  );
}

describe("OutputsArea · 呼出式 tab 簇（#79 范式注册表）", () => {
  it("默认挂载「系统」「文档」两 tab，激活 = 系统（居首主舞台）", () => {
    const html = renderArea();

    expect(html).toContain('role="tablist"');
    // 恰一个激活 tab，且激活的是「系统」（属性在先、label 紧随其后）
    expect(html.match(/role="tab" aria-selected="true"/g)).toHaveLength(1);
    const selectedAt = html.indexOf('role="tab" aria-selected="true"');
    expect(html.slice(selectedAt, selectedAt + 800)).toContain("系统");
    expect((html.match(/role="tab"/g) ?? []).length).toBe(2);
    // 系统 tab 激活 → SystemPanel 空态提示（未生成）
    expect(html).toContain("开始做系统后，这里会出现可以操作的你的系统");
  });

  it("「+ 新标签页」与收起键就位（tab 条即标题条）", () => {
    const html = renderArea();

    expect(html).toContain("新标签页");
    expect(html).toContain('aria-label="收起成果区"');
  });

  it("直播侧栏挂进成果区主体（跨范式常驻，#23）", () => {
    const html = renderArea();

    expect(html).toContain('data-testid="live-rail-stub"');
  });
});
