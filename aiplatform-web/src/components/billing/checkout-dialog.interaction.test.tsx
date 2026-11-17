// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ApiError } from "@/lib/api/api-error";
import { useCheckoutStore } from "@/lib/store/checkout";

import { CheckoutDialog } from "./checkout-dialog";

// 数据口 mock（billing API 面）：hooks 层打桩——动作路由与四步流程断言归本文件。
// mockProjectBilling.target 控制项目账单的 checkoutTarget 推导（1=我的账户＝账户源引导）。
const { billing, mockProjectBilling } = vi.hoisted(() => ({
  billing: {
    validateAccessCode: vi.fn(),
    topup: vi.fn(),
  },
  mockProjectBilling: { target: 2 as 1 | 2 },
}));

vi.mock("@/hooks/use-billing", () => ({
  useProjectBilling: () => ({ data: { checkoutTarget: mockProjectBilling.target } }),
  useValidateAccessCode: () => ({
    mutateAsync: billing.validateAccessCode,
    isPending: false,
  }),
  // 对话框统一入口——账户/项目路由在 vars.projectId 上断言（真实 hook 内分流）
  useCheckoutTopup: () => ({
    mutateAsync: billing.topup,
    isPending: false,
  }),
  useMyBilling: () => ({ data: undefined }),
}));

billing.topup.mockImplementation((vars: { projectId: string | null }) =>
  Promise.resolve({
    pool: { poolId: "8012345678901", balance: 20_000 },
    entryId: 8012345678902,
    __projectId: vars.projectId,
  }),
);

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  mockProjectBilling.target = 2;
  useCheckoutStore.setState({ open: false, projectId: null, onDone: null });
});

function renderDialog(projectId: string | null = null) {
  useCheckoutStore.setState({ open: true, projectId });
  return render(<CheckoutDialog />);
}

/** 走完「码→金额」两步进入确认页。 */
async function walkToConfirm(amountLabel: string) {
  billing.validateAccessCode.mockResolvedValueOnce(undefined);
  fireEvent.change(screen.getByLabelText("充值准入码"), { target: { value: "DEMO-2026" } });
  fireEvent.click(screen.getByRole("button", { name: /验证并选择金额/ }));
  await screen.findByText("自定义金额（元）");
  fireEvent.click(screen.getByRole("button", { name: amountLabel }));
  fireEvent.click(screen.getByRole("button", { name: "去支付" }));
  await screen.findByText("模拟支付");
}

