import { describe, expect, it, vi } from "vitest";

import { queryKeys } from "@/lib/api/keys";

import { dispatchNotificationEvent } from "./bridge";

// 订单态变化通知的消费面（#30）：失效（projects + orders 两域重拉）+ toast
// （状态文案单点 + 「查看项目」动作）。toast 库 mock 掉；失效断言按注册表前缀。
const toastMock = vi.hoisted(() => vi.fn());
vi.mock("sonner", () => ({ toast: toastMock }));

function orderEvent(status: number, statusName = "已报价") {
  return {
    id: "900:7",
    data: JSON.stringify({
      type: "order-status-changed",
      payload: { projectId: "900", orderId: "901", status, statusName },
      ts: "2026-09-01T03:00:00.000Z",
    }),
  };
}

/** 改价信号（#204）：改价不换状态（status 恒 2），载荷同 order-status-changed 量级。 */
function repricedEvent() {
  return {
    id: "900:9",
    data: JSON.stringify({
      type: "order-repriced",
      payload: { projectId: "900", orderId: "901", status: 2, statusName: "已报价" },
      ts: "2026-09-01T03:00:02.000Z",
    }),
  };
}

function dispatch(event: { id: string; data: string }) {
  const invalidateQueries = vi.fn();
  dispatchNotificationEvent({ invalidateQueries } as never, event);
  return invalidateQueries;
}

describe("bridge · order-status-changed（#30）", () => {
  it("失效 projects 与 orders 两域（activeOrder/archived 嵌入 + 订单卡详情重拉）", () => {
    const invalidateQueries = dispatch(orderEvent(2));

    expect(invalidateQueries).toHaveBeenCalledWith({ queryKey: queryKeys.projects.all });
    expect(invalidateQueries).toHaveBeenCalledWith({ queryKey: queryKeys.orders.all });
  });

  it("首报/态变信号连带失效对话史域（#203：在场项目页报价卡经重查水合实时入流）", () => {
    const invalidateQueries = dispatch(orderEvent(2));

    expect(invalidateQueries).toHaveBeenCalledWith({ queryKey: queryKeys.conversation.all });
  });

  it("toast 带状态文案与「查看项目」动作（点击直达项目页）", () => {
    dispatch(orderEvent(2));

    expect(toastMock).toHaveBeenCalledWith("报价已出，可以支付了", {
      action: {
        label: "查看项目",
        onClick: expect.any(Function),
      },
    });
  });

  it("非订单事件不 toast；畸形信封整体忽略", () => {
    toastMock.mockClear();

    const invalidateQueries = dispatch({
      id: "900:8",
      data: JSON.stringify({
        type: "project-renamed",
        payload: { projectId: "900", projectName: "品牌官网" },
        ts: "2026-09-01T03:00:01.000Z",
      }),
    });

    expect(invalidateQueries).toHaveBeenCalledTimes(1); // 只 projects 域
    expect(toastMock).not.toHaveBeenCalled();

    dispatchNotificationEvent({ invalidateQueries } as never, { id: "x", data: "not-json" });
    expect(toastMock).not.toHaveBeenCalled();
  });
});

describe("bridge · order-repriced（#204 改价入流）", () => {
  it("改价信号：失效 projects/orders/对话史三域——「报价已更新」卡实时入流、历史报价卡视镜显新价", () => {
    const invalidateQueries = dispatch(repricedEvent());

    expect(invalidateQueries).toHaveBeenCalledWith({ queryKey: queryKeys.projects.all });
    expect(invalidateQueries).toHaveBeenCalledWith({ queryKey: queryKeys.orders.all });
    expect(invalidateQueries).toHaveBeenCalledWith({ queryKey: queryKeys.conversation.all });
  });
});
