// @vitest-environment happy-dom
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const seed = vi.hoisted(() => ({
  post: vi.fn(),
  previewUrl: undefined as string | undefined,
}));

vi.mock("@/lib/api/client", () => ({
  api: { post: (...args: unknown[]) => seed.post(...args) },
}));

vi.mock("@/hooks/use-project-preview", () => ({
  useProjectPreview: () => ({
    data: seed.previewUrl ? { url: seed.previewUrl } : undefined,
    error: undefined,
    refetch: vi.fn(),
  }),
}));

import { SystemPanel } from "./system-panel";

/**
 * 停止入口呈现（#359 生成取消 AC①「入口不藏」）：生成中两态——Demo 等待态
 * （无应用 hint 档）与生成中细条（有应用 page 档）——都挂「停止」按钮；点击走
 * POST /generation/stop。终态（停止收口后）不再出停止入口、恢复出口照常。
 */
function renderPanel(props: Partial<Parameters<typeof SystemPanel>[0]> = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <SystemPanel
        projectId="p1"
        generationState="generating"
        onGenerated={() => {}}
        variant="demo"
        {...props}
      />
    </QueryClientProvider>,
  );
}

describe("system panel · 停止入口（#359）", () => {
  beforeEach(() => {
    seed.previewUrl = undefined;
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it("Demo 等待态（无应用、生成中 hint 档）：停止按钮与时钟卡同排呈现", () => {
    renderPanel();
    expect(screen.getByRole("button", { name: /停止/ })).toBeTruthy();
    expect(screen.getByText("正在为您生成系统")).toBeTruthy();
  });

  it("生成中细条（有应用、更新中 page 档）：浏览器条内联停止按钮", () => {
    seed.previewUrl = "http://localhost:42659";
    renderPanel({ generationState: "generated", coderStatus: "running" });
    expect(screen.getByText("正在更新系统，完成后自动刷新")).toBeTruthy();
    expect(screen.getByRole("button", { name: /停止/ })).toBeTruthy();
  });

  it("点击停止 → POST /projects/{id}/generation/stop", async () => {
    seed.post.mockResolvedValue({ stopped: true });
    renderPanel();
    fireEvent.click(screen.getByRole("button", { name: /停止/ }));
    await waitFor(() => {
      expect(seed.post).toHaveBeenCalledWith("/projects/p1/generation/stop");
    });
  });

  it("停止终态（coderStoppedByUser）：失败档出「已停止」口径，不再出停止入口、恢复出口照常", () => {
    renderPanel({ generationState: "interrupted", coderStatus: "error", coderStoppedByUser: true });
    expect(screen.getByText("已停止生成，已完成的进度都保留")).toBeTruthy();
    expect(screen.getByText("已停止生成")).toBeTruthy();
    expect(screen.queryByRole("button", { name: /^停止$/ })).toBeNull();
    expect(screen.getByRole("button", { name: /继续生成/ })).toBeTruthy();
  });
});
