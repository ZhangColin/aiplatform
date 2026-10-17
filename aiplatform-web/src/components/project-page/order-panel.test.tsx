import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import { OrderPanel } from "./order-panel";

// 订单面板（#28 交易环① + #29 交易环②）：无未终结订单 = 引导占位；待报价 =
// 等待文案 + 取消；已报价 = 价格面 + 取消 + 改价历史（付款走付款面板·凭证
// 核验路径，#337 起去支付入口退役）；已归档终态 = 支付完成说明 + 完整记录 +
// 源码包下载。订单详情查询 mock 掉（契约面归 hooks 与后端测试）。
const seed = vi.hoisted(() => ({
  order: undefined as Record<string, unknown> | undefined,
  contract: null as Record<string, unknown> | null,
  paymentPlan: undefined as Record<string, unknown> | undefined,
  deliveryStatus: undefined as Record<string, unknown> | undefined,
}));

vi.mock("@/hooks/use-order", () => ({
  useOrder: () => ({
    data: seed.order,
    isPending: seed.order === undefined,
  }),
  useCancelOrder: () => ({ isPending: false, mutate: vi.fn() }),
}));

// 合同域（#336）：无合同＝null（hook 已把 404 归一），契约面归 hooks 测试
vi.mock("@/hooks/use-contract", () => ({
  useContract: () => ({ data: seed.contract }),
  useConfirmScope: () => ({ isPending: false, mutate: vi.fn() }),
}));

// 付款域（#337）：无计划＝null（hook 已把 404 归一），面板契约归 interaction 测试
vi.mock("@/hooks/use-payment", () => ({
  usePaymentPlan: () => ({ data: seed.paymentPlan ?? null }),
}));

// 交付状态域（#338）：无未终结订单＝null（hook 已把 404 归一），卡片契约归
// production-card 测试
vi.mock("@/hooks/use-production", () => ({
  useDeliveryStatus: () => ({ data: seed.deliveryStatus ?? null }),
}));

function renderPanel(orderId?: string, projectArchived = false) {
  return renderToStaticMarkup(
    <QueryClientProvider client={new QueryClient()}>
      <OrderPanel orderId={orderId ?? null} projectArchived={projectArchived} />
    </QueryClientProvider>,
  );
}

