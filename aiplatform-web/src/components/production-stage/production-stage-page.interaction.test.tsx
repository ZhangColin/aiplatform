// @vitest-environment happy-dom
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { DeliveryStatus } from "@/lib/orders/production";
import { DELIVERY_STATE } from "@/lib/orders/production";
import type { ProjectDetail } from "@/lib/projects/detail";

import { ProductionStagePage } from "./production-stage-page";

const seed = vi.hoisted(() => ({
  detail: undefined as ProjectDetail | undefined,
  deliveryStatus: undefined as DeliveryStatus | null | undefined,
  projectPending: false,
  deliveryPending: false,
}));

vi.mock("@/hooks/use-project", () => ({
  useProject: () => ({
    data: seed.detail,
    isPending: seed.projectPending,
    isError: false,
    error: null,
    refetch: vi.fn(),
  }),
}));

vi.mock("@/hooks/use-production", () => ({
  useDeliveryStatus: () => ({
    data: seed.deliveryStatus,
    isPending: seed.deliveryPending,
    isError: false,
    error: null,
    refetch: vi.fn(),
  }),
}));

function delivery(overrides: Partial<DeliveryStatus> = {}): DeliveryStatus {
  return {
    orderId: "o1",
    orderStatus: 2,
    orderStatusName: "已报价",
    needsUserSupplement: false,
    handoff: null,
    ...overrides,
  };
}

function renderPage() {
  return render(<ProductionStagePage projectId="p1" />);
}

beforeEach(() => {
  seed.detail = { id: "p1", name: "智能企业客户预约系统" } as ProjectDetail;
  seed.deliveryStatus = delivery();
  seed.projectPending = false;
  seed.deliveryPending = false;
});

afterEach(cleanup);

describe("生产承接页 P07 · 前台六态呈现（#349）", () => {
  it("待签约：显示当前阶段与用户话术，不直出内部日志或工程术语", () => {
    seed.deliveryStatus = delivery({
      state: DELIVERY_STATE.awaitingContract,
      stateName: "待签约",
    });
    const { container } = renderPage();

    expect(screen.getByText("当前阶段")).toBeTruthy();
    expect(screen.getAllByText("待签约").length).toBeGreaterThan(0);
    expect(screen.getByText("已确认 Demo；等待完成范围和合同。")).toBeTruthy();
    expect(container.textContent).not.toMatch(/Agent|GitHub|run[-_ ]/i);
  });

  it.each([
    [DELIVERY_STATE.awaitingPayment, "待付款", "合同已签署；请按付款节点完成开工款支付。"],
    [DELIVERY_STATE.verifyingPayment, "款项核验中", "付款凭证已提交，等待平台核验；确认后安排生产。"],
    [DELIVERY_STATE.awaitingProduction, "待安排生产", "开工条件已满足；排期或负责人待安排。"],
    [DELIVERY_STATE.inProduction, "生产中", "正式生产已启动；显示已确认的进展与下一步。"],
    [DELIVERY_STATE.needsUserSupplement, "需用户补充", "需要您补充资料或确认事项；处理后恢复推进。"],
  ] as const)("六态 %s：%s 的说明与用户动作一致", (state, stateName, copy) => {
    seed.deliveryStatus = delivery({
      state,
      stateName,
      orderStatus: state === DELIVERY_STATE.awaitingProduction || state === DELIVERY_STATE.inProduction ? 3 : 2,
      needsUserSupplement: state === DELIVERY_STATE.needsUserSupplement,
    });
    renderPage();

    expect(screen.getAllByText(stateName).length).toBeGreaterThan(0);
    expect(screen.getByText(copy)).toBeTruthy();
  });

  it("人工承接字段与四个成果入口可达；排期未定如实显示待安排", () => {
    seed.deliveryStatus = delivery({
      state: DELIVERY_STATE.inProduction,
      stateName: "生产中",
      orderStatus: 3,
      handoff: {
        status: 2,
        statusName: "生产中",
        contact: "交付负责人 林然 · 13800000000",
        nextStep: "周四与您确认预约核销流程",
        progressNote: "已完成预约日历联调，正在整理核销流程。",
        estimatedStartAt: null,
      },
    });
    renderPage();

    expect(screen.getByText("最近更新")).toBeTruthy();
    expect(screen.getByText("已完成预约日历联调，正在整理核销流程。")).toBeTruthy();
    expect(screen.getByText("下一步事项")).toBeTruthy();
    expect(screen.getByText("周四与您确认预约核销流程")).toBeTruthy();
    expect(screen.getByText("交付联系人")).toBeTruthy();
    expect(screen.getByText("交付负责人 林然 · 13800000000")).toBeTruthy();
    expect(screen.getByText("预计开始")).toBeTruthy();
    expect(screen.getByText("待安排")).toBeTruthy();

    expect(screen.getByRole("link", { name: /^PRD/ }).getAttribute("href")).toBe("/projects/p1");
    expect(screen.getByRole("link", { name: /^Demo/ }).getAttribute("href")).toBe("/projects/p1/demo");
    expect(screen.getByRole("link", { name: /^合同/ }).getAttribute("href")).toBe("/projects/p1/contract#contract");
    expect(screen.getByRole("link", { name: /^付款记录/ }).getAttribute("href")).toBe("/projects/p1/contract#payment");
  });

  it("需用户补充：列明具体事项并提供对话区处理入口", () => {
    seed.deliveryStatus = delivery({
      state: DELIVERY_STATE.needsUserSupplement,
      stateName: "需用户补充",
      needsUserSupplement: true,
      handoff: {
        contact: "交付负责人 林然",
        nextStep: "请提供门店营业时间，并确认预约取消规则。",
        progressNote: "核销流程已确认，剩余两项信息需要您补充。",
      },
    });
    renderPage();

    const alert = screen.getByRole("alert");
    expect(alert.textContent).toContain("请提供门店营业时间，并确认预约取消规则。");
    expect(screen.getByRole("link", { name: "前往对话区处理" }).getAttribute("href"))
      .toBe("/projects/p1");
  });
});
