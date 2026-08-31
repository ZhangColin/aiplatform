import { describe, expect, it } from "vitest";

import {
  PROJECT_STAGES,
  lastTouchedAt,
  normalizeProjectSummary,
  projectListSections,
  projectStage,
  recentProjects,
  stageLabel,
  type ProjectSummary,
} from "./list";

describe("四态（issue #21：全量拉取 + 本地分区，订单态接线前两活态为空壳）", () => {
  it("过滤位呈现序 = 进行中/待报价/待支付/已归档，标签齐备", () => {
    expect(PROJECT_STAGES).toEqual([
      { key: "in_progress", label: "进行中" },
      { key: "awaiting_quote", label: "待报价" },
      { key: "awaiting_payment", label: "待支付" },
      { key: "archived", label: "已归档" },
    ]);
    expect(stageLabel("archived")).toBe("已归档");
  });

  it("口径：已归档优先；未归档 = 进行中（无未终结订单且未归档——待报价/待支付待订单事实，本片不可达）", () => {
    expect(projectStage({ archived: false })).toBe("in_progress");
    expect(projectStage({ archived: true })).toBe("archived");
  });

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
});

describe("列表分区（主网格 = 选中态；历史归档默认折叠分组）", () => {
  const items: ProjectSummary[] = [
    summary({ id: "live1", archived: false }),
    summary({ id: "done1", archived: true }),
    summary({ id: "live2", archived: false }),
    summary({ id: "done2", archived: true }),
  ];

  it("进行中：主网格 = 未归档项目，归档沉入折叠分组", () => {
    const sections = projectListSections(items, "in_progress");
    expect(sections.main.map((p) => p.id)).toEqual(["live1", "live2"]);
    expect(sections.archivedGroup.map((p) => p.id)).toEqual(["done1", "done2"]);
  });

  it("已归档：主网格即归档全量，折叠分组不再重复出", () => {
    const sections = projectListSections(items, "archived");
    expect(sections.main.map((p) => p.id)).toEqual(["done1", "done2"]);
    expect(sections.archivedGroup).toEqual([]);
  });

  it("待报价/待支付：主网格恒空（空壳过滤位），归档分组照常沉底", () => {
    for (const stage of ["awaiting_quote", "awaiting_payment"] as const) {
      const sections = projectListSections(items, stage);
      expect(sections.main).toEqual([]);
      expect(sections.archivedGroup.map((p) => p.id)).toEqual(["done1", "done2"]);
    }
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
