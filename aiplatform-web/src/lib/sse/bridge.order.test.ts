import { beforeEach, describe, expect, it, vi } from "vitest";

import { queryKeys } from "@/lib/api/keys";

import { dispatchNotificationEvent, noteRouteContext } from "./bridge";

// 订单事件通知的消费面（#30 失效 + #206 toast 分流）：失效（三域重拉）+ toast
// （状态文案单点 + 「查看项目」动作，路标组件内跳转）。toast 库 mock 掉；失效
// 断言按注册表前缀。
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

/** 登记路由上下文（#206 分流的判定输入）并返回 push 桩；每用例显式落位。 */
function at(pathname: string) {
  const push = vi.fn();
  noteRouteContext({ pathname, push });
  return push;
}

beforeEach(() => {
  toastMock.mockClear();
  at("/"); // 基准 = 他页在场；在场用例内显式覆盖
});

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

describe("bridge · 订单事件 toast 分流（#206）", () => {
  it("项目页在场收首报：不弹 toast——对话史失效水合报价卡入流即感知", () => {
    at("/projects/900");

    dispatch(orderEvent(2));

    expect(toastMock).not.toHaveBeenCalled();
  });

  it("项目页在场收改价：不弹 toast——历史报价卡视镜经订单域重查显新价", () => {
    at("/projects/900");

    dispatch(repricedEvent());

    expect(toastMock).not.toHaveBeenCalled();
  });

  it("在场分流只免 toast，失效照旧（三域重拉是入流/显新价的通道）", () => {
    at("/projects/900");

    const invalidateQueries = dispatch(orderEvent(2));

    expect(invalidateQueries).toHaveBeenCalledTimes(3);
  });

  it("他页收首报/改价：toast 路标文案区分首报与改价（信号不带金额）", () => {
    at("/projects");

    dispatch(orderEvent(2));
    expect(toastMock).toHaveBeenCalledWith("报价已出，可以支付了", {
      action: { label: "查看项目", onClick: expect.any(Function) },
    });

    toastMock.mockClear();
    dispatch(repricedEvent());
    expect(toastMock).toHaveBeenCalledWith("报价已更新，请以新价为准", {
      action: { label: "查看项目", onClick: expect.any(Function) },
    });
  });

  it("路标动作组件内跳转：走登记的 router.push（无整页刷新）", () => {
    const push = at("/projects");

    dispatch(orderEvent(2));
    const { onClick } = toastMock.mock.calls[0][1].action;
    onClick();

    expect(push).toHaveBeenCalledWith("/projects/900");
  });

  it("在场判定精确匹配：/projects/9001 不是订单 900 的项目页", () => {
    at("/projects/9001");

    dispatch(orderEvent(2));

    expect(toastMock).toHaveBeenCalledTimes(1);
  });

  it("其他状态在场仍弹：订单卡支付/取消的成功反馈依赖本 toast（#30 口径）", () => {
    at("/projects/900");

    dispatch(orderEvent(3, "已支付"));

    expect(toastMock).toHaveBeenCalledTimes(1);
  });
});
