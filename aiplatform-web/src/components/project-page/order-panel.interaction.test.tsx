// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { OrderPanel } from "./order-panel";

/**
 * 付款面板交互（#337 付款计划与凭证核验·凭证核验路径第一跳）：待支付/待核验
 * 节点出「上传转账凭证」入口，选文件（预检过）触发上传 mutation（nodeId＋
 * file 为参）；格式不符预检拦截不出网；已到账节点无上传入口；不足回待支付
 * 节点呈现「还需支付」推导。mock 支付确认弹窗已退役——去支付入口不再存在
 * （负向钉）。上传开关是客户端状态，SSR 断言不覆盖——本文件是「客户端交互
 * 逐文件 happy-dom」例外（vitest.config 注）。
 */
const seed = vi.hoisted(() => ({
  order: {
    id: "o1",
    projectId: "p1",
    status: 2,
    statusName: "已报价",
    amount: 128000,
    currency: "CNY",
    note: "首版报价",
    quotedAt: "2026-09-01T02:00:00Z",
    priceEntries: [
      { id: "e1", amount: 128000, currency: "CNY", note: "首版报价", createdAt: "2026-09-01T02:00:00Z" },
    ],
    createdAt: "2026-09-01T01:00:00Z",
    cancelledAt: null,
  } as Record<string, unknown>,
  paymentPlan: {
    id: "pp1",
    orderId: "o1",
    contractId: "c1",
    contractNo: "SG-3ukqz0c0",
    totalAmount: 128000,
    totalSettled: 10000,
    nodes: [
      {
        id: "n1",
        seq: 1,
        name: "首期开工款",
        amount: 100000,
        percentage: 78,
        status: 1,
        statusName: "待支付",
        remarkCode: "SG-3ukqz0c0-K1",
        kickoffGate: true,
        channelType: 1,
        channelTypeName: "线下转账",
        channelRefNo: null,
        settledAmount: 10000,
        remainingAmount: 90000,
        receivedAt: null,
        vouchers: [
          { id: "v1", nodeId: "n1", fileName: "receipt.pdf", contentType: "application/pdf", byteSize: 1024, createdAt: "2026-10-17T10:00:00Z" },
        ],
      },
      {
        id: "n2",
        seq: 2,
        name: "尾款结算",
        amount: 28000,
        percentage: 22,
        status: 5,
        statusName: "已到账",
        remarkCode: "SG-3ukqz0c0-K2",
        kickoffGate: false,
        channelType: 1,
        channelTypeName: "线下转账",
        channelRefNo: "BANK-1",
        settledAmount: 28000,
        remainingAmount: 0,
        receivedAt: "2026-10-16T09:00:00Z",
        vouchers: [],
      },
    ],
  } as Record<string, unknown> | null,
}));

const uploadMutate = vi.hoisted(() => vi.fn());
const cancelMutate = vi.hoisted(() => vi.fn());

vi.mock("@/hooks/use-order", () => ({
  useOrder: () => ({ data: seed.order, isPending: false }),
  useCancelOrder: () => ({ isPending: false, mutate: cancelMutate }),
}));

// 合同域（#336）：无合同＝null（付款交互面与合同无关，mock 收口不出网）
vi.mock("@/hooks/use-contract", () => ({
  useContract: () => ({ data: null }),
  useConfirmScope: () => ({ isPending: false, mutate: vi.fn() }),
}));

vi.mock("@/hooks/use-payment", () => ({
  usePaymentPlan: () => ({ data: seed.paymentPlan }),
  useUploadVoucher: () => ({ isPending: false, mutate: uploadMutate }),
}));

function renderPanel() {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <OrderPanel orderId="o1" />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  uploadMutate.mockClear();
  cancelMutate.mockClear();
});

afterEach(() => cleanup());

describe("OrderPanel · 付款面板（#337 凭证核验路径）", () => {
  it("待支付节点：上传转账凭证入口在场，已传凭证与「还需支付」推导可见", () => {
    renderPanel();

    // 上传入口（待支付节点可再传）
    expect(screen.getByRole("button", { name: /再传一张凭证/ })).toBeTruthy();
    // 不足回待支付后的推导面：已核到账 ¥100（对应节点即本节点）
    expect(screen.getByText(/已核到账 ¥100/)).toBeTruthy();
    // 已传凭证（追加保留的本人清单）
    expect(screen.getByText("receipt.pdf")).toBeTruthy();
    // 附言码在场（转账回执关联位）
    expect(screen.getByText("SG-3ukqz0c0-K1")).toBeTruthy();
    // 面板级「还需支付」合计（900 元＝首节点差额，尾款已到账不列；节点行
    // 与面板横幅各一处）
    expect(screen.getAllByText(/还需支付 ¥900/).length).toBe(2);
  });

  it("已到账节点：无上传入口（终态不收凭证）", () => {
    renderPanel();

    expect(screen.queryByRole("button", { name: "上传转账凭证" })).toBeNull();
    // 尾款节点呈现已到账（不派出入口）
    expect(screen.getAllByText("已到账").length).toBeGreaterThan(0);
  });

  it("选合法文件触发上传 mutation（nodeId＋file 为参）", () => {
    renderPanel();

    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    expect(input).not.toBeNull();
    const file = new File(["%PDF-1.4"], "receipt-2.pdf", { type: "application/pdf" });
    fireEvent.change(input, { target: { files: [file] } });

    expect(uploadMutate).toHaveBeenCalledTimes(1);
    expect(uploadMutate.mock.calls[0][0].nodeId).toBe("n1");
    expect(uploadMutate.mock.calls[0][0].file.name).toBe("receipt-2.pdf");
  });

  it("格式不符预检拦截：不出网、toast 提示", () => {
    renderPanel();

    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    const bad = new File(["x"], "note.txt", { type: "text/plain" });
    fireEvent.change(input, { target: { files: [bad] } });

    expect(uploadMutate).not.toHaveBeenCalled();
  });

  it("mock 支付入口已退役：无「去支付」按钮（支付唯一面＝付款面板）", () => {
    renderPanel();

    expect(screen.queryByRole("button", { name: "去支付" })).toBeNull();
  });

  it("无付款计划：面板缺席（不渲染占位）", () => {
    const original = seed.paymentPlan;
    seed.paymentPlan = null;
    try {
      renderPanel();

      expect(screen.queryByText("付款计划")).toBeNull();
    } finally {
      seed.paymentPlan = original;
    }
  });
});
