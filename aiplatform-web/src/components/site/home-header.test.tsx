import { renderToStaticMarkup } from "react-dom/server";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { HomeHeader } from "./home-header";

/**
 * 首页顶栏渲染契约（#345，spec #320 ⑥ 全局壳·首页无侧栏、顶栏照首页形）：品牌
 * 位（圆底 logo +「语义引力」+ OS 徽标 +「Semantic Gravity · Delivery OS」副行）
 * + 五行导航（首页＋四阶段）＋保存状态胶囊/「我的项目」/「保存项目」/头像
 * （访客＝「登录」）。首页无项目上下文：阶段导航指向最近项目的阶段页（无则回首页）。
 */

const seed = vi.hoisted(() => ({
  me: undefined as object | undefined,
  projects: [] as { id: string; name: string }[],
}));

vi.mock("next/navigation", () => ({ usePathname: () => "/" }));
vi.mock("@/hooks/use-me", () => ({ useMe: () => ({ data: seed.me, isPending: false }) }));
vi.mock("@/hooks/use-projects", () => ({ useRecentProjects: () => seed.projects }));
// 个人菜单会拉 query client / theme provider——桩掉
vi.mock("@/components/account-menu", () => ({
  AccountMenu: () => <button data-testid="account-menu">账号</button>,
}));

beforeEach(() => {
  seed.me = undefined;
  seed.projects = [];
});

describe("HomeHeader（首页顶栏·照首页形）", () => {
  it("品牌位逐字：语义引力 + OS 徽标 + Semantic Gravity · Delivery OS", () => {
    const html = renderToStaticMarkup(<HomeHeader credentialPresent />);
    expect(html).toContain("语义引力");
    expect(html).toContain("OS");
    expect(html).toContain("Semantic Gravity · Delivery OS");
  });

  it("五行导航逐字：首页｜需求分析 (PRD)｜Demo 体验｜签约付款｜生产承接，首页当前页", () => {
    const html = renderToStaticMarkup(<HomeHeader credentialPresent />);
    expect(html).toContain("首页");
    expect(html).toContain("需求分析 (PRD)");
    expect(html).toContain("Demo 体验");
    expect(html).toContain("签约付款");
    expect(html).toContain("生产承接");
    expect(html).toContain('aria-current="page"');
  });

  it("右侧状态与动作照原型：保存胶囊 + 我的项目 + 保存项目 + 访客登录入口", () => {
    const html = renderToStaticMarkup(<HomeHeader credentialPresent />);
    expect(html).toContain("浏览器已自动保存 · 绑定可跨设备");
    expect(html).toContain("我的项目");
    expect(html).toContain('href="/projects"');
    expect(html).toContain("保存项目");
    expect(html).toContain("登录");
  });

  it("首页无项目上下文：阶段导航回首页（href=\"/\"），不残留旧侧栏词", () => {
    const html = renderToStaticMarkup(<HomeHeader credentialPresent />);
    expect(html).not.toContain("新建项目");
    expect(html).not.toContain("能做这些");
    expect(html).not.toContain("历史项目");
  });

  it("有最近项目：阶段导航指向该项目的阶段页", () => {
    seed.projects = [{ id: "p1", name: "官网改版" }];
    const html = renderToStaticMarkup(<HomeHeader credentialPresent />);
    expect(html).toContain('href="/projects/p1"');
    expect(html).toContain('href="/projects/p1/demo"');
  });
});
