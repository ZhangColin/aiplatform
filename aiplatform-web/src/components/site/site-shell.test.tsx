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

/** 侧边栏导航锚（菜单项与新建项目按钮均为 render Link 的 <a>）——不含 footer 触发钮。 */
function navAnchors(html: string): { href: string; text: string }[] {
  return (html.match(/<a\b[^>]*href="[^"]*"[^>]*>[\s\S]*?<\/a>/g) ?? []).map((tag) => ({
    href: /href="([^"]*)"/.exec(tag)?.[1] ?? "",
    text: tag.replace(/<[^>]+>/g, "").trim(),
  }));
}

describe("SiteShell（#76 侧栏定稿形态装配——首页/列表共存窗口）", () => {
  it("导航锚 = 新建项目 / 首页 / 历史项目直列 / 全部项目", () => {
    pathSeed.path = "/";
    const html = renderToStaticMarkup(<SiteShell>x</SiteShell>);
    const anchors = navAnchors(html);
    // href 序（新建项目按钮、首页项与「能做这些」两档同指 /；历史项目首字色块会
    // 并入文本，取 href 序断言）
    expect(anchors.map((a) => a.href)).toEqual([
      "/",
      "/",
      "/",
      "/",
      "/projects/p1",
      "/projects",
    ]);
    expect(anchors[0].text).toBe("新建项目");
    expect(anchors[1].text).toBe("首页");
    expect(anchors[2].text).toBe("做系统");
    expect(anchors[3].text).toBe("做设计");
    expect(anchors[4].text).toContain("巷口花店小程序");
    expect(anchors[5].text).toBe("全部项目");
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
