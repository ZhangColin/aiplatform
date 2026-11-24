// @vitest-environment happy-dom
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { WorkClosing } from "@/lib/store/chat";

import { ClosingCard } from "./closing-card";

// 预览入口（#617）：取既有预览查询的 URL——未就绪禁用、就绪走既有「新窗口打开」
// 动作（#126），触碰先行（#182 先 refetch 再开窗）
const previewState = vi.hoisted(() => ({
  data: undefined as { url?: string } | undefined,
  refetch: vi.fn(),
}));
vi.mock("@/hooks/use-project-preview", () => ({
  useProjectPreview: () => previewState,
}));

// 版本动作请求面本测试不消费（卡上无 version 键）
vi.mock("@/hooks/use-version", () => ({
  useRollbackVersion: () => ({ mutate: vi.fn(), isPending: false }),
  useVersionViewSession: () => ({
    startView: { isPending: false, isError: false, data: undefined, mutate: vi.fn(), reset: vi.fn() },
    start: vi.fn(),
    finish: vi.fn(),
  }),
}));

function card(overrides: Partial<WorkClosing> = {}): WorkClosing {
  return {
    narrative: "生成完成",
    criteria: { buildPassed: true, previewPassed: true },
    files: [],
    durationMs: 60_000,
    ...overrides,
  };
}

function renderCard(value: WorkClosing) {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <ClosingCard closing={value} projectId="100" />
    </QueryClientProvider>,
  );
}

afterEach(() => {
  cleanup();
  previewState.data = undefined;
  vi.restoreAllMocks();
});

describe("ClosingCard · 单文件只读 diff（#619）", () => {
  const files = [
    {
      path: "/src/App.jsx",
      added: 4,
      removed: 0,
      patch: "diff --git a/src/App.jsx b/src/App.jsx\n@@ -1 +1,4 @@\n+新增一行\n",
    },
    { path: "/src/Huge.jsx", added: 500, removed: 0, patchOmitted: true },
  ];

  it("点单个文件展开只读 diff，再点收起", () => {
    renderCard(card({ files }));

    expect(screen.queryByText("+新增一行")).toBeNull();
    const toggle = screen.getByRole("button", { name: "查看 /src/App.jsx 的差异" });
    fireEvent.click(toggle);
    expect(screen.getByText("+新增一行")).toBeTruthy();
    expect(screen.getByText("diff --git a/src/App.jsx b/src/App.jsx")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "收起 /src/App.jsx 的差异" }));
    expect(screen.queryByText("+新增一行")).toBeNull();
  });

  it("超限文件（patchOmitted）没有 diff 展开入口，只标注「diff 过大」", () => {
    renderCard(card({ files }));

    expect(screen.getByText("diff 过大")).toBeTruthy();
    expect(screen.queryByRole("button", { name: /Huge\.jsx 的差异/ })).toBeNull();
  });

  it("变更清单不可用：显示降级文案，不显示文件行", () => {
    renderCard(card({ files: [], filesUnavailable: true }));

    expect(screen.getByText("变更清单不可用")).toBeTruthy();
  });
});

describe("ClosingCard · 预览入口（#617）", () => {
  it("未就绪：入口禁用（无地址可开）", () => {
    renderCard(card());

    const button = screen.getByRole("button", { name: "在新窗口打开预览" }) as HTMLButtonElement;
    expect(button.disabled).toBe(true);
  });

  it("就绪：点入口先 refetch 预览查询，再新窗口打开真实地址", () => {
    previewState.data = { url: "https://preview.example.com/" };
    const open = vi.spyOn(window, "open").mockImplementation(() => null);
    renderCard(card());

    fireEvent.click(screen.getByRole("button", { name: "在新窗口打开预览" }));

    expect(previewState.refetch).toHaveBeenCalledTimes(1);
    expect(open).toHaveBeenCalledWith("https://preview.example.com/", "_blank", "noopener");
    // 调用序：refetch 先于 window.open
    expect(previewState.refetch.mock.invocationCallOrder[0]).toBeLessThan(
      open.mock.invocationCallOrder[0],
    );
  });
});
