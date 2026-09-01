// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { OrderPanel } from "./order-panel";

/**
 * mock 支付确认弹窗（#30 交易环③）：已报价态点「去支付」→ 确认弹窗（价格 +
 * 归档后果说明）→「确认支付」才触支付端点 mutation；「再想想」关弹窗不触。
 * 弹窗开关是客户端状态，SSR 断言不覆盖——本文件是「客户端交互逐文件
 * happy-dom」例外（vitest.config 注）。
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
}));

const payMutate = vi.hoisted(() => vi.fn());
const cancelMutate = vi.hoisted(() => vi.fn());

vi.mock("@/hooks/use-order", () => ({
  useOrder: () => ({ data: seed.order, isPending: false }),
  useCancelOrder: () => ({ isPending: false, mutate: cancelMutate }),
  usePayOrder: () => ({ isPending: false, mutate: payMutate }),
}));

function renderPanel() {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <OrderPanel orderId="o1" />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  payMutate.mockClear();
  cancelMutate.mockClear();
});

afterEach(() => cleanup());

describe("OrderPanel · mock 支付确认弹窗（#30）", () => {
  it("点「去支付」出确认弹窗：价格与归档后果说明，未触支付", () => {
    renderPanel();

    fireEvent.click(screen.getByRole("button", { name: "去支付" }));

    expect(screen.getByText(/确认支付 ¥1,280/)).toBeTruthy();
    expect(screen.getByText(/订单与项目将一并归档/)).toBeTruthy();
    expect(payMutate).not.toHaveBeenCalled();
  });

  it("「确认支付」触支付端点（orderId 为参）并收弹窗", () => {
    renderPanel();
    fireEvent.click(screen.getByRole("button", { name: "去支付" }));

    fireEvent.click(screen.getByRole("button", { name: "确认支付" }));

    expect(payMutate).toHaveBeenCalledTimes(1);
    expect(payMutate.mock.calls[0][0]).toBe("o1");
  });

  it("「再想想」关弹窗不触支付", () => {
    renderPanel();
    fireEvent.click(screen.getByRole("button", { name: "去支付" }));

    fireEvent.click(screen.getByRole("button", { name: "再想想" }));

    expect(payMutate).not.toHaveBeenCalled();
  });

  it("待报价态无「去支付」入口（无价不可付）", () => {
    seed.order = { ...seed.order, status: 1, statusName: "待报价", amount: undefined };
    renderPanel();

    expect(screen.queryByRole("button", { name: "去支付" })).toBeNull();
  });
});
