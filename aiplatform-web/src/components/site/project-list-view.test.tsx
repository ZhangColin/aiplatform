import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import type { ProjectSummary } from "@/lib/projects/list";

import { ArchivedGroup } from "./project-list-view";

// ProjectCard 点卡跳转走 useRouter（app router 上下文在 node 环境不存在）
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));

/** ProjectCard 挂归档 mutation，SSR 断言需包一层 QueryClient。 */
function renderGroup(projects: ProjectSummary[], defaultOpen = false) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return renderToStaticMarkup(
    <QueryClientProvider client={client}>
      <ArchivedGroup projects={projects} defaultOpen={defaultOpen} />
    </QueryClientProvider>,
  );
}

const archived: ProjectSummary[] = [
  { id: "1", name: "已交付官网", archived: true, createdAt: "2026-08-01T00:00:00Z" },
  { id: "2", name: "已交付商城", archived: true, createdAt: "2026-08-02T00:00:00Z" },
];

describe("ArchivedGroup（历史归档默认折叠分组，issue #21）", () => {
  it("默认折叠：头部计数「历史归档 (N)」在、归档卡不渲染", () => {
    const html = renderGroup(archived);
    expect(html).toContain("历史归档 (2)");
    expect(html).not.toContain("已交付官网");
  });

  it("展开（defaultOpen）：归档卡摊出", () => {
    const html = renderGroup(archived, true);
    expect(html).toContain("历史归档 (2)");
    expect(html).toContain("已交付官网");
    expect(html).toContain("已交付商城");
  });
});
