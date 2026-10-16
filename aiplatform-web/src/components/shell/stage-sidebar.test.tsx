import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import type { StageNavItem } from "@/lib/projects/delivery-stages";
import { currentStageOf, deliveryStageNav } from "@/lib/projects/delivery-stages";

import { StageSidebar } from "./stage-sidebar";

// 路由激活自推（activeStageOf），SSR 下桩路径
vi.mock("next/navigation", () => ({ usePathname: () => "/projects/p1" }));

/**
 * 阶段侧栏渲染契约（#343，spec #320 ⑥ / #318 全局壳重建·侧栏）：结构照
 * 原型——品牌头＋「交付生命周期阶段」四阶段（序号/完成 ✓/灰置）＋「工作
 * 空间」组＋底部「调度引擎状态」卡；话术逐字照原型（含 GRAVITY-CORE/
 * DELIVERY PIPELINE 装饰文案）。现有侧栏拆解去向同时锁：能做这些删、
 * 新建项目＝品牌头回首页、历史项目迁「我的交付项目」。
 */

/** 门全关（今日真实投影）：仅需求分析可达，其余灰置。 */
const lockedNav = deliveryStageNav("p1", {
  prdConfirmed: false,
  demoConfirmed: false,
  orderPaid: false,
});

/** 全推进态：四阶段可达、前三完成。 */
const openNav = deliveryStageNav("p1", {
  prdConfirmed: true,
  demoConfirmed: true,
  orderPaid: true,
});

function renderSidebar(nav: StageNavItem[] = lockedNav) {
  return renderToStaticMarkup(
    <StageSidebar projectId="p1" nav={nav} currentStage={currentStageOf({
      prdConfirmed: false,
      demoConfirmed: false,
      orderPaid: false,
    })} />,
  );
}

describe("StageSidebar（项目空间新壳·侧栏）", () => {
  it("品牌头＝深色圆底 logo＋「语义引力」＋「DELIVERY PIPELINE」，品牌头即回首页（新建项目去向）", () => {
    const html = renderSidebar();
    expect(html).toContain('src="/brand/logo-dark-round.svg"');
    expect(html).toContain("语义引力");
    expect(html).toContain("DELIVERY PIPELINE");
    // 品牌头回首页：包裹链接落 /
    const brandLink = /<a\b[^>]*href="\/"[^>]*>[\s\S]*?DELIVERY PIPELINE/.exec(html);
    expect(brandLink).not.toBeNull();
  });

  it("「交付生命周期阶段」组：四阶段序号与名称逐字照原型", () => {
    const html = renderSidebar();
    expect(html).toContain("交付生命周期阶段");
    for (const label of ["需求分析 (PRD)", "Demo 体验", "签约付款", "生产承接"]) {
      expect(html).toContain(label);
    }
    // 序号 1–4 以序号位呈现（序号 chip 独立于阶段名文本）
    expect(html.match(/>1</g)).not.toBeNull();
    expect(html.match(/>2</g)).not.toBeNull();
    expect(html.match(/>3</g)).not.toBeNull();
    expect(html.match(/>4</g)).not.toBeNull();
  });

  it("可达阶段＝导航链接；灰置阶段＝非链接按钮带 aria-disabled（门全关时仅需求分析可达）", () => {
    const html = renderSidebar();
    expect(html).toContain('href="/projects/p1"');
    expect(html).not.toContain('href="/projects/p1/demo"');
    expect(html).not.toContain('href="/projects/p1/contract"');
    expect(html).not.toContain('href="/projects/p1/production"');
    expect((html.match(/aria-disabled="true"/g) ?? []).length).toBe(3);
  });

  it("全推进态：四阶段皆链接、完成 ✓ 落前三阶段（生产承接终阶段无 ✓）", () => {
    const html = renderSidebar(openNav);
    expect(html).toContain('href="/projects/p1/demo"');
    expect(html).toContain('href="/projects/p1/contract"');
    expect(html).toContain('href="/projects/p1/production"');
    expect((html.match(/aria-disabled="true"/g) ?? []).length).toBe(0);
    expect((html.match(/>✓</g) ?? []).length).toBe(3);
  });

  it("「工作空间」组：「我的交付项目」→ /projects；「系统架构与规约」装饰入口照留、点击无落地（非链接）", () => {
    const html = renderSidebar();
    expect(html).toContain("工作空间");
    expect(html).toContain("我的交付项目");
    expect(html).toContain('href="/projects"');
    expect(html).toContain("系统架构与规约");
    // 装饰入口无落地：链接锚恰三枚（品牌头回首页/需求分析/我的交付项目）——
    // 不锚 <a> 会被 React 19 img 自动 preload 的 <link as="image"> 假阳性污染
    const hrefs = [...html.matchAll(/<a\b[^>]*href="([^"]*)"/g)].map((m) => m[1]);
    expect(hrefs).toEqual(["/", "/projects/p1", "/projects"]);
  });

  it("底部「调度引擎状态」卡：话术逐字照原型（ONLINE/GRAVITY-CORE/v4.1）＋4/5 进度条", () => {
    const html = renderSidebar();
    expect(html).toContain("调度引擎状态");
    expect(html).toContain("ONLINE");
    expect(html).toContain("GRAVITY-CORE");
    expect(html).toContain("v4.1");
    expect(html).toContain("w-4/5");
  });

  it("现有侧栏拆解去向：不出现「能做这些」「新建项目」「历史项目」「全部项目」", () => {
    const html = renderSidebar();
    expect(html).not.toContain("能做这些");
    expect(html).not.toContain("新建项目");
    expect(html).not.toContain("历史项目");
    expect(html).not.toContain("全部项目");
    expect(html).not.toContain("做系统");
    expect(html).not.toContain("做设计");
  });
});
