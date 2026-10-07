// @vitest-environment happy-dom
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { ProjectDetail } from "@/lib/projects/detail";
import { useWorkMessageStore } from "@/lib/store/work-message";

import { ProjectPageView } from "./project-page-view";

// 设计稿 tab 的 live 切面（#293 验收①：设计过程启动→自动挂载点亮）：work store
// 真实转移（桥的写入面）驱动——designer 会话起跑即开成果区并切「设计稿」（稿在
// 画布上一张张长出来，对偶编码 run 起跑切「系统」先例）；收口后不再抢激活
//（用户手动切回系统不被打扰）。回访只挂载不抢激活归 SSR 测试。数据口 mock 同
// project-page-view.test。

const seed = vi.hoisted(() => ({ detail: undefined as ProjectDetail | undefined }));

vi.mock("@/hooks/use-project", () => ({
  useProject: () => ({
    data: seed.detail,
    isPending: false,
    isError: false,
    error: null,
    refetch: vi.fn(),
  }),
}));
vi.mock("@/hooks/use-conversation", () => ({ useConversation: () => ({}) }));
vi.mock("@/hooks/use-upload-material", () => ({ useUploadMaterial: () => vi.fn() }));
vi.mock("@/hooks/use-chat", () => ({
  usePostMessage: () => ({ isPending: false, mutate: vi.fn() }),
  useAnswerQuestion: () => ({ isPending: false, mutate: vi.fn() }),
}));
vi.mock("@/hooks/use-prd", () => ({
  usePrd: () => ({
    data: { content: "# 需求", updatedAt: "2026-10-01T08:00:00Z" },
    isPending: false,
  }),
}));
vi.mock("@/hooks/use-project-files", () => ({
  useProjectFiles: () => ({ data: [], isPending: false }),
}));
vi.mock("@/hooks/use-resume-generation", () => ({
  useResumeGeneration: () => ({ isPending: false, mutate: vi.fn() }),
}));
vi.mock("@/hooks/use-restart-update", () => ({
  useRestartUpdate: () => ({ isPending: false, mutate: vi.fn() }),
}));
vi.mock("@/hooks/use-project-preview", () => ({
  useProjectPreview: () => ({ data: undefined, isPending: false, isError: false }),
}));
vi.mock("@/hooks/use-order", () => ({
  usePlaceOrder: () => ({ isPending: false, mutate: vi.fn() }),
  useOrder: () => ({ data: undefined, isPending: false }),
  useCancelOrder: () => ({ isPending: false, mutate: vi.fn() }),
}));
vi.mock("@/lib/sse/agent-event-channel", () => ({ useAgentEventChannel: () => {} }));
vi.mock("@/lib/sse/provider", () => ({
  useSseStatus: () => "connected",
  useSseFallbackPolling: () => undefined,
}));

afterEach(() => {
  cleanup();
  useWorkMessageStore.setState({ works: {} });
});

function mountView() {
  seed.detail = {
    id: "p1",
    name: "设计项目",
    prdProducedAt: "2026-10-01T08:00:00Z",
    designItems: [{ ord: 1, title: "首页主视觉", status: "closed" }],
  };
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <ProjectPageView projectId="p1" />
    </QueryClientProvider>,
  );
}

/** 双断点布局各渲染一份 tab 簇（CSS 显隐）——取首个同态实例断言。 */
function firstTab(name: RegExp): HTMLElement {
  return screen.getAllByRole("tab", { name })[0]!;
}

describe("ProjectPageView · 设计稿 tab 的 live 切面（#293）", () => {
  it("回访挂载不抢激活 → designer 起跑切「设计稿」→ 收口后手动切回不被再抢", async () => {
    mountView();

    // 回访口径：三枚 tab（系统/文档/设计稿——tab 文本含图标间隙）、激活仍是「系统」
    const tabs = screen.getAllByRole("tab").map((tab) => tab.textContent ?? "");
    expect(tabs.some((label) => label.includes("设计稿"))).toBe(true);
    expect(firstTab(/系统/).getAttribute("aria-selected")).toBe("true");

    // 设计轨起跑（work store 真实转移——桥在 run-start(agent=designer) 的写入面）
    await act(async () => {
      useWorkMessageStore.getState().startWork("p1", "run-77", undefined, undefined, "designer");
    });
    expect(
      screen
        .getAllByRole("tab", { name: /设计稿/ })
        .every((tab) => tab.getAttribute("aria-selected") === "true"),
    ).toBe(true);
    // 画布已上屏（空态——无稿；双断点两份同态）
    expect(screen.getAllByText("设计稿会在这里长出来").length).toBeGreaterThan(0);

    // 收口定格 + 用户手动切回「系统」——新一场未起跑不再抢激活
    await act(async () => {
      useWorkMessageStore.getState().freezeWork("p1", "run-77");
    });
    fireEvent.click(firstTab(/系统/));
    expect(firstTab(/系统/).getAttribute("aria-selected")).toBe("true");
  });
});
