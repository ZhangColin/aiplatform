import { describe, expect, it } from "vitest";

import {
  FILTER_STATUS,
  PROJECT_LIST_FILTERS,
  normalizeProjectSummary,
  recentProjects,
  visibleProjects,
  type ProjectSummary,
} from "./list";

describe("项目列表过滤（issue #17 清场后三态；订单四态随交易环重组）", () => {
  it("三态 → status 参数映射：全部不传，进行中 1 / 已归档 3；顺序即呈现序", () => {
    expect(FILTER_STATUS.all).toBeUndefined();
    expect(FILTER_STATUS.active).toBe(1);
    expect(FILTER_STATUS.archived).toBe(3);
    expect(PROJECT_LIST_FILTERS.map((f) => f.key)).toEqual(["all", "active", "archived"]);
  });

  it("「全部」本地过滤已归档（防御后端 all 语义）；其余视图原样透传（服务端已按 status 过滤）", () => {
    const items: ProjectSummary[] = [
      summary({ id: "1", archived: false }),
      summary({ id: "2", archived: true }),
      summary({ id: "3" }), // archived 缺省 = 未归档
    ];
    expect(visibleProjects(items, "all").map((p) => p.id)).toEqual(["1", "3"]);
    expect(visibleProjects(items, "active")).toHaveLength(3);
    expect(visibleProjects(items, "archived")).toHaveLength(3);
  });

  it("normalize：缺省字段防御归一（id/name 必有、archived 缺省 false）", () => {
    expect(normalizeProjectSummary({ id: "p1" })).toMatchObject({
      id: "p1",
      name: "",
      archived: false,
    });
    expect(
      normalizeProjectSummary({ id: "p2", name: "宠物医院官网", statusName: "进行中" }),
    ).toMatchObject({ id: "p2", name: "宠物医院官网", statusName: "进行中" });
  });
});

describe("最近项目（首页：createdAt 倒序取前 N）", () => {
  it("按 createdAt 倒序取前 4；缺 createdAt 沉底不抛", () => {
    const items = [
      { id: "old", createdAt: "2026-08-01T00:00:00Z" },
      { id: "none" },
      { id: "new", createdAt: "2026-08-22T00:00:00Z" },
      { id: "mid1", createdAt: "2026-08-10T00:00:00Z" },
      { id: "mid2", createdAt: "2026-08-05T00:00:00Z" },
      { id: "none2" },
    ];
    expect(recentProjects(items).map((p) => p.id)).toEqual(["new", "mid1", "mid2", "old"]);
  });

  it("limit 可控；不改写原数组（纯函数）", () => {
    const input = [
      { id: "a", createdAt: "2026-08-02T00:00:00Z" },
      { id: "b", createdAt: "2026-08-01T00:00:00Z" },
      { id: "c", createdAt: "2026-08-03T00:00:00Z" },
    ];
    expect(recentProjects(input, 2).map((p) => p.id)).toEqual(["c", "a"]);
    expect(input.map((p) => p.id)).toEqual(["a", "b", "c"]);
  });
});

function summary(overrides: Partial<ProjectSummary>): ProjectSummary {
  return { id: "x", name: "x", archived: false, ...overrides };
}
