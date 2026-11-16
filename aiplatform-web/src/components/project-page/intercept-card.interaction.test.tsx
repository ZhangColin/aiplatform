// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { ChatMessage } from "@/lib/store/chat";
import { useCheckoutStore } from "@/lib/store/checkout";
import { useM01Store } from "@/lib/store/m01";

import { InterceptCard } from "./intercept-card";

// 请求面 mock 掉（动作路由断言归本文件——收银台/M01 store 真实转移）
const meState = vi.hoisted(() => ({ loggedIn: true }));
vi.mock("@/hooks/use-intercept", () => ({
  useConfirmIntercept: () => ({ isPending: false, mutate: vi.fn() }),
}));
vi.mock("@/hooks/use-me", () => ({
  useMe: () => ({ data: meState.loggedIn ? { accountId: "7", displayName: "测试" } : undefined }),
}));

type InterceptMessage = Extract<ChatMessage, { kind: "intercept" }>;

function message(overrides: Partial<InterceptMessage> = {}): InterceptMessage {
  return {
    kind: "intercept",
    id: "i1",
    settled: false,
    card: { interceptId: "ic-1", lane: "OPINION", prompt: "把主色调改成绿色", sourceSelected: false },
    ...overrides,
  };
}

afterEach(() => {
  cleanup();
  meState.loggedIn = true;
  useCheckoutStore.setState({ open: false, projectId: null });
  useM01Store.setState({ open: false, trigger: null, resume: null });
});

describe("InterceptCard · 「先去充值」接通模拟收银台（#534 计费④）", () => {
  it("已登录：项目上下文开收银台，卡不关闭（被拦输入随服务端留存）", () => {
    render(<InterceptCard projectId="p-1" message={message()} />);

    fireEvent.click(screen.getByRole("button", { name: "先去充值" }));

    expect(useCheckoutStore.getState().open).toBe(true);
    expect(useCheckoutStore.getState().projectId).toBe("p-1");
    expect(useM01Store.getState().open).toBe(false); // 不弹登录
  });

  it("未登录：先 M01（checkout-login 来路）——登录成功 resume 重开收银台", () => {
    meState.loggedIn = false;
    render(<InterceptCard projectId="p-1" message={message()} />);

    fireEvent.click(screen.getByRole("button", { name: "先去充值" }));

    expect(useCheckoutStore.getState().open).toBe(false);
    expect(useM01Store.getState().open).toBe(true);
    expect(useM01Store.getState().trigger).toBe("checkout-login");
    // 「绑定后放行」同构：验码成功回调续跑——重开收银台
    useM01Store.getState().resume?.();
    expect(useCheckoutStore.getState().open).toBe(true);
    expect(useCheckoutStore.getState().projectId).toBe("p-1");
  });
});
