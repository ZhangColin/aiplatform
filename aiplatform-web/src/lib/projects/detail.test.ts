import { describe, expect, it } from "vitest";

import { normalizeProjectDetail, type ProjectDetailResponse } from "./detail";

function raw(overrides: Partial<ProjectDetailResponse> = {}): ProjectDetailResponse {
  return { id: "p1", name: "花店小程序", ...overrides };
}

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
