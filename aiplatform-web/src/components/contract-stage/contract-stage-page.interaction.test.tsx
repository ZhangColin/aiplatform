// @vitest-environment happy-dom
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { ContractDetail } from "@/lib/orders/contract";
import type { OrderDetail } from "@/lib/orders/detail";
import type { PaymentPlan } from "@/lib/orders/payment";
import type { DeliveryStatus } from "@/lib/orders/production";
import type { ProjectDetail } from "@/lib/projects/detail";
import { CONTRACT_STATUS } from "@/lib/orders/contract";
import { ORDER_STATUS } from "@/lib/orders/lock";
import { PAYMENT_NODE_STATUS } from "@/lib/orders/payment";
import { useM01Store } from "@/lib/store/m01";
import { useVersionRecordStore } from "@/lib/store/version-record";

import { ContractStagePage } from "./contract-stage-page";

const seed = vi.hoisted(() => ({
  detail: undefined as ProjectDetail | undefined,
  order: undefined as OrderDetail | null | undefined,
  contract: undefined as ContractDetail | null | undefined,
  paymentPlan: undefined as PaymentPlan | null | undefined,
  deliveryStatus: undefined as DeliveryStatus | null | undefined,
  orderQueryId: undefined as string | null | undefined,
  me: undefined as { accountId: string; displayName: string } | undefined,
  mePending: false,
  confirmMutate: vi.fn(),
  cancelMutate: vi.fn(),
  uploadMutate: vi.fn(),
}));

vi.mock("@/hooks/use-project", () => ({
  useProject: () => ({
    data: seed.detail,
    isPending: false,
    isError: false,
    error: null,
    refetch: vi.fn(),
  }),
}));

vi.mock("@/hooks/use-order", () => ({
  useOrder: (orderId: string | null | undefined) => {
    seed.orderQueryId = orderId;
    return { data: seed.order };
  },
  useCancelOrder: () => ({ mutate: seed.cancelMutate, isPending: false }),
}));

vi.mock("@/hooks/use-contract", () => ({
  useContract: () => ({ data: seed.contract }),
  useConfirmScope: () => ({ mutate: seed.confirmMutate, isPending: false }),
}));

vi.mock("@/hooks/use-payment", () => ({
  usePaymentPlan: () => ({ data: seed.paymentPlan }),
  useUploadVoucher: () => ({ mutate: seed.uploadMutate, isPending: false }),
}));

vi.mock("@/hooks/use-production", () => ({
  useDeliveryStatus: () => ({ data: seed.deliveryStatus }),
}));

vi.mock("@/hooks/use-me", () => ({
  useMe: () => ({ data: seed.me, isPending: seed.mePending }),
}));

const kickoff = (status: number, over: Partial<PaymentPlan["nodes"][number]> = {}) => ({
  id: "n1",
  seq: 1,
  name: "首期开工款",
  amount: 1920000,
  percentage: 40,
  status,
  statusName: undefined,
  remarkCode: "SG-XXX-K1",
  kickoffGate: true,
  channelType: 1,
  channelTypeName: "线下转账",
  channelRefNo: undefined,
  settledAmount: 0,
  remainingAmount: 1920000,
  receivedAt: undefined,
  vouchers: [],
  ...over,
});

function seedQuotedOrder(): OrderDetail {
  return {
    id: "o1",
    projectId: "p1",
    status: ORDER_STATUS.quoted,
    statusName: "已报价",
    prdVersionNo: 2,
    demoVersionNo: 1,
    amount: 4800000,
    currency: "CNY",
    note: "固定总价包干制",
    scopeDeliverables: "全部前后端微服务源代码、容器化部署包、API 文档与 14 个工作日维保",
    scopeAcceptance: "按里程碑验收",
    scopeTimeline: "3 周",
    priceEntries: [],
  };
}

function seedSignedContract(): ContractDetail {
  return {
    id: "c1",
    contractNo: "SG-XXX",
    orderId: "o1",
    status: CONTRACT_STATUS.signed,
    statusName: "签署完成",
    version: 1,
    needsUpdate: false,
    partyA: "客户方",
    partyB: "语义引力数字交付中心",
    projectScope: "智能企业客户预约系统",
    scopeDeliverables: "全部前后端微服务源代码、容器化部署包、API 文档与 14 个工作日维保",
    scopeAcceptance: "按里程碑验收",
    amount: 4800000,
    currency: "CNY",
    prdVersionNo: 2,
    demoVersionNo: 1,
    partyASigned: true,
    partyBSigned: true,
  };
}

