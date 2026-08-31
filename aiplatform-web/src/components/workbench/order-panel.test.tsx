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

describe("OrderPanel · 项目模式订单面（#28 + #29）", () => {
  it("无未终结订单：引导占位（「确认下单」入口在指令区）", () => {
    const html = renderPanel();

    expect(html).toContain("还没有订单");
    expect(html).toContain("在左侧点「确认下单」");
    expect(html).not.toContain("取消订单");
  });

  it("待报价：状态 + 等待文案 + 下单时间 + 取消按钮（不出价格与去支付）", () => {
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
    expect(html).not.toContain("去支付");
    expect(html).not.toContain("改价历史");
  });

  it("已报价（=待支付）：总价 + 后台备注 + 去支付 + 改价历史折叠入口 + 取消", () => {
    seed.order = {
      id: "o1",
      projectId: "p1",
      status: 2,
      statusName: "已报价",
      amount: 128000,
      currency: "CNY",
      note: "首版报价：含三个页面",
      quotedAt: "2026-09-01T02:00:00Z",
      priceEntries: [
        { id: "e2", amount: 128000, currency: "CNY", note: "首版报价：含三个页面", createdAt: "2026-09-01T02:00:00Z" },
      ],
      createdAt: "2026-09-01T01:00:00Z",
      cancelledAt: null,
    };

    const html = renderPanel("o1");

    expect(html).toContain("已报价");
    expect(html).toContain("¥1,280"); // 总价（分 → 元）
    expect(html).toContain("后台说明：首版报价：含三个页面");
    expect(html).toContain("去支付");
    expect(html).toContain("取消订单");
    expect(html).not.toContain("改价历史"); // 仅一次报价，无改价不展示历史
  });

  it("改价后：折叠改价历史入口按次数展示（首次报价不计改价）", () => {
    seed.order = {
      id: "o1",
      projectId: "p1",
      status: 2,
      statusName: "已报价",
      amount: 99000,
      currency: "CNY",
      note: "调整：去掉导入功能",
      quotedAt: "2026-09-01T02:00:00Z",
      priceEntries: [
        { id: "e2", amount: 99000, currency: "CNY", note: "调整：去掉导入功能", createdAt: "2026-09-01T03:00:00Z" },
        { id: "e1", amount: 128000, currency: "CNY", note: "首版报价：含三个页面", createdAt: "2026-09-01T02:00:00Z" },
      ],
      createdAt: "2026-09-01T01:00:00Z",
      cancelledAt: null,
    };

    const html = renderPanel("o1");

    expect(html).toContain("¥990"); // 现价取最新价目行
    expect(html).toContain("改价历史（1 次）");
    expect(html).toContain("aria-label=\"展开改价历史\"");
  });

  it("非未支付态：不出取消按钮（支付态的主操作归 #30）", () => {
    seed.order = {
      id: "o1",
      projectId: "p1",
      status: 3,
      statusName: "已支付",
      priceEntries: [],
      createdAt: "2026-09-01T01:00:00Z",
      cancelledAt: null,
    };

    const html = renderPanel("o1");

    expect(html).toContain("已支付");
    expect(html).not.toContain("取消订单");
    expect(html).not.toContain("去支付");
  });
});
