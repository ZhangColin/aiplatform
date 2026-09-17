// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { queryKeys } from "@/lib/api/keys";

import { QuoteCard } from "./quote-card";

/**
 * 报价卡卡内支付闭环（#203）：「去支付」→ 确认弹窗（价格 + 归档后果说明）→
 * 「确认支付」触支付 mutation（orderId 为参），成功失效对话史域（projects/orders
 * 两域失效归 usePayOrder 既有口径）；「查看订单详情」回调挂载并切到订单 tab。
 * 弹窗开关是客户端状态——本文件是「客户端交互逐文件 happy-dom」例外
 * （vitest.config 注）。
 */
const seed = vi.hoisted(() => ({
  order: {
    id: "901",
    projectId: "100",
    status: 2,
    statusName: "已报价",
    amount: 128000,
    currency: "CNY",
    note: "首版报价",
    priceEntries: [],
    createdAt: "2026-09-01T01:00:00Z",
  } as Record<string, unknown>,
}));

const payMutate = vi.hoisted(() => vi.fn());

vi.mock("@/hooks/use-order", () => ({
  useOrder: () => ({ data: seed.order, isPending: false }),
  usePayOrder: () => ({ isPending: false, mutate: payMutate }),
}));

function renderCard(client: QueryClient, onSeeOrder = vi.fn()) {
  render(
    <QueryClientProvider client={client}>
      <QuoteCard orderId="901" event="quoted" onSeeOrder={onSeeOrder} />
    </QueryClientProvider>,
  );
  return onSeeOrder;
}

beforeEach(() => {
  payMutate.mockClear();
});

afterEach(() => cleanup());

describe("QuoteCard · 卡内支付闭环（#203）", () => {
  it("点「去支付」出确认弹窗：价格与归档后果说明，未触支付", () => {
    renderCard(new QueryClient());

    fireEvent.click(screen.getByRole("button", { name: "去支付" }));

    expect(screen.getByText(/确认支付 ¥1,280/)).toBeTruthy();
    expect(screen.getByText(/订单与项目将一并归档/)).toBeTruthy();
    expect(payMutate).not.toHaveBeenCalled();
  });

  it("「确认支付」触支付端点（orderId 为参）；成功失效对话史域（卡原地转态的重查源）", () => {
    const client = new QueryClient();
    const invalidateSpy = vi.spyOn(client, "invalidateQueries");
    renderCard(client);
    fireEvent.click(screen.getByRole("button", { name: "去支付" }));

    fireEvent.click(screen.getByRole("button", { name: "确认支付" }));

    expect(payMutate).toHaveBeenCalledTimes(1);
    expect(payMutate.mock.calls[0][0]).toBe("901");
    // 模拟支付成功（hook 的 projects/orders 失效已被 mock 掉，本卡只补对话史域）
    const options = payMutate.mock.calls[0][1] as { onSuccess?: () => void };
    options.onSuccess?.();
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: queryKeys.conversation.all });
  });

  it("「再想想」关弹窗不触支付", () => {
    renderCard(new QueryClient());
    fireEvent.click(screen.getByRole("button", { name: "去支付" }));

    fireEvent.click(screen.getByRole("button", { name: "再想想" }));

    expect(payMutate).not.toHaveBeenCalled();
  });

  it("「查看订单详情」回调（挂载并切到订单 tab 归装配层 openOutputsTo）", () => {
    const onSeeOrder = renderCard(new QueryClient());

    fireEvent.click(screen.getByRole("button", { name: "查看订单详情" }));

    expect(onSeeOrder).toHaveBeenCalledTimes(1);
  });
});
