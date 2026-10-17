// @vitest-environment happy-dom
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ApiError } from "@/lib/api/api-error";
import { UNAUTHORIZED_EVENT } from "@/lib/api/client";
import { useM01Store } from "@/lib/store/m01";

import { M01Dialog } from "./m01-dialog";

// M01 弹窗交互契约（#328 落地本体）：两点触发文案、发码→验码两步、验码成功即
// 关窗并续跑 resume（签约门「绑定后放行」）、验码错 6008 就地提示不清窗、401
// 全局出口开门。数据口 mock（auth API），store 真实转移。

const auth = vi.hoisted(() => ({ sendCode: vi.fn(), verifyCode: vi.fn() }));
vi.mock("@/lib/auth/code-login", () => ({
  sendCode: auth.sendCode,
  verifyCode: auth.verifyCode,
}));

function renderDialog() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const invalidate = vi.spyOn(qc, "invalidateQueries");
  render(
    <QueryClientProvider client={qc}>
      <M01Dialog />
    </QueryClientProvider>,
  );
  return { invalidate };
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  useM01Store.setState({ open: false, trigger: null, resume: null });
  auth.sendCode.mockReset();
  auth.verifyCode.mockReset();
});

describe("M01Dialog · 触发文案", () => {
  it("签约门来路：出「登录后进入签约付款」文案", () => {
    useM01Store.setState({ open: true, trigger: "stage-gate" });
    renderDialog();

    expect(screen.getByText("登录后进入签约付款")).toBeTruthy();
  });

  it("保存项目来路：出「登录保存项目」文案", () => {
    useM01Store.setState({ open: true, trigger: "save-project" });
    renderDialog();

    expect(screen.getByText("登录保存项目")).toBeTruthy();
  });
});

describe("M01Dialog · 发码→验码两步", () => {
  it("输入目标发码进第二步，验码成功即关窗并续跑 resume", async () => {
    const resume = vi.fn();
    auth.sendCode.mockResolvedValue({ expiresInSeconds: 300 });
    auth.verifyCode.mockResolvedValue({ accountId: "1", displayName: "张三" });
    useM01Store.setState({ open: true, trigger: "stage-gate", resume });
    const { invalidate } = renderDialog();

    fireEvent.change(screen.getByLabelText("邮箱 / 手机号"), {
      target: { value: "user@example.com" },
    });
    fireEvent.click(screen.getByRole("button", { name: "获取验证码" }));

    expect(await screen.findByText(/验证码已发送至 user@example\.com/)).toBeTruthy();
    expect(auth.sendCode).toHaveBeenCalledWith("user@example.com");

    fireEvent.change(screen.getByLabelText("验证码"), { target: { value: "123456" } });
    fireEvent.click(screen.getByRole("button", { name: "验证并继续" }));

    await waitFor(() => expect(auth.verifyCode).toHaveBeenCalledWith("user@example.com", "123456"));
    await waitFor(() => expect(resume).toHaveBeenCalledTimes(1)); // 绑定后放行：续跑本单
    expect(useM01Store.getState().open).toBe(false);
    expect(invalidate).toHaveBeenCalled(); // 会话与项目域失效自愈
  });
});

describe("M01Dialog · 错误就地提示", () => {
  it("验证码错（6008）就地提示、清空输入、弹窗不关", async () => {
    auth.sendCode.mockResolvedValue({ expiresInSeconds: 300 });
    auth.verifyCode.mockRejectedValue(
      new ApiError({ status: 400, code: 6008, message: "验证码错误或已过期" }),
    );
    useM01Store.setState({ open: true, trigger: "save-project" });
    renderDialog();

    fireEvent.change(screen.getByLabelText("邮箱 / 手机号"), {
      target: { value: "user@example.com" },
    });
    fireEvent.click(screen.getByRole("button", { name: "获取验证码" }));
    await screen.findByText(/验证码已发送至/);

    fireEvent.change(screen.getByLabelText("验证码"), { target: { value: "000000" } });
    fireEvent.click(screen.getByRole("button", { name: "验证并继续" }));

    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toBe("验证码错误或已过期");
    expect(useM01Store.getState().open).toBe(true); // 弹窗不关
    expect((screen.getByLabelText("验证码") as HTMLInputElement).value).toBe(""); // 清空重输
  });

  it("发码被频控（6007）：第一步就地提示，不进第二步", async () => {
    auth.sendCode.mockRejectedValue(
      new ApiError({ status: 429, code: 6007, message: "发送过于频繁，请稍后再试" }),
    );
    useM01Store.setState({ open: true, trigger: "save-project" });
    renderDialog();

    fireEvent.change(screen.getByLabelText("邮箱 / 手机号"), {
      target: { value: "user@example.com" },
    });
    fireEvent.click(screen.getByRole("button", { name: "获取验证码" }));

    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toBe("发送过于频繁，请稍后再试");
    expect(screen.getByRole("button", { name: "获取验证码" })).toBeTruthy(); // 仍在第一步
  });
});

describe("M01Dialog · 401 全局出口", () => {
  it("401 事件开门（保存项目来路）", async () => {
    renderDialog(); // 初始关闭

    fireEvent(window, new CustomEvent(UNAUTHORIZED_EVENT, { detail: { path: "/me", returnTo: "/" } }));

    await waitFor(() => expect(useM01Store.getState().open).toBe(true));
    expect(useM01Store.getState().trigger).toBe("save-project");
  });
});
