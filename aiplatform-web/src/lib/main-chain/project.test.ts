import { describe, expect, it } from "vitest";

import { normalizeProjectDetail, type ProjectDetailResponse } from "./project";

describe("normalizeProjectDetail · 详情投影归一", () => {
  it("核心字段透传；stages / gate 缺省归一为空序列 / null", () => {
    const raw: ProjectDetailResponse = {
      id: "p1",
      name: "宠物医院预约官网",
      stage: "DEMO",
      stageLabel: "原型",
      status: 1,
      statusName: "开发中",
    };
    expect(normalizeProjectDetail(raw)).toEqual({
      id: "p1",
      name: "宠物医院预约官网",
      stage: "DEMO",
      stageLabel: "原型",
      workspaceId: undefined,
      status: 1,
      statusName: "开发中",
      stageTaskCount: undefined,
      archived: undefined,
      createdAt: undefined,
      stages: [],
      gate: null,
    });
  });

  it("gate 有值时原样保留（ready/actor 供门卡消费）", () => {
    const detail = normalizeProjectDetail({
      id: "p1",
      gate: { actor: "USER", ready: false },
      stages: [{ name: "BA", label: "需求", gateActor: "USER" }],
    });
    expect(detail.gate).toEqual({ actor: "USER", ready: false });
    expect(detail.stages).toHaveLength(1);
  });

  it("workspaceId 透传（终端 exec 寻址面，issue #42）", () => {
    expect(normalizeProjectDetail({ id: "p1", workspaceId: "wsp_1" })).toMatchObject({
      workspaceId: "wsp_1",
    });
    expect(normalizeProjectDetail({ id: "p1" }).workspaceId).toBeUndefined();
  });

  it("全空响应也不抛（极端防御）", () => {
    const detail = normalizeProjectDetail({});
    expect(detail).toMatchObject({ id: "", name: "", stage: "", stages: [], gate: null });
  });
});
