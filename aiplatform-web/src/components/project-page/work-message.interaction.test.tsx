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

function renderMessage(
  parts: WorkPart[],
  overrides: Partial<WorkSnapshot> = {},
  closingArrived = false,
) {
  const snapshot: WorkSnapshot = { runId: "r1", frozen: false, parts, ...overrides };
  return render(<WorkMessage work={snapshot} closingArrived={closingArrived} />);
}

afterEach(cleanup);

/** 长流水样例（#230 成功无痕后）：更早区（解说 + 失败痕）+ 尾部（解说＋失败锚）；在跑动作归活性行（#235），不进正文。 */
function longFlow(): WorkPart[] {
  return [
    { kind: "text", id: "t0", text: "开工解说。" },
    action({ id: "a1", toolCallId: "t1", label: "编写【A】" }),
    action({ id: "a2", toolCallId: "t2", toolName: "execute", state: "failed", label: "执行【B】" }),
    { kind: "text", id: "t1", text: "过渡解说一。" },
    { kind: "text", id: "t2", text: "过渡解说二。" },
    { kind: "text", id: "t3", text: "过渡解说三。" },
    { kind: "text", id: "t4", text: "最新解说。" },
    action({ id: "a4", toolCallId: "t4", state: "running", label: "执行【D】" }),
  ];
}

describe("WorkMessage · 更早区展开回看交互（#225 混合坍缩 + #230 成功无痕 + #235 常驻活性行）", () => {
  it("默认坍缩：更早解说藏进「更早 N 项」，失败红行破例常驻（story9），成功动作无痕（#230）", () => {
    renderMessage(longFlow());

    // 坍缩行只数解说段（失败痕不进计数）
    expect(screen.getByText(/更早 2 项/)).toBeTruthy();
    expect(screen.queryByText("过渡解说一。")).toBeNull(); // 更早解说不逐条出（坍缩控噪）
    expect(screen.queryByText("过渡解说二。")).toBeNull();
    expect(screen.getByText("开工解说。")).toBeTruthy(); // 失败锚前展解说常驻（语境）
    // 失败红行不埋进坍缩行——常驻展开红显（#225 破例语义承接）
    expect(screen.getByText("执行【B】")).toBeTruthy();
    expect(screen.getByText("没做成")).toBeTruthy();
    // 活性行常驻（#235 唯一实时状态行）：在跑动作 label 归活性行
    expect(screen.getByText("执行【D】")).toBeTruthy();
    // 成功动作沉没——未展开也不在（滤除，不是坍缩）
    expect(screen.queryByText("编写【A】")).toBeNull();
  });

  it("点击展开回看：读到的是解说段与失败痕（story4/5/6——事件不裁剪仅呈现坍缩），展开亦无成功残骸", () => {
    renderMessage(longFlow());

    fireEvent.click(screen.getByRole("button", { name: /更早/ }));
    expect(screen.getByText("过渡解说一。")).toBeTruthy();
    expect(screen.getByText("过渡解说二。")).toBeTruthy();
    expect(screen.getByText("执行【B】")).toBeTruthy(); // 失败痕回看仍在（事故不消失）
    expect(screen.queryByText("编写【A】")).toBeNull(); // 成功沉没与坍缩无关——展开也不出
  });

  it("定格未入流：坍缩形态保持（story12 无跳变）、成功动作无残骸（#230），活性行保留末行（静态）", () => {
    renderMessage(longFlow(), { frozen: true });

    expect(screen.getByText(/更早 2 项/)).toBeTruthy();
    expect(screen.getByText("执行【D】")).toBeTruthy(); // 保留末行——定格不闪空（#235）
    expect(screen.queryByText("编写【A】")).toBeNull(); // 成功动作沉没
    expect(screen.getByText("执行【B】")).toBeTruthy(); // 失败红行留驻
    expect(screen.getByText("最新解说。")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: /更早/ }));
    expect(screen.getByText("过渡解说一。")).toBeTruthy(); // 回看叙事仍在
  });

  it("定格且收尾卡入流：活性行随定格沉没——截断动作无残骸（#235 沉没锚）", () => {
    renderMessage(longFlow(), { frozen: true }, true);

    expect(screen.queryByText("执行【D】")).toBeNull(); // 截断的未终态动作随活性行沉没
    expect(screen.queryByText("编写【A】")).toBeNull();
    expect(screen.getByText("执行【B】")).toBeTruthy(); // 失败红行留驻终形
    expect(screen.getByText("最新解说。")).toBeTruthy();
  });
});

