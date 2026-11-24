import { describe, expect, it } from "vitest";

import { normalizeProjectDetail, planAreaOfDesignItems, generationStateOf, type ProjectDetailResponse } from "./detail";

function raw(overrides: Partial<ProjectDetailResponse> = {}): ProjectDetailResponse {
  return { id: "p1", name: "花店小程序", ...overrides };
}

describe("generationStateOf · 生成态投影映射（#222；#604 四态归一——无中断档）", () => {
  it("三档 code 归一：1=从未生成、2=生成中、4=已生成；缺省 undefined", () => {
    expect(generationStateOf(1)).toBe("never");
    expect(generationStateOf(2)).toBe("generating");
    expect(generationStateOf(4)).toBe("generated");
    expect(generationStateOf(undefined)).toBeUndefined();
    expect(generationStateOf(null)).toBeUndefined();
  });

  it("已退役的中断档（code 3）不再映射——失败/停止的项目由服务端落「从未生成」，失败只走会话态呈现", () => {
    expect(generationStateOf(3)).toBeUndefined();
  });
});

describe("normalizeProjectDetail · 生成轨道片清单（#225 计划区只读透出）", () => {
  it("片行归一：ord/描述/状态 code → 字面量联合，保序", () => {
    const detail = normalizeProjectDetail(
      raw({
        segments: [
          { ord: 0, description: "系统初始化", status: 2, statusName: "已收口" },
          { ord: 1, description: "用户能注册登录", status: 3, statusName: "失败" },
          { ord: 2, description: "用户能下单支付", status: 1, statusName: "待跑" },
        ],
      }),
    );

    expect(detail.segments).toEqual([
      { ord: 0, description: "系统初始化", status: "closed" },
      { ord: 1, description: "用户能注册登录", status: "failed" },
      { ord: 2, description: "用户能下单支付", status: "pending" },
    ]);
  });

  it("无片行 / 空数组 / 缺字段 = null（无现行计划——锚过期或未落库，不伪造计划）", () => {
    expect(normalizeProjectDetail(raw()).segments).toBeNull();
    expect(normalizeProjectDetail(raw({ segments: [] })).segments).toBeNull();
  });

  it("防御归一：缺 ord 或缺描述的行剔除；未知状态 code 回落 pending（多跑向安全）", () => {
    const detail = normalizeProjectDetail(
      raw({
        segments: [
          { ord: 0, description: "系统初始化" },
          { description: "缺序号的行", status: 1 },
          { ord: 1, description: "用户能注册登录", status: 99 },
        ],
      }),
    );

    expect(detail.segments).toEqual([
      { ord: 0, description: "系统初始化", status: "pending" },
      { ord: 1, description: "用户能注册登录", status: "pending" },
    ]);
  });
});

describe("normalizeProjectDetail · 设计轨道件清单（#290 计划区；#293 画布富形状）", () => {
  it("件行归一：ord/标题/状态四值（含已定稿）＋finalizedPath 透传，保序", () => {
    const detail = normalizeProjectDetail(
      raw({
        designItems: [
          { ord: 1, title: "首页主视觉", status: 2, statusName: "已收口" },
          { ord: 2, title: "logo 主标识", status: 4, statusName: "已定稿", finalizedPath: "/design/logo-2.png" },
          { ord: 3, title: "包装盒平面", status: 3, statusName: "失败" },
        ],
      }),
    );

    expect(detail.designItems).toEqual([
      { ord: 1, title: "首页主视觉", status: "closed", finalizedPath: undefined },
      { ord: 2, title: "logo 主标识", status: "finalized", finalizedPath: "/design/logo-2.png" },
      { ord: 3, title: "包装盒平面", status: "failed", finalizedPath: undefined },
    ]);
  });

  it("无件行 / 空数组 = null（系统终点项目或清单未落库——不伪造计划）", () => {
    expect(normalizeProjectDetail(raw()).designItems).toBeNull();
    expect(normalizeProjectDetail(raw({ designItems: [] })).designItems).toBeNull();
  });

  it("防御归一：缺 ord 或缺标题的行剔除；未知状态 code 回落 pending；空 finalizedPath 不透传", () => {
    const detail = normalizeProjectDetail(
      raw({
        designItems: [
          { ord: 1, title: "首页主视觉", status: 99, finalizedPath: "" },
          { title: "缺序号" },
        ],
      }),
    );

    expect(detail.designItems).toEqual([
      { ord: 1, title: "首页主视觉", status: "pending", finalizedPath: undefined },
    ]);
  });
});

describe("planAreaOfDesignItems · 计划区投影（#290/#291：已定稿同收口位）", () => {
  it("title 落 description 位；已定稿投影为 closed（定稿呈现归画布）", () => {
    expect(
      planAreaOfDesignItems([
        { ord: 1, title: "首页主视觉", status: "closed" },
        { ord: 2, title: "logo 主标识", status: "finalized", finalizedPath: "/design/logo-2.png" },
        { ord: 3, title: "包装盒平面", status: "failed" },
      ]),
    ).toEqual([
      { ord: 1, description: "首页主视觉", status: "closed" },
      { ord: 2, description: "logo 主标识", status: "closed" },
      { ord: 3, description: "包装盒平面", status: "failed" },
    ]);
  });

  it("null / 空清单 = null（不伪造计划）", () => {
    expect(planAreaOfDesignItems(null)).toBeNull();
    expect(planAreaOfDesignItems(undefined)).toBeNull();
    expect(planAreaOfDesignItems([])).toBeNull();
  });
});
