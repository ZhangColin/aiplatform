import { describe, expect, it } from "vitest";

import {
  lastTouchedAt,
  normalizeProjectSummary,
  projectListSections,
  projectPendingAction,
  projectStage,
  projectTransactionSubstate,
  recentProjects,
  stageLabel,
  type ProjectSummary,
} from "./list";

describe("列表摘要归一", () => {
  it("normalize：缺省字段防御归一（id/name 必有、archived 缺省 false、updatedAt 透传）", () => {
    expect(normalizeProjectSummary({ id: "p1" })).toMatchObject({
      id: "p1",
      name: "",
      archived: false,
      updatedAt: undefined,
    });
    expect(
      normalizeProjectSummary({
        id: "p2",
        name: "宠物医院官网",
        createdAt: "2026-08-20T00:00:00Z",
        updatedAt: "2026-08-28T00:00:00Z",
      }),
    ).toMatchObject({
      id: "p2",
      name: "宠物医院官网",
      createdAt: "2026-08-20T00:00:00Z",
      updatedAt: "2026-08-28T00:00:00Z",
    });
  });

  it("normalize：终点类型透传（#299 弱标识取数面：code＋名随行、缺省 undefined）", () => {
    expect(
      normalizeProjectSummary({ id: "p3", endpointType: 1, endpointTypeName: "设计" }),
    ).toMatchObject({ endpointType: 1, endpointTypeName: "设计" });
    expect(normalizeProjectSummary({ id: "p4" }).endpointType).toBeUndefined();
  });

  it("normalize：P08 三项事实透传，旧后端缺省归一为 false", () => {
    expect(normalizeProjectSummary({ id: "p5" })).toMatchObject({
      prdConfirmed: false,
      demoConfirmed: false,
      needsUserSupplement: false,
    });
    expect(normalizeProjectSummary({
      id: "p6",
      prdConfirmed: true,
      demoConfirmed: true,
      needsUserSupplement: true,
    })).toMatchObject({
      prdConfirmed: true,
      demoConfirmed: true,
      needsUserSupplement: true,
    });
  });
});

describe("P08 生命周期阶段徽标（#349）", () => {
  it("按 PRD 确认 → Demo 确认 → 付款完成推导四阶段，归档优先保留", () => {
    expect(projectStage(summary({}))).toBe("prd");
    expect(projectStage(summary({ prdConfirmed: true }))).toBe("demo");
    expect(projectStage(summary({ prdConfirmed: true, demoConfirmed: true }))).toBe("contract");
    expect(projectStage(summary({
      prdConfirmed: true,
      demoConfirmed: true,
      activeOrderStatus: 3,
    }))).toBe("production");
    expect(projectStage(summary({ archived: true, prdConfirmed: true, demoConfirmed: true })))
      .toBe("archived");
  });

  it("存量订单缺双确认事实时仍以真实订单阶段为准，不回退需求分析或 Demo", () => {
    expect(projectStage(summary({ activeOrderStatus: 1 }))).toBe("contract");
    expect(projectStage(summary({ activeOrderStatus: 2 }))).toBe("contract");
    expect(projectStage(summary({ activeOrderStatus: 3 }))).toBe("production");
    expect(projectTransactionSubstate(summary({ activeOrderStatus: 1 }))).toBe("待报价");
    expect(projectTransactionSubstate(summary({ activeOrderStatus: 2 }))).toBe("待支付");
  });

  it("徽标词表使用交付生命周期名称，不再把交易子态当主阶段", () => {
    expect(stageLabel("prd")).toBe("需求分析");
    expect(stageLabel("demo")).toBe("Demo 体验");
    expect(stageLabel("contract")).toBe("签约付款");
    expect(stageLabel("production")).toBe("生产承接");
    expect(stageLabel("archived")).toBe("已归档");
  });

  it("待报价/待支付只作签约付款副行；待办优先呈需用户补充", () => {
    expect(projectTransactionSubstate(summary({
      prdConfirmed: true,
      demoConfirmed: true,
      activeOrderStatus: 1,
    }))).toBe("待报价");
    expect(projectTransactionSubstate(summary({
      prdConfirmed: true,
      demoConfirmed: true,
      activeOrderStatus: 2,
    }))).toBe("待支付");
    expect(projectTransactionSubstate(summary({
      prdConfirmed: true,
      demoConfirmed: true,
      activeOrderStatus: 3,
    }))).toBeUndefined();

    expect(projectPendingAction(summary({ needsUserSupplement: true })))
      .toBe("补充资料或确认事项");
    expect(projectPendingAction(summary({}))).toBe("继续梳理需求");
    expect(projectPendingAction(summary({ prdConfirmed: true })))
      .toBe("体验并确认 Demo 版本");
    expect(projectPendingAction(summary({ prdConfirmed: true, demoConfirmed: true })))
      .toBe("进入签约付款");
    expect(projectPendingAction(summary({
      prdConfirmed: true,
      demoConfirmed: true,
      activeOrderStatus: 1,
    }))).toBe("暂无，等待平台报价");
    expect(projectPendingAction(summary({
      prdConfirmed: true,
      demoConfirmed: true,
      activeOrderStatus: 2,
    }))).toBe("核对范围并完成签约付款");
    expect(projectPendingAction(summary({
      prdConfirmed: true,
      demoConfirmed: true,
      activeOrderStatus: 3,
    }))).toBe("暂无，等待交付更新");
    expect(projectPendingAction(summary({ archived: true }))).toBe("已归档，无待处理事项");
  });
});

