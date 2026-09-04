import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import { SiteShell } from "./site-shell";

// active 态推导与本测试无关（首页高亮与否不改变菜单结构）
vi.mock("next/navigation", () => ({ usePathname: () => "/" }));

// 历史项目数据驱动（useRecentProjects 走 query，桩掉给一条）
vi.mock("@/hooks/use-projects", () => ({
  useRecentProjects: () => [{ id: "p1", name: "巷口花店小程序" }],
}));

// 同 app-sidebar.test.tsx：桩掉 footer 触发按钮，避免拉入 query client / theme provider
vi.mock("@/components/account-menu", () => ({
  AccountMenu: () => <button data-testid="account-menu">账号</button>,
}));

/** 侧边栏导航锚（菜单项与新建项目按钮均为 render Link 的 <a>）——不含 footer 触发钮。 */
function navAnchors(html: string): { href: string; text: string }[] {
  return (html.match(/<a\b[^>]*href="[^"]*"[^>]*>[\s\S]*?<\/a>/g) ?? []).map((tag) => ({
    href: /href="([^"]*)"/.exec(tag)?.[1] ?? "",
    text: tag.replace(/<[^>]+>/g, "").trim(),
  }));
}

describe("SiteShell（#76 侧栏定稿形态装配）", () => {
  it("导航锚 = 新建项目 / 首页 / 历史项目直列 / 全部项目", () => {
    const html = renderToStaticMarkup(<SiteShell>x</SiteShell>);
    const anchors = navAnchors(html);
    // href 序（新建项目按钮与首页项同指 /；历史项目首字色块会并入文本，取 href 序断言）
    expect(anchors.map((a) => a.href)).toEqual([
      "/",
      "/",
      "/projects/p1",
      "/projects",
    ]);
    expect(anchors[0].text).toBe("新建项目");
    expect(anchors[1].text).toBe("首页");
    expect(anchors[2].text).toContain("巷口花店小程序");
    expect(anchors[3].text).toBe("全部项目");
  });
});