describe("WorkMessage · designer 直播卡交互（#290：出稿活性行换装＋解说坍缩回看）", () => {
  /** designer 快照（会话即设计物：slice＝标题＋1-based 序）。 */
  function designWork(parts: WorkPart[], overrides: Partial<WorkSnapshot> = {}): WorkSnapshot {
    return {
      runId: "run-d1",
      frozen: false,
      seat: "designer",
      slice: { title: "logo 主标识", index: 2, total: 3 },
      parts,
      ...overrides,
    } satisfies WorkSnapshot;
  }

  function draft(
    id: string,
    toolCallId: string,
    label: string,
    state: Extract<WorkPart, { kind: "action" }>["state"] = "completed",
  ) {
    return action({ id, toolCallId, toolName: "write_file", state, label });
  }

  it("出稿活性行随直播事件换装：第 1 稿完成（打字点）→ 第 2 稿开跑（正在出第 2 稿）→ 定格保留末行（静态）", () => {
    const { rerender } = render(<WorkMessage work={designWork([])} />);

    // 第 1 稿在跑 → 出第 1 稿
    rerender(<WorkMessage work={designWork([draft("a1", "tc1", "编写【logo-1】", "running")])} />);
    expect(screen.getByText("正在出第 1 稿")).toBeTruthy();

    // 第 1 稿完成（成功无痕）→ 间隙打字点
    rerender(<WorkMessage work={designWork([draft("a1", "tc1", "编写【logo-1】")])} />);
    expect(screen.queryByText(/正在出第/)).toBeNull();
    expect(document.querySelector(".animate-pulse")).toBeTruthy();

    // 第 2 稿开跑 → 出第 2 稿（换装不跳行：同槽文本替换）
    rerender(
      <WorkMessage
        work={designWork([
          draft("a1", "tc1", "编写【logo-1】"),
          draft("a2", "tc2", "编写【logo-2】", "running"),
        ])}
      />,
    );
    expect(screen.getByText("正在出第 2 稿")).toBeTruthy();
    expect(screen.queryByText("正在出第 1 稿")).toBeNull();

    // 定格保留末行（静态——不自称在跑）；收尾卡入流即沉没
    rerender(
      <WorkMessage
        work={designWork(
          [
            draft("a1", "tc1", "编写【logo-1】"),
            draft("a2", "tc2", "编写【logo-2】", "running"),
          ],
          { frozen: true },
        )}
      />,
    );
    expect(screen.getByText("正在出第 2 稿")).toBeTruthy();
    expect(screen.queryByText("进行中")).toBeNull();
    rerender(
      <WorkMessage
        closingArrived
        work={designWork(
          [
            draft("a1", "tc1", "编写【logo-1】"),
            draft("a2", "tc2", "编写【logo-2】", "running"),
          ],
          { frozen: true },
        )}
      />,
    );
    expect(screen.queryByText(/正在出第/)).toBeNull();
  });

  it("解说坍缩回看同构：多段解说折进「更早 N 项」、点击展开（设计叙事不裁剪）", () => {
    render(
      <WorkMessage
        work={designWork([
          { kind: "text", id: "t0", text: "先读 PRD 清单章。" },
          { kind: "text", id: "t1", text: "方向一走极简。" },
          { kind: "text", id: "t2", text: "方向二用衬线字。" },
          { kind: "text", id: "t3", text: "方向三上插画。" },
        ])}
      />,
    );

    expect(screen.getByText(/更早 2 项/)).toBeTruthy();
    expect(screen.queryByText("方向一走极简。")).toBeNull(); // 坍缩控噪
    fireEvent.click(screen.getByRole("button", { name: /更早/ }));
    expect(screen.getByText("方向一走极简。")).toBeTruthy(); // 展开回看（事件不裁剪）
    expect(screen.getByText("方向二用衬线字。")).toBeTruthy();
  });
});
