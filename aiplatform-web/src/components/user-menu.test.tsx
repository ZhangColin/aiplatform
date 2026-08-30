// @vitest-environment happy-dom

import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { UserMenu } from "./user-menu";

// useMe 走 TanStack Query，桩掉避免拉 query client；UserMenu 只读 displayName
vi.mock("@/hooks/use-me", () => ({
  useMe: () => ({ data: { displayName: "测试用户" } }),
}));

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("UserMenu", () => {
  it("登出项 = 原生 submit button，且不触发 Base UI nativeButton 告警", async () => {
    const errors: string[] = [];
    vi.spyOn(console, "error").mockImplementation((...args: unknown[]) => {
      errors.push(args.map(String).join(" "));
    });

    render(<UserMenu />);

    // 打开菜单：触发器 = aria-label「用户菜单」的原生 <button>
    fireEvent.click(screen.getByRole("button", { name: "用户菜单" }));

    // 登出项在菜单里以 role=menuitem 呈现，底层须是原生 <button type=submit>
    // （render 原生 submit button：菜单关闭后原生 form POST 照常走）
    const logout = await screen.findByRole("menuitem", { name: /退出登录/ });
    expect(logout.tagName).toBe("BUTTON");
    expect(logout.getAttribute("type")).toBe("submit");

    // 回归锁：render 真 <button> 未配 nativeButton 时，Base UI dev 期会 console.error 告警
    expect(errors.filter((m) => m.includes("nativeButton"))).toEqual([]);
  });
});
