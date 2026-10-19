import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import type { ProjectSummary } from "@/lib/projects/list";

import { ProjectCard } from "./project-card";

// ProjectCard 点卡跳转走 useRouter（app router 上下文在 node 环境不存在）
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));

// P08 项目卡：生命周期阶段主徽标＋交易子态副行＋最后更新＋待用户处理事项。
/** 归档 mutation 挂 QueryClient，SSR 断言包一层。 */
function renderCard(overrides: Partial<ProjectSummary> = {}) {
  const project: ProjectSummary = {
    id: "123456",
    name: "官网改版",
    archived: false,
    createdAt: "2026-08-20T00:00:00Z",
    ...overrides,
  };
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return renderToStaticMarkup(
    <QueryClientProvider client={client}>
      <ProjectCard project={project} />
    </QueryClientProvider>,
  );
}

describe("ProjectCard · P08 阶段与字段（#349）", () => {
  it("签约付款：交易子态待支付降为副行，主徽标不再显示交易状态", () => {
    const html = renderCard({ prdConfirmed: true, demoConfirmed: true, activeOrderStatus: 2 });
    expect(html).toContain("签约付款");
    expect(html).toContain("待支付");
    expect(html).not.toContain("进行中");
    expect(html).not.toContain("¥");
  });

  it("需求分析 / Demo 体验 / 生产承接按确认与付款事实呈现", () => {
    expect(renderCard()).toContain("需求分析");
    expect(renderCard({ prdConfirmed: true })).toContain("Demo 体验");
    expect(renderCard({ prdConfirmed: true, demoConfirmed: true, activeOrderStatus: 3 }))
      .toContain("生产承接");
  });

  it("已归档：徽标如实标注、卡灰态（既有口径不动）", () => {
    const html = renderCard({ archived: true });
    expect(html).toContain("已归档");
    expect(html).toContain("bg-secondary");
    expect(html).toContain("opacity-60");
  });

  it("最后更新与待用户处理事项在场；需补充优先于普通待办", () => {
    const html = renderCard({ needsUserSupplement: true });
    expect(html).toContain("最后更新");
    expect(html).toContain("待用户处理事项");
    expect(html).toContain("补充资料或确认事项");
  });
});

describe("ProjectCard · 终点弱标识（#299，ADR-0029）", () => {
  it("设计项目显「设计」文字级小标签（与首页最近项目卡同口径）", () => {
    const html = renderCard({ endpointType: 1, endpointTypeName: "设计" });
    expect(html).toContain("设计");
    expect(html).toContain('data-endpoint-type="1"');
  });

  it("弱标识不加颜色强调（不抢四态徽标焦点——纯 muted 文字）", () => {
    const html = renderCard({ endpointType: 1, endpointTypeName: "设计" });
    expect(html).toContain("text-muted-foreground/80");
    expect(html).not.toContain("bg-primary");
  });

  it("终点缺省（旧后端）：弱标识不出", () => {
    const html = renderCard();
    expect(html).not.toContain("data-endpoint-type");
  });

  it("未知终点码（名缺席）：不猜归属——弱标识不出", () => {
    const html = renderCard({ endpointType: 9 });
    expect(html).not.toContain("data-endpoint-type");
  });
});
