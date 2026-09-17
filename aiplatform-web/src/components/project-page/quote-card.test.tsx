import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import { QuoteCard } from "./quote-card";

/**
 * 报价卡（#203 视镜语义，ADR-0017）：卡载事件（报价已出）+ 订单引用，金额/备注/
 * 状态渲染时取订单当前态（useOrder mock 供当前态），卡不冻结金额。状态转进如实
 * 呈现——已支付/已取消/已归档不残留可支付假象。SSR 断言（形态轻，交互归
 * quote-card.interaction.test.tsx）。
 */
const seed = vi.hoisted(() => ({
  order: undefined as Record<string, unknown> | undefined,
}));

/** 已报价（待支付）态订单详情基准夹具（useOrder 供当前态）。 */
const quotedOrder: Record<string, unknown> = {
  id: "901",
  projectId: "100",
  status: 2,
  statusName: "已报价",
  amount: 128000,
  currency: "CNY",
  note: "首版报价：含三个页面",
  priceEntries: [],
  createdAt: "2026-09-01T01:00:00Z",
};

vi.mock("@/hooks/use-order", () => ({
  useOrder: () => ({ data: seed.order, isPending: seed.order === undefined }),
  usePayOrder: () => ({ isPending: false, mutate: vi.fn() }),
}));

function renderCard(order: Record<string, unknown> | undefined, event = "quoted") {
  seed.order = order;
  return renderToStaticMarkup(
    <QueryClientProvider client={new QueryClient()}>
      <QuoteCard orderId="901" event={event} onSeeOrder={() => {}} />
    </QueryClientProvider>,
  );
}

describe("QuoteCard · 视镜取数与状态呈现（#203）", () => {
  it("已报价（待支付）：报价已出 + 当前金额 + 后台备注 + 去支付与查看订单详情", () => {
    const html = renderCard(quotedOrder);

    expect(html).toContain("报价已出");
    expect(html).toContain("¥1,280"); // 金额经订单详情取当前态（128000 分）
    expect(html).toContain("首版报价：含三个页面"); // 后台备注
    expect(html).toContain("去支付");
    expect(html).toContain("查看订单详情");
  });

  it("金额取当前态而非落库快照：订单改价后同一张卡呈现新价（视镜非快照）", () => {
    const html = renderCard({ ...quotedOrder, amount: 99000, note: "调整：去掉导入功能" });

    expect(html).toContain("¥990");
    expect(html).toContain("调整：去掉导入功能");
    expect(html).not.toContain("¥1,280");
  });

  it("改价事件变体（#204）：标题「报价已更新」，金额与动作仍取当前态照常", () => {
    const html = renderCard(quotedOrder, "repriced");

    expect(html).toContain("报价已更新");
    expect(html).not.toContain("报价已出");
    expect(html).toContain("¥1,280"); // 视镜取数与首报卡同源
    expect(html).toContain("去支付"); // 待支付态照常可支付
  });

  it("已支付：原地转已支付态，不残留去支付入口", () => {
    const html = renderCard({ ...quotedOrder, status: 3, statusName: "已支付" });

    expect(html).toContain("已支付");
    expect(html).not.toContain("去支付");
    expect(html).toContain("查看订单详情"); // 详情入口终态仍在
  });

  it("已取消：如实转已取消态，不残留可支付假象", () => {
    const html = renderCard({ ...quotedOrder, status: 5, statusName: "已取消" });

    expect(html).toContain("已取消");
    expect(html).not.toContain("去支付");
  });

  it("已归档：如实转已归档态，不残留去支付入口", () => {
    const html = renderCard({ ...quotedOrder, status: 4, statusName: "已归档" });

    expect(html).toContain("已归档");
    expect(html).not.toContain("去支付");
  });

  it("订单详情未就位（加载中）：占位骨架，不出动作按钮", () => {
    const html = renderCard(undefined);

    expect(html).toContain("报价已出"); // 事件标题不依赖详情查询
    expect(html).not.toContain("去支付");
    expect(html).not.toContain("查看订单详情");
  });
});
