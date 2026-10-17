// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { HomeHero } from "./home-hero";

/**
 * 首页 Hero 接线契约（#345，spec #320 ⑥ 首页 P01 照原型）：示例 chip 一点即填
 * 全文、空输入 CTA 不可用、提交中防重复、一句话提交建项目（POST /api/projects，
 * 主链路载荷零变化、无终点字段）直进项目空间新壳；草稿恢复横幅取真会话数据
 * （有效访客/账号会话最近未归档项目，无会话不呈现）；模式切换 pill 已删（回单
 * 档呈现，PLATFORM_MODES 机制保留在 lib/modes）。
 */

const push = vi.fn();
const mutate = vi.fn();
const state = vi.hoisted(() => ({
  pending: false,
  projects: [] as { id: string; name: string; updatedAt?: string }[],
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ push }) }));
vi.mock("@/hooks/use-create-project", () => ({
  useCreateProject: () => ({
    isPending: state.pending,
    mutate: (command: unknown, options: { onSuccess: (r: unknown) => void }) => {
      mutate(command);
      options.onSuccess({ project: { id: "p9" } });
    },
  }),
}));
vi.mock("@/hooks/use-projects", () => ({ useRecentProjects: () => state.projects }));

beforeEach(() => {
  push.mockClear();
  mutate.mockClear();
  state.pending = false;
  state.projects = [];
});
afterEach(() => cleanup());

function input(): HTMLTextAreaElement {
  return screen.getByPlaceholderText(/说说你想解决的问题/) as HTMLTextAreaElement;
}
function cta(): HTMLButtonElement {
  return screen.getByRole("button", { name: /开始梳理需求/ }) as HTMLButtonElement;
}

describe("HomeHero（首页 P01 照原型）", () => {
  it("示例 chip 点击填入全文：点「做一个预约系统」填入原型 fillPrompt 完整需求句", () => {
    render(<HomeHero />);
    fireEvent.click(screen.getByRole("button", { name: /做一个预约系统/ }));
    expect(input().value).toBe(
      "做一个移动端客户预约系统，支持顾问日历排期、客户时段预约、短信通知与现场二维码核销",
    );
  });

  it("示例 chip 点击填入全文：点「把表格流程做成工具」同样填入完整需求句", () => {
    render(<HomeHero />);
    fireEvent.click(screen.getByRole("button", { name: /把表格流程做成工具/ }));
    expect(input().value).toBe(
      "把我们目前的Excel分散销售统计与审批流重构为一套结构化企业内部协作系统，包含权限分配和实时数据报表",
    );
  });

  it("空输入发送不可用：CTA 禁用，点不触发建项目", () => {
    render(<HomeHero />);
    expect(cta().disabled).toBe(true);
    fireEvent.click(cta());
    expect(mutate).not.toHaveBeenCalled();
  });

  it("一句话提交建项目：Enter → POST 载荷（主链路零变化，不含终点字段）→ 直进项目空间新壳", () => {
    render(<HomeHero />);
    fireEvent.change(input(), { target: { value: "做一个餐厅扫码点单系统" } });
    fireEvent.keyDown(input(), { key: "Enter", shiftKey: false });

    expect(mutate).toHaveBeenCalledWith({ requirement: "做一个餐厅扫码点单系统" });
    expect(push).toHaveBeenCalledWith("/projects/p9");
  });

  it("提交中防重复：pending 时 CTA 禁用，点击不重复发起", () => {
    state.pending = true;
    render(<HomeHero />);
    fireEvent.change(input(), { target: { value: "做个预约页" } });
    expect(cta().disabled).toBe(true);
    fireEvent.click(cta());
    expect(mutate).not.toHaveBeenCalled();
  });

  it("草稿恢复横幅真会话数据：有最近项目 → 项目名在场、「继续梳理」指到该项目", () => {
    state.projects = [{ id: "p1", name: "企业内部CRM与智能工单管理系统" }];
    render(<HomeHero />);
    expect(screen.getByText("企业内部CRM与智能工单管理系统")).toBeTruthy();
    const link = screen.getByRole("link", { name: /继续梳理/ });
    expect(link.getAttribute("href")).toBe("/projects/p1");
  });

  it("无会话不呈现：无最近项目 → 无草稿恢复横幅", () => {
    render(<HomeHero />);
    expect(screen.queryByText("草稿恢复")).toBeNull();
    expect(screen.queryByText("继续梳理")).toBeNull();
  });

  it("模式切换 pill 已删：无「先做设计」次入口（回单档呈现，PLATFORM_MODES 机制保留）", () => {
    render(<HomeHero />);
    expect(screen.queryByRole("button", { name: "先做设计" })).toBeNull();
    expect(screen.queryByRole("button", { name: "先做系统" })).toBeNull();
  });
});
