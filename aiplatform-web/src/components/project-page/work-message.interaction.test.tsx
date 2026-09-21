// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";

import type { WorkPart, WorkSnapshot } from "@/lib/store/work-message";

import { WorkMessage } from "./work-message";

function action(overrides: Partial<Extract<WorkPart, { kind: "action" }>> = {}) {
  return {
    kind: "action",
    id: "a1",
    toolCallId: "t1",
    toolName: "write_file",
    state: "completed",
    label: "编写【A】",
    ...overrides,
  } satisfies Extract<WorkPart, { kind: "action" }>;
}

function renderMessage(parts: WorkPart[], overrides: Partial<WorkSnapshot> = {}) {
  const snapshot: WorkSnapshot = { runId: "r1", frozen: false, parts, ...overrides };
  return render(<WorkMessage work={snapshot} />);
}

afterEach(cleanup);

/** 长流水样例（#230 成功无痕后）：更早区（解说 + 失败痕）+ 尾部（解说 + 当前动作行）。 */
function longFlow(): WorkPart[] {
  return [
    { kind: "text", id: "t0", text: "开工解说。" },
    action({ id: "a1", toolCallId: "t1", label: "编写【A】" }),
    action({ id: "a2", toolCallId: "t2", toolName: "execute", state: "failed", label: "执行【B】" }),
    { kind: "text", id: "t1", text: "过渡解说一。" },
    { kind: "text", id: "t2", text: "过渡解说二。" },
    { kind: "text", id: "t3", text: "最新解说。" },
    action({ id: "a4", toolCallId: "t4", state: "running", label: "执行【D】" }),
  ];
}

describe("WorkMessage · 更早区展开回看交互（#225 混合坍缩 + #230 成功无痕）", () => {
  it("默认坍缩：更早解说藏进「更早 N 项」，失败红行破例常驻（story9），成功动作无痕（#230）", () => {
    renderMessage(longFlow());

    // 坍缩行只数解说段（失败痕不进计数）
    expect(screen.getByText(/更早 2 项/)).toBeTruthy();
    expect(screen.queryByText("开工解说。")).toBeNull(); // 更早解说不逐条出（坍缩控噪）
    expect(screen.queryByText("过渡解说一。")).toBeNull();
    // 失败红行不埋进坍缩行——常驻展开红显（#225 破例语义承接）
    expect(screen.getByText("执行【B】")).toBeTruthy();
    expect(screen.getByText("没做成")).toBeTruthy();
    // 当前动作行常驻（story7）
    expect(screen.getByText("执行【D】")).toBeTruthy();
    // 成功动作沉没——未展开也不在（滤除，不是坍缩）
    expect(screen.queryByText("编写【A】")).toBeNull();
  });

  it("点击展开回看：读到的是解说段与失败痕（story4/5/6——事件不裁剪仅呈现坍缩），展开亦无成功残骸", () => {
    renderMessage(longFlow());

    fireEvent.click(screen.getByRole("button", { name: /更早/ }));
    expect(screen.getByText("开工解说。")).toBeTruthy();
    expect(screen.getByText("过渡解说一。")).toBeTruthy();
    expect(screen.getByText("执行【B】")).toBeTruthy(); // 失败痕回看仍在（事故不消失）
    expect(screen.queryByText("编写【A】")).toBeNull(); // 成功沉没与坍缩无关——展开也不出
  });

  it("定格（正常收工）：坍缩形态保持（story12 无跳变）、成功/截断动作无残骸（#230 story16），仍可展开回看", () => {
    renderMessage(longFlow(), { frozen: true });

    expect(screen.getByText(/更早 1 项/)).toBeTruthy();
    expect(screen.queryByText("执行【D】")).toBeNull(); // 定格截断的未终态动作沉没
    expect(screen.queryByText("编写【A】")).toBeNull(); // 成功动作沉没
    expect(screen.getByText("执行【B】")).toBeTruthy(); // 失败红行留驻
    expect(screen.getByText("最新解说。")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: /更早/ }));
    expect(screen.getByText("过渡解说一。")).toBeTruthy(); // 回看叙事仍在
  });
});
