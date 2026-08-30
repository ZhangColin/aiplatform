import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import { UserPortalShell } from "./portal-shell";

// active 态推导与本测试无关（首页高亮与否不改变菜单结构）
vi.mock("next/navigation", () => ({ usePathname: () => "/" }));

// 同 portal-sidebar.test.tsx：桩掉 footer 触发按钮，避免拉入 query client / theme provider
vi.mock("@/components/user-menu", () => ({
  UserMenu: () => <button data-testid="user-menu">用户</button>,
}));
vi.mock("@/components/mode-toggle", () => ({
  ModeToggle: () => <button data-testid="mode-toggle">主题</button>,
}));

/** 侧边栏导航锚（SidebarMenuButton render Link）——不含切换器触发钮与 footer。 */
function navAnchors(html: string): { href: string; label: string }[] {
  return (html.match(/<a\b[^>]*data-sidebar="menu-button"[^>]*>[\s\S]*?<\/a>/g) ?? []).map(
    (tag) => ({
      href: /href="([^"]*)"/.exec(tag)?.[1] ?? "",
      label: tag.replace(/<[^>]+>/g, "").trim(),
    }),
  );
}

describe("UserPortalShell（需求端菜单，spec 0002 §2 修订 issue #49）", () => {
  it("菜单仅「首页」「我的项目」两项，href 正确——项目直列与平台组各项不出现", () => {
    const html = renderToStaticMarkup(<UserPortalShell>x</UserPortalShell>);
    const items = navAnchors(html);
    expect(items).toEqual([
      { href: "/", label: "首页" },
      { href: "/projects", label: "我的项目" },
    ]);
  });
});
