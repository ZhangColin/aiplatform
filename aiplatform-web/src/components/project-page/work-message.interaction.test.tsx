// @vitest-environment happy-dom
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
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

function renderMessage(parts: WorkPart[]) {
  const snapshot: WorkSnapshot = { runId: "r1", frozen: false, parts };
  const client = new QueryClient({ defaultOptions: { mutations: { gcTime: 0 } } });
  return render(
    <QueryClientProvider client={client}>
      <WorkMessage work={snapshot} projectId="p1" />
    </QueryClientProvider>,
  );
}

afterEach(cleanup);

describe("WorkMessage · 动作组折叠/展开交互（#116）", () => {
  it("默认折叠一行；点击展开见单条动作及状态；再点收起", () => {
    renderMessage([
      action({ id: "a1", toolCallId: "t1", label: "编写【A】" }),
      action({ id: "a2", toolCallId: "t2", toolName: "command", label: "执行【B】", state: "failed" }),
    ]);

    // 默认折叠：只有「N 个动作」一行，单条动作标签不播
    expect(screen.getByText("2 个动作")).toBeTruthy();
    expect(screen.queryByText("编写【A】")).toBeNull();
    expect(screen.queryByText("执行【B】")).toBeNull();

    // 展开：单条动作 + 状态（「没做成」= failed 状态保留）
    fireEvent.click(screen.getByRole("button", { name: /个动作/ }));
    expect(screen.getByText("编写【A】")).toBeTruthy();
    expect(screen.getByText("执行【B】")).toBeTruthy();
    expect(screen.getByText("没做成")).toBeTruthy();

    // 收起：单条动作标签退场
    fireEvent.click(screen.getByRole("button", { name: /个动作/ }));
    expect(screen.queryByText("编写【A】")).toBeNull();
  });

  it("多个动作组各自独立折叠（展开一组不影响另一组）", () => {
    renderMessage([
      action({ id: "a1", toolCallId: "t1", label: "编写【A】" }),
      action({ id: "a2", toolCallId: "t2", label: "执行【B】" }),
      { kind: "text", id: "t1", text: "解说段" },
      action({ id: "a3", toolCallId: "t3", label: "编写【C】" }),
      action({ id: "a4", toolCallId: "t4", label: "执行【D】" }),
    ]);

    const buttons = screen.getAllByRole("button", { name: /个动作/ });
    expect(buttons).toHaveLength(2);

    fireEvent.click(buttons[0]);
    expect(screen.getByText("编写【A】")).toBeTruthy();
    expect(screen.queryByText("编写【C】")).toBeNull(); // 第二组仍折叠
  });
});
