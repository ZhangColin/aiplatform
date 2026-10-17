import { describe, expect, it } from "vitest";

import type { ProjectDetail } from "@/lib/projects/detail";

import {
  DELIVERY_STAGES,
  currentStageOf,
  deliveryStageNav,
  stageGateFactsOf,
} from "@/lib/projects/delivery-stages";

/**
 * 交付生命周期阶段可达性判定（#343，spec #320 ⑥ 全局壳）：灰置条件＝
 * 「PRD 已确认 / Demo 已确认 / 订单已支付」三门的纯函数推导——侧栏与顶栏
 * 五行导航共用的单一判定点（#318：未满足条件的步骤要说明原因）。
 * 期望值来自 spec #320 ⑥ 门序：Demo 前置＝PRD 确认、签约前置＝Demo 确认、
 * 生产承接前置＝付款完成；阶段名逐字照原型（需求分析 (PRD)/Demo 体验/
 * 签约付款/生产承接）。
 */

const ALL_OPEN = { prdConfirmed: true, demoConfirmed: true, orderPaid: true };

describe("DELIVERY_STAGES（四阶段词表，原型逐字）", () => {
  it("序号 1–4 与阶段名照原型", () => {
    expect(DELIVERY_STAGES.map((s) => [s.seq, s.label])).toEqual([
      [1, "需求分析 (PRD)"],
      [2, "Demo 体验"],
      [3, "签约付款"],
      [4, "生产承接"],
    ]);
  });
});

describe("deliveryStageNav（可达性判定＋灰置原因）", () => {
  it("需求分析是入口：任何事实下不灰置、无完成 ✓", () => {
    const nav = deliveryStageNav("p1", { prdConfirmed: false, demoConfirmed: false, orderPaid: false });
    const prd = nav[0];
    expect(prd.key).toBe("prd");
    expect(prd.locked).toBe(false);
    expect(prd.lockReason).toBeUndefined();
    expect(prd.completed).toBe(false);
    expect(prd.href).toBe("/projects/p1");
  });

  it("三门全开：各阶段可达、href 落项目空间路径段", () => {
    const nav = deliveryStageNav("p1", ALL_OPEN);
    expect(nav.map((s) => s.href)).toEqual([
      "/projects/p1",
      "/projects/p1/demo",
      "/projects/p1/contract",
      "/projects/p1/production",
    ]);
    expect(nav.every((s) => !s.locked)).toBe(true);
  });

  it("完成 ✓：PRD 确认✓需求分析、Demo 确认✓Demo 体验、已支付✓签约付款；生产承接是终阶段无 ✓", () => {
    const nav = deliveryStageNav("p1", ALL_OPEN);
    expect(nav.map((s) => s.completed)).toEqual([true, true, true, false]);
  });

  it("未确认 PRD：Demo 体验灰置并说明原因", () => {
    const nav = deliveryStageNav("p1", { ...ALL_OPEN, prdConfirmed: false });
    expect(nav[1].locked).toBe(true);
    expect(nav[1].lockReason).toContain("PRD");
  });

  it("未确认 Demo：签约付款灰置并说明原因（PRD 已确认也不开门）", () => {
    const nav = deliveryStageNav("p1", { ...ALL_OPEN, demoConfirmed: false });
    expect(nav[2].locked).toBe(true);
    expect(nav[2].lockReason).toContain("Demo");
  });

  it("未付款：生产承接灰置并说明原因", () => {
    const nav = deliveryStageNav("p1", { ...ALL_OPEN, orderPaid: false });
    expect(nav[3].locked).toBe(true);
    expect(nav[3].lockReason).toContain("付款");
  });

  it("灰置时前置阶段自身不受牵连（门只挡门后）", () => {
    const nav = deliveryStageNav("p1", { prdConfirmed: false, demoConfirmed: false, orderPaid: false });
    expect(nav[0].locked).toBe(false);
    expect(nav.slice(1).every((s) => s.locked)).toBe(true);
  });
});

describe("currentStageOf（当前阶段＝首个未完成）", () => {
  it("推进序：需求分析 → Demo 体验 → 签约付款 → 生产承接", () => {
    expect(currentStageOf({ prdConfirmed: false, demoConfirmed: false, orderPaid: false })).toBe("prd");
    expect(currentStageOf({ prdConfirmed: true, demoConfirmed: false, orderPaid: false })).toBe("demo");
    expect(currentStageOf({ prdConfirmed: true, demoConfirmed: true, orderPaid: false })).toBe("contract");
    expect(currentStageOf(ALL_OPEN)).toBe("production");
  });
});

describe("stageGateFactsOf（REST 项目事实 → 门事实投影）", () => {
  it("PRD 确认接 #330 版本列表：有已确认版本＝门开；空列表/缺省＝未确认", () => {
    const detail = { prdProducedAt: "2026-10-01T00:00:00Z" } as ProjectDetail;
    expect(stageGateFactsOf(detail).prdConfirmed).toBe(false);
    expect(stageGateFactsOf(detail, []).prdConfirmed).toBe(false);
    expect(stageGateFactsOf(detail, [{ versionNo: 1 }]).prdConfirmed).toBe(true);
    expect(
      stageGateFactsOf(detail, [{ versionNo: 2 }, { versionNo: 1 }]).prdConfirmed,
    ).toBe(true);
  });

  it("Demo 确认待 #333 版本化：未落地前诚实映射 false", () => {
    const facts = stageGateFactsOf(
      { prdProducedAt: "2026-10-01T00:00:00Z" } as ProjectDetail,
      [{ versionNo: 1 }],
    );
    expect(facts.demoConfirmed).toBe(false);
  });

  it("已支付订单在场＝付款门开", () => {
    const facts = stageGateFactsOf({
      activeOrder: { id: "o1", status: 3 },
    } as ProjectDetail);
    expect(facts.orderPaid).toBe(true);
  });

  it("待报价/已报价未支付＝付款门仍关；已归档项目（含最近单已归档）＝门开", () => {
    expect(
      stageGateFactsOf({ activeOrder: { id: "o1", status: 1 } } as ProjectDetail).orderPaid,
    ).toBe(false);
    expect(
      stageGateFactsOf({ activeOrder: { id: "o1", status: 2 } } as ProjectDetail).orderPaid,
    ).toBe(false);
    expect(stageGateFactsOf({ archived: true } as ProjectDetail).orderPaid).toBe(true);
    expect(
      stageGateFactsOf({
        latestOrder: { id: "o9", status: 4 },
      } as ProjectDetail).orderPaid,
    ).toBe(true);
  });

  it("无项目事实（详情未到）：三门全关", () => {
    expect(stageGateFactsOf(undefined)).toEqual({
      prdConfirmed: false,
      demoConfirmed: false,
      orderPaid: false,
    });
  });
});
