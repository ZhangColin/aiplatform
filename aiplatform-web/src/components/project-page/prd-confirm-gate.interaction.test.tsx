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

function renderGate(readiness: PrdReadiness) {
  return render(<PrdConfirmGate projectId="p1" readiness={readiness} />);
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
});