function seedPlan(over: { kickoffStatus?: number; settled?: boolean } = {}): PaymentPlan {
  return {
    id: "pl1",
    orderId: "o1",
    contractId: "c1",
    contractNo: "SG-XXX",
    totalAmount: 4800000,
    totalSettled: 0,
    nodes: [
      kickoff(over.kickoffStatus ?? PAYMENT_NODE_STATUS.pending),
      {
        id: "n2",
        seq: 2,
        name: "中期验收款",
        amount: 1920000,
        percentage: 40,
        status: PAYMENT_NODE_STATUS.pending,
        remarkCode: "SG-XXX-K2",
        kickoffGate: false,
        vouchers: [],
      },
      {
        id: "n3",
        seq: 3,
        name: "尾款结算",
        amount: 960000,
        percentage: 20,
        status: PAYMENT_NODE_STATUS.pending,
        remarkCode: "SG-XXX-K3",
        kickoffGate: false,
        vouchers: [],
      },
    ],
  };
}

function seedProject(over: Partial<ProjectDetail> = {}): ProjectDetail {
  return { id: "p1", name: "智能企业客户预约系统", activeOrder: { id: "o1", status: 2 }, ...over } as ProjectDetail;
}

function renderPage() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <ContractStagePage projectId="p1" />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  seed.detail = seedProject();
  seed.order = seedQuotedOrder();
  seed.contract = null;
  seed.paymentPlan = null;
  seed.deliveryStatus = null;
  seed.orderQueryId = undefined;
  seed.me = { accountId: "a1", displayName: "张三" };
  seed.mePending = false;
  seed.confirmMutate.mockReset();
  seed.cancelMutate.mockReset();
  seed.uploadMutate.mockReset();
  useM01Store.getState().closeM01();
  useVersionRecordStore.getState().closeVersionRecord();
});

afterEach(cleanup);

