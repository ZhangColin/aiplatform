// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ForkCard } from "./fork-card";

// 生成端点（既有 POST /generate）的 mutation——点火动作的路由断言归本文件
const generateState = vi.hoisted(() => ({ mutate: vi.fn(), isPending: false }));
vi.mock("@/hooks/use-resume-generation", () => ({
  useResumeGeneration: () => generateState,
}));

/**
 * 岔路口三选卡的点火动作（#542 访谈②）：「立即生成」＝触发首个构建派发
 * （复用既有 POST /generate mutation——立即生成与「继续生成」同单出口）；
 * 查看 PRD 回调（PRD 先回看）。
 */
describe("ForkCard · 「立即生成」点火与 PRD 回看（#542）", () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it("「立即生成」触发既有生成端点 mutation（首建点火）", () => {
    render(<ForkCard projectId="p1" />);

    fireEvent.click(screen.getByRole("button", { name: /立即生成/ }));

    expect(generateState.mutate).toHaveBeenCalledTimes(1);
    expect(generateState.mutate).toHaveBeenCalledWith(undefined);
  });

  it("付费两选占位态不可点火（点击无派发）", () => {
    render(<ForkCard projectId="p1" />);

    // 占位行无按钮（aria-disabled 行；不触发生成）
    const accountRow = screen
      .getByText("继续讨论（由我的账户付费）")
      .closest("[data-fork-choice]");
    expect(accountRow?.getAttribute("aria-disabled")).toBe("true");
    expect(generateState.mutate).not.toHaveBeenCalled();
  });

  it("「查看 PRD」回调（PRD 先回看）", () => {
    const seePrd = vi.fn();
    render(<ForkCard projectId="p1" onSeePrd={seePrd} />);

    fireEvent.click(screen.getByRole("button", { name: /查看 PRD/ }));

    expect(seePrd).toHaveBeenCalledTimes(1);
  });
});
