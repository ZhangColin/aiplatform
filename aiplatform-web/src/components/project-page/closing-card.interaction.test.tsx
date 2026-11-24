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
