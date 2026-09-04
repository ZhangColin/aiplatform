// @vitest-environment happy-dom

import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { AccountMenu } from "./account-menu";

// useMe 走 TanStack Query，桩掉避免拉 query client
vi.mock("@/hooks/use-me", () => ({
  useMe: () => ({ data: { displayName: "测试用户" } }),
}));

const setTheme = vi.fn();
vi.mock("next-themes", () => ({
  useTheme: () => ({ theme: "light", setTheme }),
}));

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  setTheme.mockClear();
});

describe("AccountMenu（#76 个人菜单）", () => {
  it("登出项 = 原生 submit button，且不触发 Base UI nativeButton 告警", async () => {
    const errors: string[] = [];
    vi.spyOn(console, "error").mockImplementation((...args: unknown[]) => {
      errors.push(args.map(String).join(" "));
    });

    render(<AccountMenu />);
    fireEvent.click(screen.getByRole("button", { name: "个人菜单" }));

    const logout = await screen.findByRole("button", { name: /退出登录/ });
    expect(logout.tagName).toBe("BUTTON");
    expect(logout.getAttribute("type")).toBe("submit");
    expect(errors.filter((m) => m.includes("nativeButton"))).toEqual([]);
  });

  it("主题三态真切换：点「深色」走 setTheme（next-themes localStorage 持久）", async () => {
    render(<AccountMenu />);
    fireEvent.click(screen.getByRole("button", { name: "个人菜单" }));

    fireEvent.click(await screen.findByRole("button", { name: /深色/ }));
    expect(setTheme).toHaveBeenCalledWith("dark");
    fireEvent.click(screen.getByRole("button", { name: /跟随系统/ }));
    expect(setTheme).toHaveBeenCalledWith("system");
    fireEvent.click(screen.getByRole("button", { name: /^浅色/ }));
    expect(setTheme).toHaveBeenCalledWith("light");
  });
});
