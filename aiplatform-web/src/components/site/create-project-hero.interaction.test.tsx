// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ENTRY_MODE } from "@/lib/modes";
import { useEntryModeStore } from "@/lib/store/entry-mode";

import { CreateProjectHero } from "./create-project-hero";

/**
 * 首页对谈入口的接线契约（#76 AC；#299 入口两档，ADR-0029）：示例 chips 一点
 * 即填、模板卡填入对应需求句、一句话发起建项目（POST /api/projects → 直进项目
 * 页）；「先做设计」次入口就地切换上下文（主标/placeholder/chips 随切换）、可
 * 切回，设计态提交载荷带终点初值。
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
  // 分流态归缺省做系统（store 是模块级单例，用例间不串档）
  useEntryModeStore.setState({ mode: ENTRY_MODE.system });
});
afterEach(() => cleanup());

function input(): HTMLTextAreaElement {
  return screen.getByPlaceholderText("一句话说说你想做什么…") as HTMLTextAreaElement;
}

function designInput(): HTMLTextAreaElement {
  return screen.getByPlaceholderText("一句话说说你想要的设计…（logo、海报、页面样式…）") as HTMLTextAreaElement;
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

  it("默认系统主线零变化（一等断言）：不触切换件直接提交，载荷不含终点字段", () => {
    render(<CreateProjectHero />);
    expect(screen.getByText("想做什么，直接说")).toBeTruthy();
    expect(screen.queryByText("想要什么样子，直接说")).toBeNull();

    fireEvent.change(input(), { target: { value: "做个预约页" } });
    fireEvent.keyDown(input(), { key: "Enter", shiftKey: false });
    expect(mutate).toHaveBeenCalledWith({ requirement: "做个预约页" });
  });
});

describe("CreateProjectHero · 入口两档（#299，ADR-0029）", () => {
  it("「先做设计」可见可点、就地切换：主标/placeholder/示例 chips 随切换（不跳页不弹层）", () => {
    render(<CreateProjectHero />);

    // 次入口不点开就可见（轻量显式）
    fireEvent.click(screen.getByRole("button", { name: "先做设计" }));

    // 就地切换：同一页面内文案集换轨（无路由跳转）
    expect(screen.getByText("想要什么样子，直接说")).toBeTruthy();
    expect(designInput()).toBeTruthy();
    expect(screen.getByRole("button", { name: "给我的咖啡店设计一个 logo" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: "帮我的花店做个能下单的小程序" })).toBeNull();
    expect(push).not.toHaveBeenCalled();
  });

  it("可切回：设计态点「先做系统」回系统态文案", () => {
    render(<CreateProjectHero />);
    fireEvent.click(screen.getByRole("button", { name: "先做设计" }));
    fireEvent.click(screen.getByRole("button", { name: "先做系统" }));

    expect(screen.getByText("想做什么，直接说")).toBeTruthy();
    expect(input()).toBeTruthy();
  });

  it("设计态提交带终点初值入项目：载荷携 endpointType=1", () => {
    render(<CreateProjectHero />);
    fireEvent.click(screen.getByRole("button", { name: "先做设计" }));

    fireEvent.change(designInput(), { target: { value: "给我的咖啡店设计一个 logo" } });
    fireEvent.keyDown(designInput(), { key: "Enter", shiftKey: false });

    expect(mutate).toHaveBeenCalledWith({
      requirement: "给我的咖啡店设计一个 logo",
      endpointType: 1,
    });
    expect(push).toHaveBeenCalledWith("/projects/p9");
  });

  it("设计态模板卡填入设计向需求句", () => {
    render(<CreateProjectHero />);
    fireEvent.click(screen.getByRole("button", { name: "先做设计" }));

    fireEvent.click(screen.getByRole("button", { name: /logo 设计/ }));
    expect(designInput().value).toBe("给我的咖啡店设计一个 logo");
  });

  it("发送框类型下拉与切换件状态同源：下拉选「做设计」＝切换件同效", () => {
    render(<CreateProjectHero />);

    fireEvent.click(screen.getByRole("button", { name: /做系统/ }));
    fireEvent.click(screen.getByRole("menuitem", { name: "做设计" }));

    expect(screen.getByText("想要什么样子，直接说")).toBeTruthy();
    // 切换件随档翻面（设计态显「先做系统」切回）
    expect(screen.getByRole("button", { name: "先做系统" })).toBeTruthy();
  });
});