describe("CheckoutDialog · 模拟收银台（#534 计费④）", () => {
  it("准入码错误被拦并如实引导（BILL_019 message 指路），正确码才进金额选择", async () => {
    billing.validateAccessCode.mockRejectedValueOnce(
      new ApiError({
        status: 400,
        code: 8019,
        message: "充值准入码不正确（无码用户请联系平台索取）",
      }),
    );
    renderDialog();

    fireEvent.change(screen.getByLabelText("充值准入码"), { target: { value: "WRONG" } });
    fireEvent.click(screen.getByRole("button", { name: /验证并选择金额/ }));

    await waitFor(() => {
      expect(screen.getByRole("alert").textContent).toContain("联系平台");
    });
    expect(screen.queryByText("自定义金额（元）")).toBeNull(); // 被拦不进金额步
    expect(billing.validateAccessCode).toHaveBeenCalledWith("WRONG");
  });

  it("账户上下文全流程：预设档→模拟通道确认页（徽标）→即时到账回执", async () => {
    renderDialog(null);

    await walkToConfirm("¥200.00");

    // 模拟通道确认页：金额＋目标池＋「模拟支付」徽标（Dialog 走 portal——document 面查）
    expect(document.querySelector("[data-checkout-confirm]")).toBeTruthy();
    expect(screen.getByText("模拟支付")).toBeTruthy();
    expect(screen.getByText("我的账户（账户余额）")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: /确认支付 ¥200.00/ }));

    await waitFor(() => {
      expect(screen.getByText(/已到账 ¥200.00/)).toBeTruthy();
    });
    const balance = document.querySelector("[data-checkout-done] .font-semibold");
    expect(balance?.textContent).toBe("¥200.00");
    // 金额分落账（预设档 200 元 = 20000 分）＋码随行
    expect(billing.topup).toHaveBeenCalledWith(
      expect.objectContaining({ projectId: null, amountFen: 20_000, accessCode: "DEMO-2026" }),
    );
  });

  it("项目上下文（项目源）：目标池＝本项目余额，充值走项目上下文", async () => {
    renderDialog("p-1");

    await walkToConfirm("¥50.00");

    expect(screen.getByText("本项目余额")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /确认支付 ¥50.00/ }));

    await waitFor(() => {
      expect(screen.getByText(/已到账 ¥50.00/)).toBeTruthy();
    });
    expect(billing.topup).toHaveBeenCalledWith(
      expect.objectContaining({ projectId: "p-1", amountFen: 5_000 }),
    );
  });

  it("项目上下文（账户源）：引导充我的账户——非源池选项不呈现、走账户端点", async () => {
    mockProjectBilling.target = 1;
    renderDialog("p-2");

    await walkToConfirm("¥500.00");

    expect(screen.getByText("我的账户（该项目的扣减来源）")).toBeTruthy();
    expect(screen.queryByText("本项目余额")).toBeNull(); // 死钱选项不呈现

    fireEvent.click(screen.getByRole("button", { name: /确认支付 ¥500.00/ }));
    await waitFor(() => {
      // UI 说我的账户 ⇒ 请求打账户端点（projectId 归 null——不碰 BILL_020）
      expect(billing.topup).toHaveBeenCalledWith(
        expect.objectContaining({ projectId: null, amountFen: 50_000 }),
      );
    });
  });

  it("自定义金额：元输入换算分落账", async () => {
    renderDialog(null);
    billing.validateAccessCode.mockResolvedValueOnce(undefined);
    fireEvent.change(screen.getByLabelText("充值准入码"), { target: { value: "DEMO-2026" } });
    fireEvent.click(screen.getByRole("button", { name: /验证并选择金额/ }));
    await screen.findByText("自定义金额（元）");
    fireEvent.change(screen.getByLabelText("自定义金额（元）"), { target: { value: "123.45" } });
    fireEvent.click(screen.getByRole("button", { name: "去支付" }));
    await screen.findByText("模拟支付");

    fireEvent.click(screen.getByRole("button", { name: /确认支付 ¥123.45/ }));
    await waitFor(() => {
      expect(billing.topup).toHaveBeenCalledWith(
        expect.objectContaining({ amountFen: 12_345 }),
      );
    });
  });

  it("到账续跑回调（#544 引导流「到账即翻转」）：携 onDone 开门，支付成功即触发", async () => {
    const onDone = vi.fn();
    useCheckoutStore.setState({ open: true, projectId: "p-1", onDone });
    render(<CheckoutDialog />);

    await walkToConfirm("¥50.00");
    fireEvent.click(screen.getByRole("button", { name: /确认支付 ¥50.00/ }));

    await waitFor(() => {
      expect(screen.getByText(/已到账 ¥50.00/)).toBeTruthy();
    });
    expect(onDone).toHaveBeenCalledTimes(1); // 到账即续跑（settle 重判翻转，免重走选择）
  });

  it("中途关闭（取消/放弃充值）不触发 onDone——卡留岔路口，随时可回", async () => {
    const onDone = vi.fn();
    useCheckoutStore.setState({ open: true, projectId: "p-1", onDone });
    render(<CheckoutDialog />);

    await walkToConfirm("¥50.00");
    fireEvent.click(screen.getByRole("button", { name: "取消" }));

    expect(onDone).not.toHaveBeenCalled();
  });
});