describe("签约付款页 P06 · 原型结构（#348）", () => {
  it("阶段头卡＋三子步＋黄金门禁横幅照原型落位（话术逐字）", () => {
    renderPage();

    expect(screen.getByText(/STAGE P06 \/ P07/)).toBeTruthy();
    expect(screen.getByText("合同签署与开工款承接校验")).toBeTruthy();
    expect(screen.getByText("企业级双重准入保障")).toBeTruthy();
    expect(screen.getByText("核对范围与报价")).toBeTruthy();
    expect(screen.getByText("签署合同")).toBeTruthy();
    expect(screen.getByText("开工款支付与核验")).toBeTruthy();
    expect(screen.getByText("严正开工法则 (DELIVERY GOLDEN GATE)")).toBeTruthy();
    expect(screen.getByText("双重门禁阻断机制")).toBeTruthy();
    expect(screen.getByText("DISPATCH LOCKED")).toBeTruthy();
  });

  it("已报价未冻结：子步一当前、确认范围并准备合同触发冻结动作", () => {
    renderPage();

    // 子步一当前（已报价、无合同），子步二三待开始
    expect(screen.getByText("Step 01 · 当前阶段")).toBeTruthy();
    expect(screen.getByText("Step 02 · 待开始")).toBeTruthy();
    expect(screen.getByText("Step 03 · 待开始")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "确认范围并准备合同" }));
    expect(seed.confirmMutate).toHaveBeenCalledWith("o1");
  });

  it("待报价：「待确认报价」占位态，不出现金额/确认动作", () => {
    seed.order = { ...seedQuotedOrder(), status: ORDER_STATUS.pendingQuote, statusName: "待报价", amount: undefined };
    renderPage();

    expect(screen.getAllByText("待确认报价").length).toBeGreaterThan(0);
    expect(screen.queryByRole("button", { name: "确认范围并准备合同" })).toBeNull();
    expect(screen.queryByText("¥48,000")).toBeNull();
  });

  it("合同进场：子步一完成、子步二当前、合同六态与版本号呈现", () => {
    seed.contract = { ...seedSignedContract(), status: CONTRACT_STATUS.awaitingSignature, statusName: "待核对与签署", partyASigned: false, partyBSigned: false };
    renderPage();

    expect(screen.getByText("Step 01 · 商业基线已冻结")).toBeTruthy();
    expect(screen.getByText("Step 02 · 当前阶段")).toBeTruthy();
    expect(screen.getByText("版本 v1")).toBeTruthy();
    expect(screen.getAllByText("待核对与签署").length).toBeGreaterThan(0);
    expect(screen.getByText("甲方签署人 (客户方)")).toBeTruthy();
    expect(screen.getByText("乙方签署人 (交付方)")).toBeTruthy();
  });

  it("门禁翻态：签署完成＋开工款到账 → GATE PERMITTED", () => {
    seed.contract = seedSignedContract();
    seed.paymentPlan = seedPlan({ kickoffStatus: PAYMENT_NODE_STATUS.received });
    renderPage();

    expect(screen.getByText("GATE PERMITTED")).toBeTruthy();
    expect(screen.getByText("READINESS APPROVED")).toBeTruthy();
    expect(screen.queryByText("DISPATCH LOCKED")).toBeNull();
    expect(screen.getByText("进入生产看板 →")).toBeTruthy();
    // 开工款足额到账后「当前应付总额」转「已到账」，不显示残留金额
    expect(screen.getAllByText("已到账").length).toBeGreaterThan(0);
  });

  it("无进行中订单（已取消/已归档）：范围与报价子步出占位、不呈现可动作态", () => {
    seed.detail = seedProject({ activeOrder: undefined, archived: false });
    seed.order = null;
    renderPage();

    expect(screen.getByText(/当前没有进行中的订单/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "确认范围并准备合同" })).toBeNull();
    expect(screen.queryByRole("button", { name: "取消订单" })).toBeNull();
  });

  it("归档项目从 P07 回看：改取最近订单的合同与付款记录，上传与签约动作关闭", () => {
    seed.detail = seedProject({
      activeOrder: undefined,
      latestOrder: { id: "o9", status: ORDER_STATUS.archived },
      archived: true,
    });
    seed.order = {
      ...seedQuotedOrder(),
      id: "o9",
      status: ORDER_STATUS.archived,
      statusName: "已归档",
    };
    seed.contract = seedSignedContract();
    seed.paymentPlan = { ...seedPlan({ kickoffStatus: PAYMENT_NODE_STATUS.received }), orderId: "o9" };
    renderPage();

    expect(seed.orderQueryId).toBe("o9");
    expect(screen.getByText("版本 v1")).toBeTruthy();
    expect(screen.getByText("首期开工款结算节点")).toBeTruthy();
    expect(screen.queryByRole("button", { name: /上传转账凭证|再传一张凭证/ })).toBeNull();
    expect(screen.queryByRole("button", { name: "确认范围并准备合同" })).toBeNull();
    expect(screen.queryByRole("button", { name: "取消订单" })).toBeNull();
  });

  it("签署完成但开工款未到账：门禁仍锁定、开工款 checklist 待确认", () => {
    seed.contract = seedSignedContract();
    seed.paymentPlan = seedPlan({ kickoffStatus: PAYMENT_NODE_STATUS.pending });
    renderPage();

    expect(screen.getByText("DISPATCH LOCKED")).toBeTruthy();
    expect(screen.getByText("PENDING CLEARANCE")).toBeTruthy();
    expect(screen.getAllByText("待确认 ⏱️").length).toBeGreaterThan(0);
  });

  it("付款子步：凭证上传触发上传动作、差额呈现", () => {
    seed.contract = seedSignedContract();
    seed.paymentPlan = seedPlan({ kickoffStatus: PAYMENT_NODE_STATUS.pending });
    renderPage();

    expect(screen.getByText("首期开工款结算节点")).toBeTruthy();
    expect(screen.getByText(/还需支付/)).toBeTruthy();

    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    const file = new File(["receipt"], "receipt.pdf", { type: "application/pdf" });
    fireEvent.change(input, { target: { files: [file] } });

    expect(seed.uploadMutate).toHaveBeenCalledTimes(1);
    expect(seed.uploadMutate.mock.calls[0][0]).toMatchObject({ nodeId: "n1" });
    expect((seed.uploadMutate.mock.calls[0][0] as { file: File }).file.name).toBe("receipt.pdf");
  });

  it("PRD/Demo 快照版本引用呈现，点击打开版本记录抽屉", () => {
    seed.contract = seedSignedContract();
    seed.paymentPlan = seedPlan({ kickoffStatus: PAYMENT_NODE_STATUS.received });
    renderPage();

    expect(screen.getByText("PRD v2 不可变需求快照")).toBeTruthy();
    expect(screen.getByText("Demo v1 终审交互沙盒")).toBeTruthy();

    fireEvent.click(screen.getByText("PRD v2 不可变需求快照"));
    expect(useVersionRecordStore.getState().open).toBe(true);
  });

  it("合同预览长字段区窄屏独立滚动（#350，交接包 13.2：长文档和合同支持独立滚动）", () => {
    seed.contract = seedSignedContract();
    renderPage();

    const dl = screen.getByText("项目范围").closest("dl");
    expect(dl?.className).toContain("overflow-y-auto");
    expect(dl?.className).toContain("overscroll-contain");
    // 窄屏限高独立滚动；≥xl 解除（桌面页滚不受内层框）
    expect(dl?.className).toContain("xl:max-h-none");
  });
});

describe("签约付款页 P06 · M01 登录门（#348）", () => {
  it("匿名访客进入本页：弹 M01（stage-gate 来路）", () => {
    seed.me = undefined;
    renderPage();

    expect(useM01Store.getState().open).toBe(true);
    expect(useM01Store.getState().trigger).toBe("stage-gate");
  });

  it("已登录账号进入本页：不弹 M01", () => {
    seed.me = { accountId: "a1", displayName: "张三" };
    renderPage();

    expect(useM01Store.getState().open).toBe(false);
  });

  it("会话探明中不急于弹 M01（静候 /me 判定）", () => {
    seed.me = undefined;
    seed.mePending = true;
    renderPage();

    expect(useM01Store.getState().open).toBe(false);
  });
});
