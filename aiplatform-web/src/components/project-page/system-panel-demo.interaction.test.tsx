// @vitest-environment happy-dom
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { useGenerationStore, type CoderRunStatus } from "@/lib/store/generation";
import { useWorkMessageStore } from "@/lib/store/work-message";
import type { GenerationState } from "@/lib/projects/detail";

import { SystemPanel } from "./system-panel";

/** Demo 体验页原型壳（#347，interaction seam）：只锁新壳的公共交互与话术，
 *  地址栏/圈注/恢复出口的既有契约由 system-panel 专测继续守护。 */
const seed = vi.hoisted(() => ({ previewUrl: "about:blank" as string | undefined }));

vi.mock("@/hooks/use-project-preview", () => ({
  useProjectPreview: () => ({
    data: seed.previewUrl ? { url: seed.previewUrl } : undefined,
    error: undefined,
    isPending: false,
    isError: false,
    refetch: vi.fn(),
  }),
}));

vi.mock("@/hooks/use-resume-generation", () => ({
  useResumeGeneration: () => ({ isPending: false, mutate: vi.fn() }),
}));

vi.mock("@/hooks/use-demo-version", () => ({
  useDemoVersions: () => ({ data: [] }),
}));

vi.mock("@/hooks/use-restart-update", () => ({
  useRestartUpdate: () => ({ isPending: false, mutate: vi.fn() }),
}));

type HappyDOMWindow = Window & {
  happyDOM: { settings: { disableIframePageLoading: boolean } };
};

beforeEach(() => {
  (window as unknown as HappyDOMWindow).happyDOM.settings.disableIframePageLoading = true;
  vi.spyOn(process.stderr, "write").mockImplementation(() => true);
});

function renderDemoPanel(options?: {
  previewUrl?: string;
  generationState?: GenerationState;
  coderStatus?: CoderRunStatus;
  startedAt?: number;
  endedAt?: number;
}) {
  seed.previewUrl = options && "previewUrl" in options ? options.previewUrl : "about:blank";
  const generationState = options?.generationState ?? "generated";
  const coderStatus = options?.coderStatus ?? "finished";
  useGenerationStore.setState({
    generations: {
      p1: { coderRunIds: [], coderStatus, previewEpoch: 0, seenFinishEventIds: [] },
    },
  });
  useWorkMessageStore.setState({
    works: options?.startedAt
      ? {
          p1: {
            runId: "run-1",
            frozen: options.endedAt != null,
            parts: [{ kind: "text", id: "text-1", text: "正在创建首页" }],
            seenEventIds: [],
            startedAt: options.startedAt,
            endedAt: options.endedAt,
          },
        }
      : {},
  });
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <SystemPanel
        projectId="p1"
        generationState={generationState}
        coderStatus={coderStatus}
        onGenerated={() => {}}
        variant="demo"
      />
    </QueryClientProvider>,
  );
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  useGenerationStore.setState({ generations: {} });
  useWorkMessageStore.setState({ works: {} });
  seed.previewUrl = "about:blank";
  (window as unknown as HappyDOMWindow).happyDOM.settings.disableIframePageLoading = false;
});

describe("SystemPanel · Demo 原型壳视口工具条（#347）", () => {
  it("桌面/手机档位与沙箱提示照原型逐字呈现", () => {
    renderDemoPanel();

    expect(screen.getByText("桌面 1440px")).toBeTruthy();
    expect(screen.getByText("手机 390px")).toBeTruthy();
    expect(screen.getByText("沙箱演示环境 · 隔离运行不产生正式扣款")).toBeTruthy();
  });

  it("沙箱窗承接真实地址栏，刷新/新标签与设备切换在工具条各只保留一套", () => {
    renderDemoPanel();

    const sandbox = screen.getByRole("region", { name: "Demo 沙箱窗口" });
    const address = within(sandbox).getByRole("textbox", { name: "预览地址" });
    expect((address as HTMLInputElement).value).toBe("about:blank");
    expect(within(sandbox).getByText("PROTOTYPE")).toBeTruthy();
    expect(screen.getAllByRole("button", { name: "刷新预览" })).toHaveLength(1);
    expect(screen.getAllByRole("button", { name: "在新窗口打开预览" })).toHaveLength(1);
    expect(screen.getAllByRole("group", { name: "预览设备宽度" })).toHaveLength(1);
  });

  it("新壳设备切换不重挂用户系统，刷新仍强制重挂", () => {
    const { container } = renderDemoPanel();
    const initial = container.querySelector("iframe");

    fireEvent.click(screen.getByRole("button", { name: "手机预览" }));
    const afterDeviceChange = container.querySelector("iframe");
    expect(afterDeviceChange).toBe(initial);
    expect(afterDeviceChange?.parentElement?.className).toContain("w-[390px]");

    fireEvent.click(screen.getByRole("button", { name: "刷新预览" }));
    expect(container.querySelector("iframe")).not.toBe(afterDeviceChange);
  });

  it("新壳选择/圈选通道保持可用", () => {
    renderDemoPanel();

    fireEvent.click(screen.getByRole("button", { name: "选择" }));

    expect(screen.getByRole("button", { name: "退出标注" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "选择" }).getAttribute("aria-pressed")).toBe("true");
  });

  it("生成中同页等待：已等待时间锚定 run-start，当前状态吃真实工作消息且不报进度", () => {
    renderDemoPanel({
      previewUrl: undefined,
      generationState: "generating",
      coderStatus: "running",
      startedAt: Date.now() - 65_000,
    });

    expect(screen.getByText("正在为您生成系统")).toBeTruthy();
    expect(screen.getByText("已等待 01:05")).toBeTruthy();
    expect(screen.getByText("当前状态")).toBeTruthy();
    expect(screen.getByText("正在创建首页")).toBeTruthy();
    expect(screen.queryByText(/\d+%/)).toBeNull();
  });

  it("生成中断仍在同页给出原因与继续生成出口，等待时钟定格在收口事件", () => {
    renderDemoPanel({
      previewUrl: undefined,
      generationState: "interrupted",
      coderStatus: "error",
      startedAt: Date.now() - 65_000,
      endedAt: Date.now(),
    });

    expect(screen.getByText(/生成中断了/)).toBeTruthy();
    expect(screen.getByText("已等待 01:05")).toBeTruthy();
    expect(screen.getByRole("button", { name: "继续生成" })).toBeTruthy();
  });
});
