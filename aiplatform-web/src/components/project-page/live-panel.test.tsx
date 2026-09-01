// @vitest-environment happy-dom
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import type { CoderRunStatus } from "@/lib/store/generation";
import { useGenerationStore } from "@/lib/store/generation";
import { useLiveStore } from "@/lib/store/live";

import { LiveRail } from "./live-panel";

// 直播侧栏（#23 验收口径）：编码 run 进行中呈现段（自述气泡 / 动作行 / 步骤分隔），
// 重试中播帧内话术；run 结束（含终态 error）收起即逝——!inFlight 不渲染、无历史
// 回看。client render（zustand v5 server snapshot 限制同 files-panel.test）：状态
// 经真实 store setState 驱动，可覆盖渲染期派生态转换（起跑自动展开 / 收口自动收起）
// 与手动收展。

function setStores(
  coderStatus: CoderRunStatus | undefined,
  segments: Parameters<typeof seedSegments>[0] = [],
  retryMessage?: string,
) {
  useGenerationStore.setState({
    generations: {
      p1: {
        coderRunIds: ["run1"],
        coderStatus,
        retryMessage,
        previewEpoch: 0,
        seenFinishEventIds: [],
      },
    },
  });
  useLiveStore.setState({ lives: {} });
  seedSegments(segments);
}

function seedSegments(
  segments: Array<{ kind: "step" | "text" | "action"; id: string; n?: number; body?: string }>,
) {
  for (const segment of segments) {
    if (segment.kind === "step") {
      useLiveStore.getState().noteLiveSegment("p1", "run1", {
        kind: "step",
        id: segment.id,
        step: segment.n ?? 1,
      });
    } else if (segment.kind === "text") {
      useLiveStore.getState().noteLiveSegment("p1", "run1", {
        kind: "text",
        id: segment.id,
        text: segment.body ?? "",
      });
    } else {
      useLiveStore.getState().noteLiveSegment("p1", "run1", {
        kind: "action",
        id: segment.id,
        action: segment.body ?? "",
      });
    }
  }
}

describe("LiveRail · 直播侧栏（#23 生成环②）", () => {
  beforeEach(() => {
    useGenerationStore.setState({ generations: {} });
    useLiveStore.setState({ lives: {} });
  });

  afterEach(() => {
    cleanup();
    useGenerationStore.setState({ generations: {} });
    useLiveStore.setState({ lives: {} });
  });

  it("run 进行中：直播面板展开，自述/动作/步骤段逐段呈现", () => {
    setStores("running", [
      { kind: "step", id: "e1", n: 1 },
      { kind: "text", id: "e2", body: "正在准备演示数据。" },
      { kind: "action", id: "e3", body: "正在编写【订单管理】" },
    ]);

    render(<LiveRail projectId="p1" />);

    expect(screen.getAllByText("直播").length).toBeGreaterThan(0);
    expect(screen.getAllByText("第 1 步").length).toBeGreaterThan(0);
    expect(screen.getAllByText("正在准备演示数据。").length).toBeGreaterThan(0);
    expect(screen.getAllByText("正在编写【订单管理】").length).toBeGreaterThan(0);
  });

  it("run 进行中无段（起跑瞬间）：空态一句提示，不空窗", () => {
    setStores("running");

    render(<LiveRail projectId="p1" />);

    expect(screen.getAllByText("智能体开始工作后，这里会逐段说明在做什么").length).toBeGreaterThan(0);
  });

  it("重试中：播帧内话术「遇到问题，正在重试」", () => {
    setStores("retrying", [], "遇到问题，正在重试");

    render(<LiveRail projectId="p1" />);

    expect(screen.getAllByText("遇到问题，正在重试").length).toBeGreaterThan(0);
  });

  it.each(["finished", "error"] as const)("run 终态（%s）：收起即逝不渲染", (status) => {
    setStores(status, [{ kind: "text", id: "e1", body: "已收口的段。" }]);

    const { container } = render(<LiveRail projectId="p1" />);

    expect(container.innerHTML).toBe("");
  });

  it("本会话未见编码 run（含纯 BA 期）：不渲染", () => {
    setStores(undefined);

    const { container } = render(<LiveRail projectId="p1" />);

    expect(container.innerHTML).toBe("");
  });

  it("进行中手动收起 → 可再展开（回看进行中的直播）", () => {
    setStores("running", [{ kind: "text", id: "e1", body: "正在搭页面。" }]);

    render(<LiveRail projectId="p1" />);
    // lg 面板的收起钮（aria 收拢面唯一）
    fireEvent.click(screen.getAllByLabelText("收起直播")[0]);
    expect(screen.getAllByLabelText("展开直播").length).toBeGreaterThan(0);

    fireEvent.click(screen.getAllByLabelText("展开直播")[0]);
    expect(screen.getAllByText("正在搭页面。").length).toBeGreaterThan(0);
  });

  it("run 收口后自动收起即逝（渲染期派生态转换）", () => {
    setStores("running", [{ kind: "text", id: "e1", body: "收口前的段。" }]);

    render(<LiveRail projectId="p1" />);
    expect(screen.getAllByText("收口前的段。").length).toBeGreaterThan(0);

    // run-finish 落 generation store → 终态：整栏退场（收起即逝）
    act(() => {
      useGenerationStore.setState({
        generations: { p1: { coderRunIds: ["run1"], coderStatus: "finished", previewEpoch: 1, seenFinishEventIds: ["run1:9"] } },
      });
    });
    expect(screen.queryByText("收口前的段。")).toBeNull();
  });
});
