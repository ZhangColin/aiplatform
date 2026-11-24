// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { toast } from "sonner";

import { useCheckoutStore } from "@/lib/store/checkout";
import { useM01Store } from "@/lib/store/m01";

import { ForkCard } from "./fork-card";

// 生成端点（既有 POST /generate）的 mutation——点火动作的路由断言归本文件
const generateState = vi.hoisted(() => ({ mutate: vi.fn(), isPending: false }));
vi.mock("@/hooks/use-start-generation", () => ({
  useStartGeneration: () => generateState,
}));

// 「继续讨论」引导流 mutation（#544——POST /fork/continue-discussion）＋登录态
const guideState = vi.hoisted(() => ({
  mutate: vi.fn(),
  isPending: false,
  /** 服务端判定回执（用例按场景设定：翻转/引导充值） */
  result: { flipped: true, rechargeRequired: false } as {
    flipped: boolean;
    rechargeRequired: boolean;
  },
  loggedIn: true,
}));
vi.mock("@/hooks/use-continue-discussion", () => ({
  useContinueDiscussion: () => guideState,
}));
vi.mock("@/hooks/use-me", () => ({
  useMe: () => ({ data: guideState.loggedIn ? { id: "u1" } : null }),
}));

/** mutate mock：按当前 result 走 onSuccess 分岔（编排断言的驱动面）。 */
function guideMock() {
  guideState.mutate.mockImplementation((_vars: unknown, opts?: { onSuccess?: (r: unknown) => void }) => {
    opts?.onSuccess?.(guideState.result);
  });
}

/**
 * 岔路口三选卡的动作（#542「立即生成」＋#544「继续讨论」引导流，ADR-0048——
 * 翻转门①交互时序，三选上卡压平版）：付费两选＝引导入口——「由我的账户付费」
 * （访客先 M01 登录＋项目平移〔登录编排〕，账户有余额直翻转、无余额引导充值）、
 * 「由本项目付费」（后台已充项目池免登录直翻转；试用项目池必空→引导充值）；
 * 到账经收银台 onDone 回调 settle 重判（空 source）即翻转、无需重走选择；中途
 * 放弃＝卡留岔路口、随时改选「立即生成」。
 */
