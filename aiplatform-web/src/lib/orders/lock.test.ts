import { describe, expect, it } from "vitest";

import { lockRowOf, ORDER_LOCK_HINT, ORDER_STATUS } from "./lock";

/** 锁定式矩阵（#28 验收：纯函数测试）——订单态 × UI 可用性逐行钉死。 */
describe("lockRowOf · 锁定式矩阵", () => {
  it("进行中（无未终结订单、未归档）：全功能行", () => {
    expect(lockRowOf({ archived: false, activeOrder: null })).toEqual({
      chatInput: "open",
      outputsLocked: false,
    });
    // 缺省输入（详情未到/字段缺省）同进行中兜底
    expect(lockRowOf({})).toEqual({ chatInput: "open", outputsLocked: false });
  });

  it("待报价：指令区禁用+锁定提示、成果区只读（本片接线行）", () => {
    const row = lockRowOf({
      activeOrder: { id: "o1", status: ORDER_STATUS.pendingQuote, statusName: "待报价" },
    });
    expect(row.chatInput).toBe("locked");
    expect(row.chatHint).toBe(ORDER_LOCK_HINT);
    expect(row.outputsLocked).toBe(true);
  });

  it("已报价（=待支付）：同锁定行（主操作面归 #29 接线）", () => {
    const row = lockRowOf({
      activeOrder: { id: "o1", status: ORDER_STATUS.quoted, statusName: "已报价" },
    });
    expect(row.chatInput).toBe("locked");
    expect(row.chatHint).toBe(ORDER_LOCK_HINT);
    expect(row.outputsLocked).toBe(true);
  });

  it("订单存在但状态未知：按锁定兜底（订单在即冻结，不误开输入）", () => {
    expect(lockRowOf({ activeOrder: { id: "o1" } }).chatInput).toBe("locked");
  });

  it("已支付（归档前的瞬时态）/订单已归档：终态行——指令区关闭、全只读", () => {
    for (const status of [ORDER_STATUS.paid, ORDER_STATUS.archived]) {
      const row = lockRowOf({ activeOrder: { id: "o1", status } });
      expect(row.chatInput).toBe("closed");
      expect(row.outputsLocked).toBe(true);
    }
  });

  it("已归档项目（归档终态，无订单事实）：指令区关闭、全只读", () => {
    const row = lockRowOf({ archived: true });
    expect(row.chatInput).toBe("closed");
    expect(row.chatHint).toBe("项目已归档，指令区已关闭");
    expect(row.outputsLocked).toBe(true);
  });

  it("归档优先于订单事实（支付成功订单项目一并归档，两信号同现取终态）", () => {
    expect(
      lockRowOf({ archived: true, activeOrder: { id: "o1", status: ORDER_STATUS.pendingQuote } })
        .chatInput,
    ).toBe("closed");
  });
});
