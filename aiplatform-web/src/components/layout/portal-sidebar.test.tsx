import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import { SidebarProvider } from "@/components/ui/sidebar";

import { PortalSidebar, PORTALS } from "./portal-sidebar";

// active 态推导与本测试无关
vi.mock("next/navigation", () => ({ usePathname: () => "/dev/members" }));

// UserMenu / ModeToggle 的渲染契约 = 各自产出一个触发 <button>（真实实现即如此）。
// 桩掉以避免拉入 query client / theme provider，同时保留本测试要锁的嵌套模式。
vi.mock("@/components/user-menu", () => ({
  UserMenu: () => <button data-testid="user-menu">用户</button>,
}));
vi.mock("@/components/mode-toggle", () => ({
  ModeToggle: () => <button data-testid="mode-toggle">主题</button>,
}));

/** 任何 <button> 不得出现在另一个 <button> 内（非法嵌套 → 浏览器修正 DOM → hydration 必炸）。 */
function assertNoNestedButton(html: string) {
  let depth = 0;
  for (const m of html.matchAll(/<\/?button\b[^>]*>/g)) {
    if (m[0].startsWith("</")) {
      depth -= 1;
    } else {
      depth += 1;
      expect(
        depth,
        `<button> 嵌套在另一个 <button> 内: …${html.slice(Math.max(0, (m.index ?? 0) - 160), (m.index ?? 0) + 60)}…`,
      ).toBeLessThanOrEqual(1);
    }
  }
}

describe("PortalSidebar", () => {
  it("切换器 = 三门户 + 后台四项，href 即各落地页（#56；dropdown 内容 SSR 不挂载，数据口径在此锁）", () => {
    expect(PORTALS).toEqual([
      { name: "需求端", href: "/" },
      { name: "开发平台", href: "/dev" },
      { name: "任务平台", href: "/opc" },
      { name: "后台", href: "/admin" },
    ]);
  });

  it("footer 不出现 button 嵌套（hydration 回归：UserMenu/ModeToggle 均为触发按钮）", () => {
    const html = renderSidebar(true);
    assertNoNestedButton(html);
  });

  it("展开态品牌行 = 门户切换 dropdown + 品牌名右侧收起按钮（issue #50）", () => {
    const html = renderSidebar(true);
    // 品牌位仍是门户切换触发器
    expect(html).toContain('data-slot="dropdown-menu-trigger"');
    expect(html).toContain('aria-haspopup="menu"');
    // 收起按钮存在，且在品牌行「AI 开发平台」之后（flex 行内 DOM 顺序 = 视觉右侧）
    const collapseAt = html.indexOf('aria-label="收起菜单"');
    expect(collapseAt).toBeGreaterThan(-1);
    expect(collapseAt).toBeGreaterThan(html.indexOf("AI 开发平台"));
  });

  it("收起态 = 图标条：Logo/空白处展开、不弹切换菜单、导航图标仍可点（issue #50）", () => {
    const html = renderSidebar(false);
    // 整条 rail 处于 icon 收起态
    expect(html).toContain('data-collapsible="icon"');
    // 点 Logo 不弹门户切换菜单：切换触发器整体不渲染
    expect(html).not.toContain('data-slot="dropdown-menu-trigger"');
    expect(html).not.toContain('aria-haspopup="menu"');
    // 两个展开入口：Logo 按钮 + 图标条空白展开层
    expect(html.match(/aria-label="展开菜单"/g)).toHaveLength(2);
    // 导航图标仍在且可导航（href 项渲染为锚点）。「可点」的层序不归 SSR 断言：
    // 依赖 ui/sidebar 的菜单项均为定位元素、绘制在空白展开层之上（见组件注释）
    expect(html).toContain('href="/"');
    expect(html).toContain('href="/projects"');
    // 收起按钮只在展开态品牌行出现
    expect(html).not.toContain('aria-label="收起菜单"');
  });
});

/** 受控 open 渲染出展开/收起两态（SSR 断言，沿用仓内组件测试模式）。 */
function renderSidebar(open: boolean) {
  return renderToStaticMarkup(
    <SidebarProvider open={open}>
      <PortalSidebar
        portal="开发平台"
        groups={[
          {
            items: [
              { key: "home", label: "首页", href: "/" },
              { key: "projects", label: "我的项目", href: "/projects" },
            ],
          },
        ]}
      />
    </SidebarProvider>,
  );
}