describe("P08 列表分区与最近更新排序", () => {
  it("未归档入主网格、已归档保留折叠组；两组均按最后更新新到旧", () => {
    const items: ProjectSummary[] = [
      summary({ id: "active-old", updatedAt: "2026-08-01T00:00:00Z" }),
      summary({ id: "archived-old", archived: true, updatedAt: "2026-08-02T00:00:00Z" }),
      summary({ id: "active-new", updatedAt: "2026-08-04T00:00:00Z" }),
      summary({ id: "archived-new", archived: true, updatedAt: "2026-08-03T00:00:00Z" }),
    ];

    const sections = projectListSections(items);

    expect(sections.main.map((p) => p.id)).toEqual(["active-new", "active-old"]);
    expect(sections.archivedGroup.map((p) => p.id)).toEqual(["archived-new", "archived-old"]);
    expect(items.map((p) => p.id)).toEqual(["active-old", "archived-old", "active-new", "archived-new"]);
  });

  it("normalizeProjectSummary：activeOrder 嵌入归一为状态 code（无订单 undefined）", () => {
    expect(
      normalizeProjectSummary({ id: "1", name: "n", archived: false, activeOrder: { id: "o1", status: 1, statusName: "待报价" } }),
    ).toMatchObject({ id: "1", activeOrderStatus: 1 });
    expect(normalizeProjectSummary({ id: "2", name: "n", archived: false }).activeOrderStatus)
      .toBeUndefined();
  });
});

describe("最近项目（首页：更新时间新→旧取前 N；updatedAt 缺失以 createdAt 代）", () => {
  it("按 updatedAt 倒序——updatedAt 比 createdAt 更晚的项目浮到最前", () => {
    const items = [
      { id: "old", createdAt: "2026-08-01T00:00:00Z" },
      { id: "touched", createdAt: "2026-08-05T00:00:00Z", updatedAt: "2026-08-23T00:00:00Z" },
      { id: "new", createdAt: "2026-08-22T00:00:00Z" },
    ];
    expect(recentProjects(items).map((p) => p.id)).toEqual(["touched", "new", "old"]);
  });

  it("updatedAt 缺失回退 createdAt；两列全缺沉底不抛", () => {
    const items = [
      { id: "none" },
      { id: "byCreated", createdAt: "2026-08-10T00:00:00Z" },
      { id: "none2" },
    ];
    expect(recentProjects(items).map((p) => p.id)).toEqual(["byCreated", "none", "none2"]);
  });

  it("limit 可控；不改写原数组（纯函数）", () => {
    const input = [
      { id: "a", updatedAt: "2026-08-02T00:00:00Z" },
      { id: "b", updatedAt: "2026-08-01T00:00:00Z" },
      { id: "c", updatedAt: "2026-08-03T00:00:00Z" },
    ];
    expect(recentProjects(input, 2).map((p) => p.id)).toEqual(["c", "a"]);
    expect(input.map((p) => p.id)).toEqual(["a", "b", "c"]);
  });
});

describe("lastTouchedAt（最近动静时点：排序与首页行共用的回退口径）", () => {
  it("updatedAt 优先；缺失/畸形回退 createdAt；两列全缺 undefined", () => {
    expect(lastTouchedAt({ updatedAt: "2026-08-28T00:00:00Z", createdAt: "2026-08-01T00:00:00Z" }))
      .toBe("2026-08-28T00:00:00Z");
    expect(lastTouchedAt({ createdAt: "2026-08-01T00:00:00Z" })).toBe("2026-08-01T00:00:00Z");
    expect(lastTouchedAt({ updatedAt: "不是时间", createdAt: "2026-08-01T00:00:00Z" }))
      .toBe("2026-08-01T00:00:00Z");
    expect(lastTouchedAt({})).toBeUndefined();
  });
});

function summary(overrides: Partial<ProjectSummary>): ProjectSummary {
  return { id: "x", name: "x", archived: false, ...overrides };
}
