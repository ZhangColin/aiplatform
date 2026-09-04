// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { CreateProjectHero } from "./create-project-hero";

/**
 * 首页对谈入口的接线契约（#76 AC）：示例 chips 一点即填、模板卡填入对应
 * 需求句、一句话发起建项目（POST /api/projects → 直进项目页）。
 */

const push = vi.fn();
const mutate = vi.fn();

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push }),
}));

vi.mock("@/hooks/use-create-project", () => ({
  useCreateProject: () => ({
    isPending: false,
    mutate: (command: unknown, options: { onSuccess: (r: unknown) => void }) => {
      mutate(command);
      // 成功路径同步回放（真实为 TanStack Query 异步回调）
      options.onSuccess({ project: { id: "p9" } });
    },
  }),
}));

// 侧栏数据源与此处无关（最近项目卡由 SSR 测试覆盖）
vi.mock("@/hooks/use-projects", () => ({
  useRecentProjects: () => [],
}));

beforeEach(() => {
  push.mockClear();
  mutate.mockClear();
});
afterEach(() => cleanup());

function input() {
  return screen.getByPlaceholderText("一句话说说你想做什么…") as HTMLTextAreaElement;
}

describe("CreateProjectHero（首页定稿形态）", () => {
  it("示例 chips 一点即填：点击把需求句填进输入框", () => {
    render(<CreateProjectHero />);
    fireEvent.click(screen.getByRole("button", { name: "帮我的花店做个能下单的小程序" }));
    expect(input().value).toBe("帮我的花店做个能下单的小程序");
  });

  it("模板卡点击填入对应需求句（照着模板开工）", () => {
    render(<CreateProjectHero />);
    fireEvent.click(screen.getByRole("button", { name: /花店小程序/ }));
    expect(input().value).toBe("帮我的花店做个能下单的小程序");
  });

  it("一句话发起建项目：Enter 提交 → POST 载荷 → 直进项目页", () => {
    render(<CreateProjectHero />);
    fireEvent.change(input(), { target: { value: "做一个餐厅扫码点单系统" } });
    fireEvent.keyDown(input(), { key: "Enter", shiftKey: false });

    expect(mutate).toHaveBeenCalledWith({ requirement: "做一个餐厅扫码点单系统" });
    expect(push).toHaveBeenCalledWith("/projects/p9");
  });
});
