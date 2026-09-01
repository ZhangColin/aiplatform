import { describe, expect, it } from "vitest";

import { ORDER_STATUS } from "@/lib/orders/lock";
import { orderStatusToastText } from "@/lib/orders/status";

// 订单态变化 toast 文案（#30）：状态 → 用户面话术的唯一推导点（SSE 桥消费）。
describe("orderStatusToastText", () => {
  it("逐态话术：说清楚发生了什么/该做什么", () => {
    expect(orderStatusToastText(ORDER_STATUS.pendingQuote)).toBe("订单已提交，后台将尽快报价");
    expect(orderStatusToastText(ORDER_STATUS.quoted)).toBe("报价已出，可以支付了");
    expect(orderStatusToastText(ORDER_STATUS.paid)).toBe("支付完成，项目已归档");
    expect(orderStatusToastText(ORDER_STATUS.archived)).toBe("支付完成，项目已归档");
    expect(orderStatusToastText(ORDER_STATUS.cancelled)).toBe("订单已取消，项目已恢复迭代");
  });

  it("未知态回落状态名；状态名也缺时兜底通用文案", () => {
    expect(orderStatusToastText(99, "神秘态")).toBe("订单状态更新：神秘态");
    expect(orderStatusToastText(undefined, undefined)).toBe("订单状态已更新");
  });
});