describe("OrderPanel · 项目模式订单面（#28 + #29 + #30）", () => {
  it("无未终结订单：引导占位（「确认下单」入口在顶栏右上角）", () => {
    const html = renderPanel();

    expect(html).toContain("还没有订单");
    expect(html).toContain("点右上角「确认下单」");
    expect(html).not.toContain("取消订单");
  });

  it("归档项目无订单记录：终态占位不再引导下单（#30）", () => {
    const html = renderPanel(undefined, true);

    expect(html).toContain("项目已归档");
    expect(html).toContain("没有订单记录；如需继续，请新建项目");
    expect(html).not.toContain("确认下单");
  });

  it("待报价：状态 + 「待确认报价」占位态 + 下单时间 + 取消按钮（不出价格与去支付）", () => {
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
    // 「待确认报价」占位呈现态（#335）：价格位以待定态名占位——不以 0 元或示例价代替
    expect(html).toContain("待确认报价");
    expect(html).toContain("后台正在评估范围与报价");
    expect(html).not.toContain("¥"); // 无金额呈现
    expect(html).toContain("取消订单");
    expect(html).toContain("取消后回到迭代，可继续修改再重新下单");
    expect(html).not.toContain("去支付");
    expect(html).not.toContain("改价历史");
  });

  it("待报价带双版本引用：下单基准行呈现（#335 随单冻结）", () => {
    seed.order = {
      id: "o1",
      projectId: "p1",
      status: 1,
      statusName: "待报价",
      prdVersionNo: 3,
      demoVersionNo: 2,
      createdAt: "2026-09-01T01:00:00Z",
      cancelledAt: null,
    };

    const html = renderPanel("o1");

    expect(html).toContain("基准 PRD v3 · Demo v2");
  });

  it("遗留单无版本引用：下单基准行不虚构（确认制前订单）", () => {
    seed.order = {
      id: "o1",
      projectId: "p1",
      status: 1,
      statusName: "待报价",
      createdAt: "2026-09-01T01:00:00Z",
      cancelledAt: null,
    };

    const html = renderPanel("o1");

    expect(html).not.toContain("基准 PRD");
    expect(html).not.toContain("Demo v");
  });

  it("已报价＋范围录入＋无合同：范围三段＋「确认范围并准备合同」（#336 确认面）", () => {
    seed.order = {
      id: "o1",
      projectId: "p1",
      status: 2,
      statusName: "已报价",
      amount: 128000,
      currency: "CNY",
      note: "首版报价",
      scopeDeliverables: "交付清单：管理后台＋小程序",
      scopeAcceptance: "验收方式：按 PRD v3 验收",
      scopeTimeline: "工期口径：4 周",
      priceEntries: [],
      createdAt: "2026-09-01T01:00:00Z",
      cancelledAt: null,
    };
    seed.contract = null;

    const html = renderPanel("o1");

    expect(html).toContain("范围与报价");
    expect(html).toContain("交付清单：管理后台＋小程序");
    expect(html).toContain("验收方式：按 PRD v3 验收");
    expect(html).toContain("工期口径：4 周");
    expect(html).toContain("确认范围并准备合同");
    expect(html).not.toContain("SG-"); // 无合同不渲染预览卡（编号缺席＝卡缺席）
  });

  it("已报价无范围文本：确认面不出（等后台录入范围）", () => {
    seed.order = {
      id: "o1",
      projectId: "p1",
      status: 2,
      statusName: "已报价",
      amount: 128000,
      currency: "CNY",
      priceEntries: [],
      createdAt: "2026-09-01T01:00:00Z",
      cancelledAt: null,
    };
    seed.contract = null;

    const html = renderPanel("o1");

    expect(html).not.toContain("确认范围并准备合同");
  });

  it("合同在场：预览卡挂载＋确认面退场（范围以合同快照为准）", () => {
    seed.order = {
      id: "o1",
      projectId: "p1",
      status: 2,
      statusName: "已报价",
      amount: 128000,
      currency: "CNY",
      scopeDeliverables: "交付清单：管理后台＋小程序",
      scopeAcceptance: "验收方式：按 PRD v3 验收",
      scopeTimeline: "工期口径：4 周",
      priceEntries: [],
      createdAt: "2026-09-01T01:00:00Z",
      cancelledAt: null,
    };
    seed.contract = {
      id: "c1",
      contractNo: "SG-3ukqz0c0",
      status: 1,
      statusName: "准备中",
      version: 1,
      needsUpdate: false,
      scopeDeliverables: "交付清单：管理后台＋小程序",
      scopeAcceptance: "验收方式：按 PRD v3 验收",
      scopeTimeline: "工期口径：4 周",
      amount: 128000,
      currency: "CNY",
      partyASigned: false,
      partyBSigned: false,
    };

    const html = renderPanel("o1");

    expect(html).toContain("SG-3ukqz0c0");
    expect(html).toContain("准备中");
    expect(html).not.toContain("确认范围并准备合同"); // 合同已建，确认动作退场
  });

  it("已报价（=待支付）：总价 + 后台备注 + 取消（去支付入口已随 mock 支付闭环退役）", () => {
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
    expect(html).not.toContain("去支付"); // mock 支付入口退役——付款走付款面板
    expect(html).not.toContain("在线支付即将开通");
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
    expect(html).toContain("aria-label=\"展开价格历史\"");
  });

  it("非未支付态：不出取消按钮（支付态的主操作归付款面板）", () => {
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

  // ---------- #30 交易环③：归档终态完整记录 ----------

  it("已归档终态：支付完成说明 + 全时间点组 + 源码包下载 + 完整报价记录，无任何动作按钮", () => {
    seed.order = {
      id: "o1",
      projectId: "p1",
      status: 4,
      statusName: "已归档",
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
      paidAt: "2026-09-01T04:00:00Z",
      archivedAt: "2026-09-01T04:00:00Z",
    };

    const html = renderPanel("o1");

    expect(html).toContain("已归档");
    expect(html).toContain("支付完成，订单与项目已归档");
    expect(html).toContain("支付于");
    expect(html).toContain("归档于");
    expect(html).toContain("¥990"); // 成交价（现值取最新价目行）
    // 源码包下载：项目源码包端点直下（同源 cookie 自动携带）
    expect(html).toContain('href="/api/projects/p1/source-package"');
    expect(html).toContain("下载源码包");
    // 完整报价记录：终态展示含首次报价的全部条目（区别于未支付态的「改价历史」）
    expect(html).toContain("报价记录（2 条）");
    // 终态无任何动作按钮
    expect(html).not.toContain("取消订单");
    expect(html).not.toContain("去支付");
  });

  it("已归档终态仅一次报价：报价记录仍展示（终态含首次报价，不算改价口径）", () => {
    seed.order = {
      id: "o1",
      projectId: "p1",
      status: 4,
      statusName: "已归档",
      amount: 128000,
      currency: "CNY",
      note: "首版报价",
      quotedAt: "2026-09-01T02:00:00Z",
      priceEntries: [
        { id: "e1", amount: 128000, currency: "CNY", note: "首版报价", createdAt: "2026-09-01T02:00:00Z" },
      ],
      createdAt: "2026-09-01T01:00:00Z",
      cancelledAt: null,
      paidAt: "2026-09-01T04:00:00Z",
      archivedAt: "2026-09-01T04:00:00Z",
    };

    const html = renderPanel("o1");

    expect(html).toContain("报价记录（1 条）");
    expect(html).not.toContain("改价历史");
  });

  // ---------- #297：归档终态下载口按交付物类型分岔 ----------

  it("已归档设计单：下载口＝设计资产包（订单锚冻结件，非源码包端点）", () => {
    seed.order = {
      id: "o9",
      projectId: "p1",
      status: 4,
      statusName: "已归档",
      deliverableType: 1,
      deliverableTypeName: "设计",
      amount: 88000,
      currency: "CNY",
      note: "设计单首报",
      quotedAt: "2026-10-01T02:00:00Z",
      priceEntries: [
        { id: "e1", amount: 88000, currency: "CNY", note: "设计单首报", createdAt: "2026-10-01T02:00:00Z" },
      ],
      createdAt: "2026-10-01T01:00:00Z",
      cancelledAt: null,
      paidAt: "2026-10-01T04:00:00Z",
      archivedAt: "2026-10-01T04:00:00Z",
    };

    const html = renderPanel("o9");

    expect(html).toContain('href="/api/orders/o9/design-package"');
    expect(html).toContain("下载设计资产包");
    expect(html).toContain("选定稿＋设计规范＋衍生");
    expect(html).not.toContain("下载源码包");
    expect(html).not.toContain('href="/api/projects/p1/source-package"');
  });

  it("已归档系统＋设计单：下载口＝交付物包（统一部件容器，同一订单锚端点）", () => {
    seed.order = {
      id: "o9",
      projectId: "p1",
      status: 4,
      statusName: "已归档",
      deliverableType: 3,
      deliverableTypeName: "系统＋设计",
      amount: 128000,
      currency: "CNY",
      note: "系统＋设计首报",
      quotedAt: "2026-10-01T02:00:00Z",
      priceEntries: [
        { id: "e1", amount: 128000, currency: "CNY", note: "系统＋设计首报", createdAt: "2026-10-01T02:00:00Z" },
      ],
      createdAt: "2026-10-01T01:00:00Z",
      cancelledAt: null,
      paidAt: "2026-10-01T04:00:00Z",
      archivedAt: "2026-10-01T04:00:00Z",
    };

    const html = renderPanel("o9");

    expect(html).toContain('href="/api/orders/o9/design-package"');
    expect(html).toContain("下载交付物包");
    expect(html).toContain("系统源码＋选定设计稿同包");
    expect(html).not.toContain("下载源码包");
  });

  it("已归档系统单（含缺省交付物类型＝存量单口径）：下载口照旧源码包", () => {
    seed.order = {
      id: "o1",
      projectId: "p1",
      status: 4,
      statusName: "已归档",
      amount: 128000,
      currency: "CNY",
      note: "首版报价",
      quotedAt: "2026-09-01T02:00:00Z",
      priceEntries: [
        { id: "e1", amount: 128000, currency: "CNY", note: "首版报价", createdAt: "2026-09-01T02:00:00Z" },
      ],
      createdAt: "2026-09-01T01:00:00Z",
      cancelledAt: null,
      paidAt: "2026-09-01T04:00:00Z",
      archivedAt: "2026-09-01T04:00:00Z",
    };

    const html = renderPanel("o1");

    expect(html).toContain('href="/api/projects/p1/source-package"');
    expect(html).toContain("下载源码包");
    expect(html).not.toContain("design-package");
  });

  it("生产状态卡（#338）：六态在场即挂载（付款面板之后）、缺席不渲染", () => {
    seed.order = {
      id: "o1",
      projectId: "p1",
      status: 3,
      statusName: "已支付",
      amount: 128000,
      currency: "CNY",
      priceEntries: [],
      createdAt: "2026-09-01T01:00:00Z",
      cancelledAt: null,
      paidAt: "2026-09-01T04:00:00Z",
    };
    seed.deliveryStatus = {
      orderId: "o1",
      orderStatus: 3,
      state: 4,
      stateName: "待安排生产",
      needsUserSupplement: false,
      handoff: null,
    };

    const html = renderPanel("o1");

    expect(html).toContain("生产承接");
    expect(html).toContain("待安排生产");

    // 缺席态（无未终结订单/待报价 state null）：卡不渲染
    seed.deliveryStatus = undefined;
    const absent = renderPanel("o1");
    expect(absent).not.toContain("生产承接");
  });
});
