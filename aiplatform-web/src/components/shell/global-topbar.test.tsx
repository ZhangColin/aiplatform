import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import { deliveryStageNav } from "@/lib/projects/delivery-stages";

import { GlobalTopbar } from "./global-topbar";

// 项目空间路由（需求分析页激活）；登录态可播种（已登录/访客两档）
const meSeed = vi.hoisted(() => ({ me: { displayName: "测试用户" } as object | undefined }));
vi.mock("next/navigation", () => ({ usePathname: () => "/projects/p1" }));
vi.mock("@/hooks/use-me", () => ({
  useMe: () => ({ data: meSeed.me, isPending: false }),
}));

/**
 * 全局顶栏渲染契约（#343，spec #320 ⑥ / #318 全局壳重建·顶栏）：照原型
 * 全量——五行导航（与侧栏重复亦保留，原型如此设计）＋保存状态胶囊（真实
 * 三态，缺省已保存）＋「我的项目」＋「保存项目」＋头像（访客态＝「登录」
 * 文字入口）。话术逐字照原型。
 */

/** 门全关（今日真实投影）：仅需求分析可达。 */
const nav = deliveryStageNav("p1", {
  prdConfirmed: false,
  demoConfirmed: false,
  orderPaid: false,
});

function renderTopbar() {
  return renderToStaticMarkup(<GlobalTopbar projectId="p1" nav={nav} onOpenNav={() => {}} />);
}

describe("GlobalTopbar（项目空间新壳·顶栏）", () => {
  it("五行导航逐字照原型：首页｜需求分析 (PRD)｜Demo 体验｜签约付款｜生产承接", () => {
    const html = renderTopbar();
    expect(html).toContain("首页");
    expect(html).toContain("需求分析 (PRD)");
    expect(html).toContain("Demo 体验");
    expect(html).toContain("签约付款");
    expect(html).toContain("生产承接");
    // 首页是站点级链接；当前页（需求分析）带 aria-current
    expect(html).toContain('href="/"');
    expect(html).toContain('aria-current="page"');
  });

  it("可达性与侧栏同源：门全关时仅需求分析可点，其余灰置（非链接）", () => {
    const html = renderTopbar();
    expect(html).toContain('href="/projects/p1"');
    expect(html).not.toContain('href="/projects/p1/demo"');
    expect((html.match(/aria-disabled="true"/g) ?? []).length).toBe(3);
  });

  it("保存状态胶囊缺省＝已保存，话术逐字照原型", () => {
    const html = renderTopbar();
    expect(html).toContain("浏览器已自动保存 · 绑定可跨设备");
  });

  it("「我的项目」→ /projects；「保存项目」在场（触发 M01 归 interaction 断言）", () => {
    const html = renderTopbar();
    expect(html).toContain("我的项目");
    expect(html).toContain('href="/projects"');
    expect(html).toContain("保存项目");
  });

  it("已登录＝头像（个人菜单迁入顶栏）；不残留旧侧栏词", () => {
    const html = renderTopbar();
    expect(html).toContain('aria-label="个人菜单"');
    expect(html).not.toContain("新建项目");
    expect(html).not.toContain("能做这些");
  });

  it("访客态＝「登录」文字入口（me 无数据），不出头像菜单", () => {
    meSeed.me = undefined;
    const html = renderToStaticMarkup(<GlobalTopbar projectId="p1" nav={nav} onOpenNav={() => {}} />);
    expect(html).toContain("登录");
    expect(html).not.toContain('aria-label="个人菜单"');
  });
});
