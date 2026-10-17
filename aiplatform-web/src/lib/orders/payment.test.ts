import { describe, expect, it } from "vitest";

import {
  nodeAcceptsVoucher,
  normalizePaymentPlan,
  outstandingPaymentOf,
  voucherFileAcceptable,
  type PaymentPlanResponse,
} from "./payment";

/**
 * swagger 原始形状基准（schema 声明 int64 为 number——线上实为字符串，容错
 * 折数在字符串钉死用例单独验）。null 载荷形态以 undefined 呈现（可选字段）。
 */
const rawPlan: PaymentPlanResponse = {
  id: "910",
  orderId: "900",
  contractId: "920",
  contractNo: "SG-3ukqz0c0",
  totalAmount: 4800000,
  totalSettled: 1920000,
  createdAt: "2026-10-17T01:00:00Z",
  nodes: [
    {
      id: "911",
      seq: 1,
      name: "首期开工款",
      amount: 1920000,
      percentage: 40,
      status: 1,
      statusName: "待支付",
      remarkCode: "SG-3ukqz0c0-K1",
      kickoffGate: true,
      channelType: 1,
      channelTypeName: "线下转账",
      settledAmount: 1000000,
      remainingAmount: 920000,
      vouchers: [
        {
          id: "930",
          nodeId: "911",
          fileName: "receipt.pdf",
          contentType: "application/pdf",
          byteSize: 1024,
          createdAt: "2026-10-17T02:00:00Z",
        },
      ],
    },
    {
      id: "912",
      seq: 2,
      name: "尾款结算",
      amount: 2880000,
      percentage: 60,
      status: 5,
      statusName: "已到账",
      remarkCode: "SG-3ukqz0c0-K2",
      kickoffGate: false,
      channelType: 1,
      channelTypeName: "线下转账",
      channelRefNo: "BANK-1",
      settledAmount: 2880000,
      remainingAmount: 0,
      receivedAt: "2026-10-16T09:00:00Z",
      vouchers: [],
    },
  ],
};

describe("normalizePaymentPlan（#337 消费口径归一）", () => {
  it("Long 字符串折数为 number、缺省字段防御归一", () => {
    const plan = normalizePaymentPlan(rawPlan);

    expect(plan.totalAmount).toBe(4800000);
    expect(plan.totalSettled).toBe(1920000);
    const first = plan.nodes[0]!;
    expect(first.amount).toBe(1920000);
    expect(first.settledAmount).toBe(1000000);
    expect(first.remainingAmount).toBe(920000);
    expect(first.kickoffGate).toBe(true);
    expect(first.vouchers[0]!.byteSize).toBe(1024);
    expect(plan.nodes[1]!.channelRefNo).toBe("BANK-1");
  });

  it("空载荷（无节点）归一为空清单不炸", () => {
    const plan = normalizePaymentPlan({ id: "910" });

    expect(plan.nodes).toEqual([]);
    expect(plan.totalAmount).toBeUndefined();
  });

  it("Long 字段线上实为字符串（Jackson Long→String，schema 声明 number）：折数容忍", () => {
    const plan = normalizePaymentPlan(
      {
        ...rawPlan,
        totalAmount: "4800000" as unknown as number,
        nodes: rawPlan.nodes?.map((node) => ({
          ...node,
          amount: "1920000" as unknown as number,
          remainingAmount: "920000" as unknown as number,
        })),
      },
    );

    expect(plan.totalAmount).toBe(4800000);
    expect(plan.nodes[0]!.amount).toBe(1920000);
    expect(plan.nodes[0]!.remainingAmount).toBe(920000);
  });
});

describe("nodeAcceptsVoucher（凭证上传可达态）", () => {
  it("待支付/待核验可传；已到账不收；处理中/失败 v1 不可达同样不收", () => {
    const node = (status?: number) => ({ id: "n", status, vouchers: [] });

    expect(nodeAcceptsVoucher(node(1))).toBe(true);
    expect(nodeAcceptsVoucher(node(4))).toBe(true);
    expect(nodeAcceptsVoucher(node(5))).toBe(false);
    expect(nodeAcceptsVoucher(node(2))).toBe(false);
    expect(nodeAcceptsVoucher(node(3))).toBe(false);
    expect(nodeAcceptsVoucher(node(undefined))).toBe(false);
  });
});

describe("outstandingPaymentOf（还需支付推导——AC：前台可推导差额与节点）", () => {
  it("未到账节点带差额：合计＋对应节点清单；已到账节点不入列", () => {
    const plan = normalizePaymentPlan(rawPlan);
    const outstanding = outstandingPaymentOf(plan);

    // 仅首节点（已核到账 10000 元、差额 9200 元）；尾款已到账不入列
    expect(outstanding).not.toBeNull();
    expect(outstanding!.total).toBe(920000);
    expect(outstanding!.nodes.map((node) => node.name)).toEqual(["首期开工款"]);
  });

  it("全部到账：返回 null（面板转「款项已全部到账」）", () => {
    const plan = normalizePaymentPlan({
      ...rawPlan,
      nodes: rawPlan.nodes?.map((node) => ({ ...node, status: 5, remainingAmount: 0 })),
    });

    expect(outstandingPaymentOf(plan)).toBeNull();
  });

  it("待核验节点（有凭证无核验结论）：差额仍在推导面", () => {
    const plan = normalizePaymentPlan({
      ...rawPlan,
      nodes: rawPlan.nodes?.map((node) => ({
        ...node,
        status: 4,
        // 全员待核验＝全员未到账：尾款核到账清零、差额回全额
        settledAmount: 0,
        remainingAmount: node.amount,
      })),
    });
    const outstanding = outstandingPaymentOf(plan);

    expect(outstanding!.total).toBe(4800000);
    expect(outstanding!.nodes).toHaveLength(2);
  });
});

describe("voucherFileAcceptable（上传前预检——省注定 400 的上传）", () => {
  it("四格式放行、其余拒绝；超 10MB 拒绝", () => {
    expect(voucherFileAcceptable(new File(["x"], "回单.PDF"))).toBeNull();
    expect(voucherFileAcceptable(new File(["x"], "receipt.png"))).toBeNull();
    expect(voucherFileAcceptable(new File(["x"], "receipt.jpg"))).toBeNull();
    expect(voucherFileAcceptable(new File(["x"], "receipt.jpeg"))).toBeNull();
    expect(voucherFileAcceptable(new File(["x"], "note.txt"))).toContain("PDF / PNG / JPG");

    const oversize = new File([new Uint8Array(10 * 1024 * 1024 + 1)], "big.pdf");
    // File.size 以实际字节为准（Uint8Array 一字节一元素）
    expect(oversize.size).toBeGreaterThan(10 * 1024 * 1024);
    expect(voucherFileAcceptable(oversize)).toContain("10MB");
  });
});
