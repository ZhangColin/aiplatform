// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { components } from "@/lib/api/schema";

import { PrdConfirmGate } from "./prd-confirm-gate";

type PrdReadiness = components["schemas"]["PrdReadinessResponse"];

const mutate = vi.fn();
vi.mock("@/hooks/use-confirm-prd", () => ({
  useConfirmPrd: () => ({ isPending: false, mutate }),
}));

afterEach(() => {
  cleanup();
  mutate.mockReset();
});

function renderGate(readiness: PrdReadiness, changeSummary?: string | null) {
  return render(
    <PrdConfirmGate projectId="p1" readiness={readiness} changeSummary={changeSummary} />,
  );
}

describe("PrdConfirmGate · 两段式摘要层（#332 生成加门）", () => {
  it("点主 CTA 开摘要层（范围摘要＋未解决事项）；「继续」进层内确认段；「确认并生成 Demo」才真 POST", () => {
    renderGate({
      blocking: [],
      minor: [{ dimension: "验收", text: "列表默认排序偏好未确认" }],
      dimensions: [
        { dimension: "目标与角色", status: "ready" },
        { dimension: "验收", status: "minor" },
      ],
    });

    fireEvent.click(screen.getByRole("button", { name: /确认此版 PRD 并生成 Demo/ }));

    // 第一段＝摘要层：维度就绪计数＋未解决事项；此段不派发
    expect(screen.getByText(/已梳理 1\/2 个维度/)).toBeTruthy();
    expect(screen.getByText("列表默认排序偏好未确认")).toBeTruthy();
    expect(mutate).not.toHaveBeenCalled();

    // 第二段＝层内确认：一段最终确认动作才真正触发 POST
    fireEvent.click(screen.getByRole("button", { name: "继续" }));
    fireEvent.click(screen.getByRole("button", { name: /确认并生成 Demo/ }));

    expect(mutate).toHaveBeenCalledTimes(1);
  });

  it("阻碍级缺项在场：「继续」禁用、就地指路（用户不可绕过——须先在对话区补齐）", () => {
    renderGate({
      blocking: [{ dimension: "主流程", text: "【主流程】发货方式未确认" }],
      minor: [],
      dimensions: [{ dimension: "主流程", status: "blocking" }],
    });

    fireEvent.click(screen.getByRole("button", { name: /确认此版 PRD 并生成 Demo/ }));

    expect(screen.getByText("【主流程】发货方式未确认")).toBeTruthy();
    expect(
      screen.getByRole("button", { name: "继续" }).hasAttribute("disabled"),
    ).toBe(true);
    expect(mutate).not.toHaveBeenCalled();
  });

  it("差异摘要在场（#334 改需求回路）：摘要层带「本版更新内容」节——确认前看到这版改了什么；层内确认段如实说「下一版」", () => {
    renderGate(
      { blocking: [], minor: [], dimensions: [] },
      "功能清单新增「管理后台」：管理员可管理商品与订单",
    );

    fireEvent.click(screen.getByRole("button", { name: /确认此版 PRD 并生成 Demo/ }));

    expect(screen.getByText("本版更新内容")).toBeTruthy();
    expect(screen.getByText("功能清单新增「管理后台」：管理员可管理商品与订单")).toBeTruthy();

    // 确认段措辞分野（#334）：停驻草稿确认＝出新版本、按新版生成下一版（非「首个」）
    fireEvent.click(screen.getByRole("button", { name: "继续" }));
    expect(screen.getByText("确认即冻结本版需求为新版本，平台随即按新版生成下一版 Demo。")).toBeTruthy();
  });

  it("无停驻摘要（#334）：摘要层不出「本版更新内容」节（不占形）", () => {
    renderGate({ blocking: [], minor: [], dimensions: [] });

    fireEvent.click(screen.getByRole("button", { name: /确认此版 PRD 并生成 Demo/ }));

    expect(screen.queryByText("本版更新内容")).toBeNull();
  });
});
