// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { deliveryStageNav } from "@/lib/projects/delivery-stages";
import { useM01Store } from "@/lib/store/m01";

import { GlobalTopbar } from "./global-topbar";

// 顶栏访客态登录入口的交互契约（#377）：点「登录」开 M01 验证码弹窗（来路
// topbar-login、无续跑回调——与保存项目/签约门的续跑语义区分），全程不离开
// 平台（渲染面不出 OIDC 302 目标）；已登录态照旧出账号菜单、无登录入口。

const meSeed = vi.hoisted(() => ({ me: undefined as object | undefined }));
vi.mock("next/navigation", () => ({ usePathname: () => "/projects/p1" }));
vi.mock("@/hooks/use-me", () => ({
  useMe: () => ({ data: meSeed.me, isPending: false }),
}));

/** 门全关（与渲染契约测试同投影）：仅需求分析可达。 */
const nav = deliveryStageNav("p1", {
  prdConfirmed: false,
  demoConfirmed: false,
  orderPaid: false,
});

afterEach(() => {
  cleanup();
  meSeed.me = undefined;
  useM01Store.setState({ open: false, trigger: null, resume: null });
});

function mountTopbar() {
  return render(<GlobalTopbar projectId="p1" nav={nav} onOpenNav={() => {}} />);
}

describe("GlobalTopbar · 访客态登录入口切 M01 弹窗（#377）", () => {
  it("点「登录」开 M01：来路 topbar-login、不带续跑回调", () => {
    mountTopbar();
    expect(useM01Store.getState().open).toBe(false);

    fireEvent.click(screen.getByRole("button", { name: "登录" }));

    expect(useM01Store.getState().open).toBe(true);
    expect(useM01Store.getState().trigger).toBe("topbar-login");
    expect(useM01Store.getState().resume).toBeNull(); // 无续跑语义（区别于签约门）
  });

  it("登录入口非链接：不出 OIDC 302 目标（/auth/login）", () => {
    mountTopbar();
    expect(screen.getByText("登录").closest("a")).toBeNull();
  });

  it("已登录态现状不变：账号菜单在位、无登录入口", () => {
    meSeed.me = { displayName: "测试用户" };
    mountTopbar();
    expect(screen.queryByRole("button", { name: "登录" })).toBeNull();
    expect(screen.getByLabelText("个人菜单")).toBeTruthy();
  });
});
