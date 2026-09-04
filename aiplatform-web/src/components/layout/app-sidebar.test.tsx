import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import { SidebarProvider } from "@/components/ui/sidebar";

import { AppSidebar } from "./app-sidebar";

// active 态推导与本测试无关
vi.mock("next/navigation", () => ({ usePathname: () => "/" }));

// 历史项目数据驱动（useRecentProjects 走 query，桩掉并给两条）
vi.mock("@/hooks/use-projects", () => ({
  useRecentProjects: () => [
    { id: "p1", name: "巷口花店小程序" },
    { id: "p2", name: "社区团购站" },
  ],
}));

// AccountMenu 的渲染契约 = 产出一个触发 <button>。桩掉以避免拉入
// query client / theme provider，同时保留本测试要锁的嵌套模式。
vi.mock("@/components/account-menu", () => ({
  AccountMenu: () => <button data-testid="account-menu">账号</button>,
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

describe("AppSidebar（#76 侧栏定稿形态）", () => {
  it("展开态：新建项目主按钮 + 首页 +「能做这些」+ 历史项目色块 + 全部项目", () => {
    const html = renderSidebar(true);
    expect(html).toContain("新建项目");
    expect(html).toContain('href="/"');
    expect(html).toContain("能做这些");
    expect(html).toContain("做系统");
    expect(html).toContain("做页面");
    expect(html).toContain("历史项目");
    expect(html).toContain("巷口花店小程序");
    expect(html).toContain('href="/projects/p1"');
    expect(html).toContain('href="/projects"'); // 全部项目
  });

  it("footer 不出现 button 嵌套（hydration 回归：AccountMenu 为触发按钮）", () => {
    const html = renderSidebar(true);
    assertNoNestedButton(html);
  });

  it("展开态品牌行 = 品牌 + 收起按钮（收起按钮在品牌名之后）", () => {
    const html = renderSidebar(true);
    const collapseAt = html.indexOf('aria-label="收起菜单"');
    expect(collapseAt).toBeGreaterThan(-1);
    expect(collapseAt).toBeGreaterThan(html.indexOf("AI 开发平台"));
  });

  it("收起态 = 图标条：Logo/空白处展开、导航图标仍可导航", () => {
    const html = renderSidebar(false);
    expect(html).toContain('data-collapsible="icon"');
    expect(html.match(/aria-label="展开菜单"/g)).toHaveLength(2);
    expect(html).toContain('href="/"');
    expect(html).toContain('href="/projects/p1"');
    expect(html).not.toContain('aria-label="收起菜单"');
  });
});

/** 受控 open 渲染出展开/收起两态（SSR 断言，沿用仓内组件测试模式）。 */
function renderSidebar(open: boolean) {
  return renderToStaticMarkup(
    <SidebarProvider open={open}>
      <AppSidebar />
    </SidebarProvider>,
  );
}
