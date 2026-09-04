// @vitest-environment happy-dom
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { useGenerationStore } from "@/lib/store/generation";

import { SystemPanel } from "./system-panel";

/**
 * 系统面板浏览器条交互契约（#80 验收锚）：桌面/手机宽度切换不重挂 iframe
 * （用户的系统不因换设备丢状态）、手动刷新强制重挂（本地节拍并入预览纪元）、
 * 新窗口打开独立预览页（/preview/:id，noopener）。沿 outputs-area.interaction
 * 先例（happy-dom 逐文件例外）；断言用原生属性。数据口 mock 掉；假地址用
 * about:blank——happy-dom 会真去 fetch iframe 的 src，真地址会发网络请求。
 */
vi.mock("@/hooks/use-project-preview", () => ({
  useProjectPreview: (_projectId: string, active: boolean) =>
    active
      ? { data: { url: "about:blank" }, error: undefined, isPending: false, isError: false }
      : { data: undefined, error: undefined, isPending: false, isError: false },
}));

vi.mock("@/hooks/use-generate", () => ({
  useGenerate: () => ({ isPending: false, mutate: vi.fn() }),
}));

vi.mock("@/hooks/use-restart-fix", () => ({
  useRestartFix: () => ({ isPending: false, mutate: vi.fn() }),
}));

const liveLives: Record<string, never> = {};
vi.mock("@/lib/store/live", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/store/live")>();
  return {
    ...actual,
    useLiveStore: (selector: (state: { lives: typeof liveLives }) => unknown) =>
      selector({ lives: liveLives }),
  };
});

function renderPanel() {
  useGenerationStore.setState({
    generations: { p1: { coderRunIds: [], coderStatus: "running", previewEpoch: 0, seenFinishEventIds: [] } },
  });
  const utils = render(
    <QueryClientProvider client={new QueryClient()}>
      <SystemPanel projectId="p1" coderStatus="running" onGenerated={() => {}} />
    </QueryClientProvider>,
  );
  return { frame: () => utils.container.querySelector("iframe")!, ...utils };
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  useGenerationStore.setState({ generations: {} });
});

describe("SystemPanel · 设备宽度切换（#80）", () => {
  it("切手机宽度：舞台换 390px 手机框，iframe 不重挂（用户系统不丢状态）", () => {
    const { frame } = renderPanel();
    const before = frame();

    fireEvent.click(screen.getByRole("button", { name: "手机预览" }));

    const after = frame();
    expect(after).toBe(before); // 同一 DOM 节点：宽度是样式切换不是重建
    expect(after.parentElement!.className).toContain("w-[390px]");
  });

  it("切回桌面宽度：手机框撤下，iframe 仍不重挂", () => {
    const { frame } = renderPanel();
    fireEvent.click(screen.getByRole("button", { name: "手机预览" }));
    const before = frame();

    fireEvent.click(screen.getByRole("button", { name: "桌面预览" }));

    expect(frame()).toBe(before);
    expect(frame().parentElement!.className).not.toContain("w-[390px]");
  });
});

describe("SystemPanel · 手动刷新（#80）", () => {
  it("点刷新强制重挂 iframe（本地节拍并入预览纪元的重挂 key）", () => {
    const { frame } = renderPanel();
    const before = frame();

    fireEvent.click(screen.getByRole("button", { name: "刷新预览" }));

    expect(frame()).not.toBe(before);
    expect(frame()!.getAttribute("src")).toBe("about:blank");
  });
});

describe("SystemPanel · 新窗口打开（#80）", () => {
  it("点新窗口开独立预览页（/preview/:id，noopener）", () => {
    const openSpy = vi.spyOn(window, "open").mockReturnValue(null);
    renderPanel();

    fireEvent.click(screen.getByRole("button", { name: "在新窗口打开预览" }));

    expect(openSpy).toHaveBeenCalledWith("/preview/p1", "_blank", "noopener");
  });
});
