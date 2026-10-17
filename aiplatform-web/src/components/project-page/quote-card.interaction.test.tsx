// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";

import { QuoteCard } from "./quote-card";

/**
 * 报价卡交互（#203 视镜；#337 卡内支付闭环退役——付款走订单面付款面板的凭证
 * 核验路径）：「查看订单详情」回调挂载并切到订单 tab；去支付入口负向钉
 * （不再存在）。回调开关是客户端状态——本文件是「客户端交互逐文件
 * happy-dom」例外（vitest.config 注）。
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

vi.mock("@/hooks/use-order", () => ({
  useOrder: () => ({ data: seed.order, isPending: false }),
}));

function renderCard(onSeeOrder = vi.fn()) {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <QuoteCard orderId="901" event="quoted" onSeeOrder={onSeeOrder} />
    </QueryClientProvider>,
  );
  return onSeeOrder;
}

afterEach(() => cleanup());

describe("QuoteCard · 视镜卡交互（#203；#337 支付入口退役）", () => {
  it("「查看订单详情」回调（挂载并切到订单 tab 归装配层 openOutputsTo）", () => {
    const onSeeOrder = renderCard();

    fireEvent.click(screen.getByRole("button", { name: "查看订单详情" }));

    expect(onSeeOrder).toHaveBeenCalledTimes(1);
  });

  it("卡内支付入口已退役：无「去支付」按钮（付款走订单面付款面板）", () => {
    renderCard();

    expect(screen.queryByRole("button", { name: "去支付" })).toBeNull();
  });
});
