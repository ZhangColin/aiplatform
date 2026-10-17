import { renderToStaticMarkup } from "react-dom/server";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { HomeHero } from "./home-hero";

/**
 * 首页 Hero 渲染契约（#345，spec #320 ⑥ 首页 P01 照原型）：话术逐字保留——
 * 徽章/主标/副标/大输入卡（附加资料＋免注册提示＋CTA「开始梳理需求」）/示例
 * chip×3；三件删除（模式切换 pill/模板卡/最近项目卡区）。
 */

const seed = vi.hoisted(() => ({ projects: [] as { id: string; name: string; updatedAt?: string }[] }));

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock("@/hooks/use-create-project", () => ({
  useCreateProject: () => ({ isPending: false, mutate: vi.fn() }),
}));
vi.mock("@/hooks/use-projects", () => ({ useRecentProjects: () => seed.projects }));

beforeEach(() => {
  seed.projects = [];
});

describe("HomeHero（首页 Hero 文案逐字照原型）", () => {
  it("徽章/主标/副标逐字照原型", () => {
    const html = renderToStaticMarkup(<HomeHero />);
    expect(html).toContain("SEMANTIC GRAVITY · AI NATIVE DELIVERY OS");
    expect(html).toContain("你想做一个什么产品？");
    expect(html).toContain("聊清需求，形成 PRD，再体验你的专属 Demo。所见即所得，零盲盒交付。");
  });

  it("大输入卡：附加资料 + 免注册提示 + CTA「开始梳理需求」＋ placeholder 逐字", () => {
    const html = renderToStaticMarkup(<HomeHero />);
    expect(html).toContain("附加资料");
    expect(html).toContain("免注册即可开始 · 私有云自动隔离保存");
    expect(html).toContain("开始梳理需求");
    expect(html).toContain(
      "说说你想解决的问题，比如做一个客户预约系统，支持移动端日历选择、顾问分配和核销统计……",
    );
  });

  it("示例 chip×3 题目/概述逐字照原型", () => {
    const html = renderToStaticMarkup(<HomeHero />);
    expect(html).toContain("做一个预约系统");
    expect(html).toContain("移动端选时、日历排期与自动化核销看板");
    expect(html).toContain("把表格流程做成工具");
    expect(html).toContain("多表分散统计转为结构化协同与权限系统");
    expect(html).toContain("我有想法，帮我理清");
    expect(html).toContain("语义启发式对齐，层层拆解直接生成结构化PRD");
  });

  it("三件删除：无模式切换 pill、无模板卡、无最近项目卡区", () => {
    const html = renderToStaticMarkup(<HomeHero />);
    expect(html).not.toContain("先做设计");
    expect(html).not.toContain("先做系统");
    expect(html).not.toContain("点模板一句话开工");
    expect(html).not.toContain("最近的项目");
  });

  it("草稿恢复横幅：有最近项目 → 项目名 + 继续梳理 + 指到该项目", () => {
    seed.projects = [{ id: "p1", name: "企业内部CRM与智能工单管理系统" }];
    const html = renderToStaticMarkup(<HomeHero />);
    expect(html).toContain("草稿恢复");
    expect(html).toContain("企业内部CRM与智能工单管理系统");
    expect(html).toContain("继续梳理");
    expect(html).toContain('href="/projects/p1"');
  });

  it("无会话不呈现：无最近项目 → 无草稿恢复横幅", () => {
    const html = renderToStaticMarkup(<HomeHero />);
    expect(html).not.toContain("草稿恢复");
  });
});
