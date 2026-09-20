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

function renderMessage(parts: WorkPart[], overrides: Partial<WorkSnapshot> = {}) {
  const snapshot: WorkSnapshot = { runId: "r1", frozen: false, parts, ...overrides };
  const client = new QueryClient({ defaultOptions: { mutations: { gcTime: 0 } } });
  return render(
    <QueryClientProvider client={client}>
      <WorkMessage work={snapshot} />
    </QueryClientProvider>,
  );
}

afterEach(cleanup);

/** 长流水样例：更早区（解说 + 双动作组）+ 尾部（解说 + 在跑动作组 + 解说）。 */
function longFlow(): WorkPart[] {
  return [
    { kind: "text", id: "t0", text: "开工解说。" },
    action({ id: "a1", toolCallId: "t1", label: "编写【A】" }),
    action({ id: "a2", toolCallId: "t2", toolName: "execute", label: "执行【B】" }),
    { kind: "text", id: "t1", text: "过渡解说。" },
    action({ id: "a3", toolCallId: "t3", label: "编写【C】" }),
    action({ id: "a4", toolCallId: "t4", label: "执行【D】" }),
    { kind: "text", id: "t2", text: "最新解说。" },
  ];
}

describe("WorkMessage · 动作组折叠/展开交互（#116 + #225 混合坍缩）", () => {
  it("生长中尾部动作组保持展开（story8「正在做」一眼可分）；点击收起后 forceOpen 下不再折叠", () => {
    renderMessage(longFlow());

    // 尾部组（正在进行的动作组）默认展开：单条动作可见
    expect(screen.getByText("编写【C】")).toBeTruthy();
    expect(screen.getByText("执行【D】")).toBeTruthy();

    // 点击组头：切换用户态——forceOpen 组点击不收（正在进行的组常驻展开）
    fireEvent.click(screen.getByRole("button", { name: /个动作/ }));
    expect(screen.getByText("编写【C】")).toBeTruthy();
  });

  it("更早内容坍缩为「更早 N 项」，点击展开回看完整过程（story4/5/17——事件不裁剪仅呈现坍缩）", () => {
    renderMessage(longFlow());

    // 默认坍缩：更早动作组整组不出场
    expect(screen.getByText(/更早 \d+ 项/)).toBeTruthy();
    expect(screen.queryByText("编写【A】")).toBeNull();
    expect(screen.queryByText("执行【B】")).toBeNull();

    // 点击展开：更早段照常竖流（组折叠行 + 解说）
    fireEvent.click(screen.getByRole("button", { name: /更早/ }));
    expect(screen.getByText("开工解说。")).toBeTruthy();
    expect(screen.getAllByText("2 个动作").length).toBeGreaterThanOrEqual(1);
  });

  it("多个更早动作组各自独立折叠（展开一组不影响另一组）", () => {
    renderMessage([
      action({ id: "a1", toolCallId: "t1", label: "编写【A】" }),
      action({ id: "a2", toolCallId: "t2", label: "执行【B】" }),
      { kind: "text", id: "t1", text: "解说一。" },
      action({ id: "a3", toolCallId: "t3", label: "编写【C】" }),
      action({ id: "a4", toolCallId: "t4", label: "执行【D】" }),
      { kind: "text", id: "t2", text: "解说二。" },
      action({ id: "a5", toolCallId: "t5", label: "编写【E】" }),
      action({ id: "a6", toolCallId: "t6", label: "执行【F】" }),
      { kind: "text", id: "t3", text: "最新解说。" },
    ]);

    fireEvent.click(screen.getByRole("button", { name: /更早/ }));
    const buttons = screen.getAllByRole("button", { name: /2 个动作/ });
    expect(buttons).toHaveLength(3); // 更早区两组 + 进行中的尾部组（头也在、行已展开）

    fireEvent.click(buttons[0]);
    expect(screen.getByText("编写【A】")).toBeTruthy();
    expect(screen.queryByText("编写【C】")).toBeNull(); // 第二组仍折叠
  });

  it("定格（正常收工）：尾部组回落折叠（story12 坍缩形态），点击仍可展开回看", () => {
    renderMessage(longFlow(), { frozen: true });

    expect(screen.queryByText("编写【C】")).toBeNull(); // 完成的组自动折叠
    fireEvent.click(screen.getByRole("button", { name: /个动作/ }));
    expect(screen.getByText("编写【C】")).toBeTruthy();
  });

  it("失败破例（story9）：更早区的失败组不埋进坍缩行——常驻展开、折叠头带红色计数", () => {
    renderMessage([
      { kind: "text", id: "t0", text: "开工解说。" },
      action({ id: "a1", toolCallId: "t1", label: "编写【A】" }),
      action({ id: "a2", toolCallId: "t2", toolName: "execute", label: "执行【B】", state: "failed" }),
      { kind: "text", id: "t1", text: "重试解说。" },
      action({ id: "a3", toolCallId: "t3", label: "编写【C】" }),
      action({ id: "a4", toolCallId: "t4", label: "执行【D】" }),
    ]);

    // 失败组不藏进「更早」行：动作行常驻可见
    expect(screen.getByText("执行【B】")).toBeTruthy();
    expect(screen.getByText("没做成")).toBeTruthy();
    // 无失败段照常坍缩（更早行仍在——坍缩只让位给事故）
    expect(screen.getByText(/更早/)).toBeTruthy();

    // 展开「更早」回看：完整过程含失败段（story5/17——事件不裁剪，事故段不消失）
    fireEvent.click(screen.getByRole("button", { name: /更早/ }));
    expect(screen.getByText("开工解说。")).toBeTruthy();
    expect(screen.getByText("执行【B】")).toBeTruthy();
  });
});
