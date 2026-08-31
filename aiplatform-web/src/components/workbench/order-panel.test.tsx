import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import { OrderPanel } from "./order-panel";

// 订单面板（#28 交易环①）：无未终结订单 = 引导占位；待报价 = 当前态卡
// （状态 + 等待文案 + 下单时间 + 取消按钮——未支付态随时取消回迭代）。
// 订单详情查询 mock 掉（契约面归 hooks 与后端测试）。
const seed = vi.hoisted(() => ({
  order: undefined as Record<string, unknown> | undefined,
}));

vi.mock("@/hooks/use-order", () => ({
  useOrder: () => ({
    data: seed.order,
    isPending: seed.order === undefined,
  }),
  useCancelOrder: () => ({ isPending: false, mutate: vi.fn() }),
}));

function renderPanel(orderId?: string) {
  return renderToStaticMarkup(
    <QueryClientProvider client={new QueryClient()}>
      <OrderPanel orderId={orderId ?? null} />
    </QueryClientProvider>,
  );
}

describe("OrderPanel · 项目模式订单面（#28）", () => {
  it("无未终结订单：引导占位（「确认下单」入口在指令区）", () => {
    const html = renderPanel();

    expect(html).toContain("还没有订单");
    expect(html).toContain("在左侧点「确认下单」");
    expect(html).not.toContain("取消订单");
  });

  it("待报价：状态 + 等待文案 + 下单时间 + 取消按钮", () => {
    seed.order = {
      id: "o1",
      projectId: "p1",
      status: 1,
      statusName: "待报价",
      createdAt: "2026-09-01T01:00:00Z",
      cancelledAt: null,
    };

    const html = renderPanel("o1");

    expect(html).toContain("待报价");
    expect(html).toContain("已收到您的订单，后台正在评估报价");
    expect(html).toContain("取消订单");
    expect(html).toContain("取消后回到迭代，可继续修改再重新下单");
  });

  it("非未支付态：不出取消按钮（改价/支付态的主操作归 #29/#30）", () => {
    seed.order = {
      id: "o1",
      projectId: "p1",
      status: 3,
      statusName: "已支付",
      createdAt: "2026-09-01T01:00:00Z",
      cancelledAt: null,
    };

    const html = renderPanel("o1");

    expect(html).toContain("已支付");
    expect(html).not.toContain("取消订单");
  });
});