describe("ForkCard · 引导流分岔与 M01 拉起时序（#544）", () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
    useM01Store.getState().closeM01();
    useCheckoutStore.getState().closeCheckout();
    guideState.loggedIn = true;
    guideState.result = { flipped: true, rechargeRequired: false };
  });

  it("「立即生成」触发既有生成端点 mutation（首建点火，#542 单出口不动）", () => {
    render(<ForkCard projectId="p1" />);

    fireEvent.click(screen.getByRole("button", { name: /立即生成/ }));

    expect(generateState.mutate).toHaveBeenCalledTimes(1);
    expect(generateState.mutate).toHaveBeenCalledWith(undefined);
  });

  it("账户有余额：选「由我的账户付费」直翻转回访谈（已登录，一次命令）", () => {
    const success = vi.spyOn(toast, "success");
    guideMock();
    guideState.result = { flipped: true, rechargeRequired: false };
    render(<ForkCard projectId="p1" />);

    fireEvent.click(screen.getByTestId("fork-continue-account"));

    expect(guideState.mutate).toHaveBeenCalledTimes(1);
    expect(guideState.mutate).toHaveBeenCalledWith(
      { source: 1 },
      expect.objectContaining({ onSuccess: expect.any(Function) }),
    );
    // 翻转回执：成功提示（卡随 build-gate 读面熄灭归装配层）
    expect(success).toHaveBeenCalledWith(expect.stringContaining("已转为付费"));
    // 不拉 M01、不开收银台
    expect(useM01Store.getState().open).toBe(false);
    expect(useCheckoutStore.getState().open).toBe(false);
  });

  it("M01 拉起时序：访客（未登录）选账户付费先登录，验码成功 resume 才发引导命令", () => {
    guideState.loggedIn = false;
    guideMock();
    render(<ForkCard projectId="p1" />);

    fireEvent.click(screen.getByTestId("fork-continue-account"));

    // 先拉 M01（fork-login 来路），命令未发——登录＋项目平移归登录编排
    expect(useM01Store.getState().open).toBe(true);
    expect(useM01Store.getState().trigger).toBe("fork-login");
    expect(guideState.mutate).not.toHaveBeenCalled();

    // 验码成功（m01-dialog 编排）→ resume 续跑：平移完成后发引导命令
    useM01Store.getState().resume?.();
    expect(guideState.mutate).toHaveBeenCalledTimes(1);
    expect(guideState.mutate).toHaveBeenCalledWith(
      { source: 1 },
      expect.objectContaining({ onSuccess: expect.any(Function) }),
    );
  });

  it("账户无余额：引导充值——开收银台（源已选定），到账 onDone 即 settle 重判翻转", () => {
    guideMock();
    guideState.result = { flipped: false, rechargeRequired: true };
    render(<ForkCard projectId="p1" />);

    fireEvent.click(screen.getByTestId("fork-continue-account"));

    // 引导充值支：收银台开（项目上下文——只充当前扣减源池），未登录先行 M01 不动
    expect(useCheckoutStore.getState().open).toBe(true);
    expect(useCheckoutStore.getState().projectId).toBe("p1");
    expect(useM01Store.getState().open).toBe(false);
    expect(guideState.mutate).toHaveBeenCalledTimes(1);

    // 到账（收银台回执）→ onDone 回调 settle：空 source 重判，无需重走选择
    guideState.result = { flipped: true, rechargeRequired: false };
    useCheckoutStore.getState().onDone?.();
    expect(guideState.mutate).toHaveBeenCalledTimes(2);
    expect(guideState.mutate).toHaveBeenLastCalledWith(
      {},
      expect.objectContaining({ onSuccess: expect.any(Function) }),
    );
  });

  it("本项目付费池必空（访客）：先 M01 登录，resume 开收银台，到账 settle 翻转", () => {
    guideState.loggedIn = false;
    guideMock();
    guideState.result = { flipped: false, rechargeRequired: true };
    render(<ForkCard projectId="p1" />);

    fireEvent.click(screen.getByTestId("fork-continue-project"));

    // 源随选择一次选定（source=2）→ 池空引导充值；访客须先登录（收银台准入）
    expect(guideState.mutate).toHaveBeenCalledWith(
      { source: 2 },
      expect.objectContaining({ onSuccess: expect.any(Function) }),
    );
    expect(useM01Store.getState().open).toBe(true);
    expect(useM01Store.getState().trigger).toBe("fork-login");
    expect(useCheckoutStore.getState().open).toBe(false);

    // 登录成功 resume → 重开收银台（projectId 上下文）
    useM01Store.getState().resume?.();
    expect(useCheckoutStore.getState().open).toBe(true);
    expect(useCheckoutStore.getState().projectId).toBe("p1");

    // 到账 settle → 翻转
    guideState.result = { flipped: true, rechargeRequired: false };
    useCheckoutStore.getState().onDone?.();
    expect(guideState.mutate).toHaveBeenLastCalledWith(
      {},
      expect.objectContaining({ onSuccess: expect.any(Function) }),
    );
  });

  it("本项目付费已充（后台入账即选源）：一次命令免登录直翻转（销售演示动线）", () => {
    guideMock();
    guideState.result = { flipped: true, rechargeRequired: false };
    render(<ForkCard projectId="p1" />);

    fireEvent.click(screen.getByTestId("fork-continue-project"));

    expect(guideState.mutate).toHaveBeenCalledTimes(1);
    expect(guideState.mutate).toHaveBeenCalledWith(
      { source: 2 },
      expect.objectContaining({ onSuccess: expect.any(Function) }),
    );
    expect(useM01Store.getState().open).toBe(false);
    expect(useCheckoutStore.getState().open).toBe(false);
  });

  it("中途放弃：引导充值支关闭收银台后卡仍在（留岔路口），随时改选「立即生成」", () => {
    guideMock();
    guideState.result = { flipped: false, rechargeRequired: true };
    render(<ForkCard projectId="p1" />);

    fireEvent.click(screen.getByTestId("fork-continue-account"));
    expect(useCheckoutStore.getState().open).toBe(true);

    // 用户关闭收银台（放弃充值）——无 settle、卡内容不变（三选仍在，可改选立即生成）
    useCheckoutStore.getState().closeCheckout();
    expect(guideState.mutate).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId("fork-continue-account")).toBeTruthy();
    expect(screen.getByTestId("fork-continue-project")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: /立即生成/ }));
    expect(generateState.mutate).toHaveBeenCalledTimes(1);
  });

  it("「查看 PRD」回调（PRD 先回看，#542 不动）", () => {
    const seePrd = vi.fn();
    render(<ForkCard projectId="p1" onSeePrd={seePrd} />);

    fireEvent.click(screen.getByRole("button", { name: /查看 PRD/ }));

    expect(seePrd).toHaveBeenCalledTimes(1);
  });

  it("到账自动翻转（autoSettle 信号）：他处到账后卡回读即 settle，无需点击与重走选择", () => {
    // 灵魂例（code-review 处置——「自动」不止收银台 onDone 单缝）：forkSettleDue
    // 在场（卡∧源已选∧池正）→ 挂载即空 source 重判一次
    guideMock();
    guideState.result = { flipped: true, rechargeRequired: false };
    render(<ForkCard projectId="p1" autoSettle />);

    expect(guideState.mutate).toHaveBeenCalledTimes(1);
    expect(guideState.mutate).toHaveBeenCalledWith(
      {},
      expect.objectContaining({ onSuccess: expect.any(Function) }),
    );
  });

  it("无 autoSettle 信号不自动发命令（源未定/池未回正常态零噪音）", () => {
    guideMock();
    render(<ForkCard projectId="p1" />);

    expect(guideState.mutate).not.toHaveBeenCalled();
  });
});
