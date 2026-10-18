import { describe, expect, it } from "vitest";

import { CONTRACT_STATUS } from "./contract";
import { contractStageOf } from "./contract-stage";
import { ORDER_STATUS } from "./lock";
import { PAYMENT_NODE_STATUS } from "./payment";

/**
 * 三子步 + 黄金门禁推导（#348 纯函数 seam）：订单/合同/付款三源事实 → 子步
 * 态与门禁态，逐行钉死（含缺省防御与「核到账但不足不算到账」负例）。
 */
describe("contractStageOf · 三子步与黄金门禁", () => {
  const kickoff = (status: number) => ({ kickoffGate: true, status });

  it("无订单：三子步全 pending、门禁锁定", () => {
    expect(contractStageOf({})).toEqual({
      scope: "pending",
      contract: "pending",
      payment: "pending",
      gate: "locked",
      contractSigned: false,
      kickoffSettled: false,
      verifying: false,
    });
  });

  it("待报价/已报价无合同：子步一 current、二三 pending、门禁锁定", () => {
    for (const status of [ORDER_STATUS.pendingQuote, ORDER_STATUS.quoted]) {
      const stage = contractStageOf({ order: { status } });
      expect(stage.scope).toBe("current");
      expect(stage.contract).toBe("pending");
      expect(stage.payment).toBe("pending");
      expect(stage.gate).toBe("locked");
    }
  });

  it("已取消订单：无活跃签约面——子步一 pending（不呈现可动作态）", () => {
    const stage = contractStageOf({ order: { status: ORDER_STATUS.cancelled } });
    expect(stage.scope).toBe("pending");
    expect(stage.contract).toBe("pending");
    expect(stage.payment).toBe("pending");
    expect(stage.gate).toBe("locked");
  });

  it("合同准备中：子步一 done、子步二 current、子步三 pending", () => {
    const stage = contractStageOf({
      order: { status: ORDER_STATUS.quoted },
      contract: { status: CONTRACT_STATUS.preparing },
    });
    expect(stage.scope).toBe("done");
    expect(stage.contract).toBe("current");
    expect(stage.payment).toBe("pending");
    expect(stage.gate).toBe("locked");
  });

  it("合同签署完成但开工款未到账：子步一/二 done、子步三 current、门禁锁定", () => {
    const stage = contractStageOf({
      order: { status: ORDER_STATUS.quoted },
      contract: { status: CONTRACT_STATUS.signed },
      paymentPlan: { nodes: [kickoff(PAYMENT_NODE_STATUS.pending)] },
    });
    expect(stage.scope).toBe("done");
    expect(stage.contract).toBe("done");
    expect(stage.payment).toBe("current");
    expect(stage.gate).toBe("locked");
    expect(stage.contractSigned).toBe(true);
    expect(stage.kickoffSettled).toBe(false);
  });

  it("开工门节点全部到账：三子步全 done、门禁 GATE PERMITTED", () => {
    const stage = contractStageOf({
      order: { status: ORDER_STATUS.quoted },
      contract: { status: CONTRACT_STATUS.signed },
      paymentPlan: {
        nodes: [
          kickoff(PAYMENT_NODE_STATUS.received),
          { kickoffGate: false, status: PAYMENT_NODE_STATUS.pending },
        ],
      },
    });
    expect(stage.scope).toBe("done");
    expect(stage.contract).toBe("done");
    expect(stage.payment).toBe("done");
    expect(stage.gate).toBe("permitted");
  });

  it("「核到账但不足」的 pending 节点不算到账：门禁仍锁定", () => {
    const stage = contractStageOf({
      order: { status: ORDER_STATUS.quoted },
      contract: { status: CONTRACT_STATUS.signed },
      // 开工门节点核到部分款但未足额（remainingAmount>0 的 pending 态）
      paymentPlan: { nodes: [kickoff(PAYMENT_NODE_STATUS.pending)] },
    });
    expect(stage.kickoffSettled).toBe(false);
    expect(stage.gate).toBe("locked");
  });

  it("无开工门标记节点不臆断为满足：门禁锁定", () => {
    const stage = contractStageOf({
      order: { status: ORDER_STATUS.quoted },
      contract: { status: CONTRACT_STATUS.signed },
      paymentPlan: { nodes: [{ kickoffGate: false, status: PAYMENT_NODE_STATUS.received }] },
    });
    expect(stage.kickoffSettled).toBe(false);
    expect(stage.gate).toBe("locked");
  });

  it("有节点处线下转账待核验：verifying 置位（Step 03 核验中 pill 判据）", () => {
    const stage = contractStageOf({
      order: { status: ORDER_STATUS.quoted },
      contract: { status: CONTRACT_STATUS.signed },
      paymentPlan: { nodes: [kickoff(PAYMENT_NODE_STATUS.awaitingVerification)] },
    });
    expect(stage.verifying).toBe(true);
    expect(stage.payment).toBe("current");
  });

  it("合同终态（失败或过期/撤回或作废）：子步二不回 done、门禁锁定", () => {
    for (const status of [CONTRACT_STATUS.failedOrExpired, CONTRACT_STATUS.withdrawnOrVoided]) {
      const stage = contractStageOf({
        order: { status: ORDER_STATUS.quoted },
        contract: { status },
      });
      expect(stage.contract).toBe("current");
      expect(stage.gate).toBe("locked");
    }
  });
});
