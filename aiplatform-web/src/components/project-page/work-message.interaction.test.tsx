// @vitest-environment happy-dom
import { cleanup, render, screen } from "@testing-library/react";
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

function renderMessage(
  parts: WorkPart[],
  overrides: Partial<WorkSnapshot> = {},
  closingArrived = false,
) {
  const snapshot: WorkSnapshot = { runId: "r1", frozen: false, parts, ...overrides };
  return render(<WorkMessage work={snapshot} closingArrived={closingArrived} />);
}

afterEach(cleanup);

/** 流水样例（#608 呈现面清场后：正文只余失败痕；在跑动作归活性行）。 */
function flow(): WorkPart[] {
  return [
    action({ id: "a1", toolCallId: "t1", label: "编写【A】" }),
    action({ id: "a2", toolCallId: "t2", toolName: "execute", state: "failed", label: "执行【B】" }),
    action({ id: "a4", toolCallId: "t4", state: "running", label: "执行【D】" }),
  ];
}

describe("WorkMessage · 静态面（#230 成功无痕 + #235 常驻活性行）", () => {
  it("失败红行常驻、成功动作无痕、在跑动作归活性行", () => {
    renderMessage(flow());

    expect(screen.getByText("执行【B】")).toBeTruthy();
    expect(screen.getByText("没做成")).toBeTruthy();
    expect(screen.getByText("执行【D】")).toBeTruthy(); // 活性行
    expect(screen.queryByText("编写【A】")).toBeNull(); // 成功动作沉没
  });

  it("模型文本不呈现（#608：解说部件退役；即使旧数据混入文本部件也不渲染）", () => {
    const stale = [{ kind: "text", id: "t1", text: "过渡解说。" } as unknown as WorkPart];
    renderMessage([...stale, ...flow()]);

    expect(screen.queryByText("过渡解说。")).toBeNull();
    expect(screen.getByText("执行【B】")).toBeTruthy();
  });

  it("定格未入流：失败红行留驻，活性行保留末行（静态）", () => {
    renderMessage(flow(), { frozen: true });

    expect(screen.getByText("执行【D】")).toBeTruthy();
    expect(screen.getByText("执行【B】")).toBeTruthy();
  });

  it("定格且收尾卡入流：活性行随定格沉没——失败红行留驻终形", () => {
    renderMessage(flow(), { frozen: true }, true);

    expect(screen.queryByText("执行【D】")).toBeNull(); // 截断的未终态动作随活性行沉没
    expect(screen.queryByText("编写【A】")).toBeNull();
    expect(screen.getByText("执行【B】")).toBeTruthy();
  });
});
