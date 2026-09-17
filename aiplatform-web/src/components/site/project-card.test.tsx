import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import type { ProjectSummary } from "@/lib/projects/list";

import { ProjectCard } from "./project-card";

// ProjectCard 点卡跳转走 useRouter（app router 上下文在 node 环境不存在）
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));

// 项目列表卡（#205 三面渗透）：阶段纯文本升级为四态徽标——待支付最强档、
// 其余三态次级、均不带金额；时间行/归档灰态等既有呈现不劣化。
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

describe("ProjectCard · 阶段徽标（#205 纯文本升级）", () => {
  it("待支付：显眼徽标最强档（primary 实底）、不带金额", () => {
    const html = renderCard({ activeOrderStatus: 2 });
    expect(html).toContain("待支付");
    expect(html).toContain("bg-primary text-primary-foreground");
    expect(html).not.toContain("bg-secondary");
    expect(html).not.toContain("¥"); // 路标层只报状态，金额进项目语境内看
  });

  it("进行中/待报价：次级档徽标（呈现不劣化）", () => {
    const inProgress = renderCard();
    expect(inProgress).toContain("进行中");
    expect(inProgress).toContain("bg-secondary");

    const awaitingQuote = renderCard({ activeOrderStatus: 1 });
    expect(awaitingQuote).toContain("待报价");
    expect(awaitingQuote).toContain("bg-secondary");
  });

  it("已归档：徽标如实标注、卡灰态（既有口径不动）", () => {
    const html = renderCard({ archived: true });
    expect(html).toContain("已归档");
    expect(html).toContain("bg-secondary");
    expect(html).toContain("opacity-60"); // 归档卡灰态不劣化
  });

  it("时间行仍在（创建于 X），徽标升级不挤掉既有信息", () => {
    const html = renderCard();
    expect(html).toContain("创建于");
  });
});
