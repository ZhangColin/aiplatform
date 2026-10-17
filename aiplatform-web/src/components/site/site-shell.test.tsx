import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import { SiteShell } from "./site-shell";

// 路径可播种（首页 / 项目空间各档）
const pathSeed = vi.hoisted(() => ({ path: "/" }));
vi.mock("next/navigation", () => ({ usePathname: () => pathSeed.path }));

// 历史项目数据驱动（useRecentProjects 走 query，桩掉给一条）——旧壳（首页/列表）
vi.mock("@/hooks/use-projects", () => ({
  useRecentProjects: () => [{ id: "p1", name: "巷口花店小程序" }],
}));

// 同 app-sidebar.test.tsx：桩掉 footer 触发按钮，避免拉入 query client / theme provider
vi.mock("@/components/account-menu", () => ({
  AccountMenu: () => <button data-testid="account-menu">账号</button>,
}));

// 项目空间壳的取数（详情/会话）桩掉——壳装配只测分流，可达性判定归
// delivery-stages 与 shell interaction 测试
vi.mock("@/hooks/use-project", () => ({
  useProject: () => ({ data: undefined, isPending: false }),
}));
vi.mock("@/hooks/use-me", () => ({
  useMe: () => ({ data: { displayName: "测试用户" }, isPending: false }),
}));

describe("SiteShell（#76 侧栏定稿形态装配 / #345 首页去侧栏）", () => {
  it("首页无侧栏（#345，spec #320 ⑥「首页无侧栏」）：仅渲染内容位，旧侧栏件不在", () => {
    pathSeed.path = "/";
    const html = renderToStaticMarkup(<SiteShell>x</SiteShell>);
    expect(html).toBe("x");
    expect(html).not.toContain("新建项目");
    expect(html).not.toContain("能做这些");
    expect(html).not.toContain("历史项目");
  });

  it("/projects（列表）：仍旧壳（P08 重建归 #349）", () => {
    pathSeed.path = "/projects";
    const html = renderToStaticMarkup(<SiteShell>x</SiteShell>);
    expect(html).toContain("历史项目");
    expect(html).not.toContain('data-collapsible="icon"');
  });
});

describe("SiteShell · 项目空间分流（#343：/projects/[id] 及阶段子路径走新壳）", () => {
  it("/projects/[id]：阶段侧栏＋全局顶栏在场，旧侧栏件不在", () => {
    pathSeed.path = "/projects/p1";
    const html = renderToStaticMarkup(<SiteShell>x</SiteShell>);
    // 新壳两件
    expect(html).toContain("交付生命周期阶段");
    expect(html).toContain("保存项目");
    expect(html).toContain("调度引擎状态");
    // 旧壳四件去向（能做这些删/历史项目迁/新建项目＝品牌头回首页）
    expect(html).not.toContain("能做这些");
    expect(html).not.toContain("历史项目");
    expect(html).not.toContain("新建项目");
    expect(html).not.toContain("data-collapsible");
  });

  it("阶段子路径（/projects/p1/demo）同走新壳——阶段页挂点", () => {
    pathSeed.path = "/projects/p1/demo";
    const html = renderToStaticMarkup(<SiteShell>x</SiteShell>);
    expect(html).toContain("交付生命周期阶段");
    expect(html).not.toContain("能做这些");
  });
});
