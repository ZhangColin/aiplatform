// @vitest-environment happy-dom
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { useGenerationStore } from "@/lib/store/generation";
import { useWorkMessageStore, type WorkPart } from "@/lib/store/work-message";

import { SystemPanel } from "./system-panel";

/**
 * 预览跟随（#618，ADR-0057）：写到哪页切哪页——默认开、同页反复重写不跳、组件／
 * 样式／库／布局／API 变化不切页；「固定」暂停自动导航，手动地址跳转自动转固定，
 * 手动刷新保持当前模式。断言面 = iframe 的 src 与重挂节点（导航即换地址；同页
 * 不重挂即无震荡）。
 */

const PREVIEW_URL = "http://localhost:42659";

type HappyDOMWindow = Window & {
  happyDOM: { settings: { disableIframePageLoading: boolean } };
};

let previewUrl: string | undefined = PREVIEW_URL;

vi.mock("@/hooks/use-project-preview", () => ({
  useProjectPreview: () => ({
    data: previewUrl ? { url: previewUrl } : undefined,
    error: undefined,
    isPending: false,
    isError: false,
    refetch: vi.fn(),
  }),
}));

vi.mock("@/hooks/use-demo-version", () => ({
  useDemoVersions: () => ({ data: [] }),
}));

function writePart(toolCallId: string, path: string): WorkPart {
  return {
    kind: "action",
    id: `e-${toolCallId}`,
    toolCallId,
    toolName: "write_file",
    state: "running",
    label: "编写【page】",
    path,
  };
}

/** 当前项目的编码 run 部件（模拟生长中的工作消息）。 */
function seedParts(parts: WorkPart[]) {
  useWorkMessageStore.setState({
    works: { p1: { runId: "run1", frozen: false, parts, seenEventIds: [] } },
  });
}

function pushParts(...parts: WorkPart[]) {
  const current = useWorkMessageStore.getState().works.p1;
  useWorkMessageStore.setState({
    works: {
      p1: {
        runId: "run1",
        frozen: false,
        parts: [...(current?.parts ?? []), ...parts],
        seenEventIds: [],
      },
    },
  });
}

function renderPanel(variant?: "default" | "demo") {
  useGenerationStore.setState({
    generations: {
      p1: { coderRunIds: [], coderStatus: "running", previewEpoch: 0, seenFinishEventIds: [] },
    },
  });
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <SystemPanel
        projectId="p1"
        generationState="generated"
        coderStatus="running"
        variant={variant}
      />
    </QueryClientProvider>,
  );
}

function frameNode(): HTMLIFrameElement {
  return document.querySelector("iframe")!;
}

function frameSrc(): string | null {
  return frameNode().getAttribute("src");
}

/** 跟随／固定开关（aria-pressed = 是否跟随中）。 */
function followToggle(): HTMLElement {
  return screen.getByRole("button", { name: "预览跟随开关" });
}

beforeEach(() => {
  previewUrl = PREVIEW_URL;
  (window as unknown as HappyDOMWindow).happyDOM.settings.disableIframePageLoading = true;
  vi.spyOn(process.stderr, "write").mockImplementation(() => true);
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  useGenerationStore.setState({ generations: {} });
  useWorkMessageStore.setState({ works: {} });
  (window as unknown as HappyDOMWindow).happyDOM.settings.disableIframePageLoading = false;
});

describe("SystemPanel · 预览跟随（#618）", () => {
  it("默认跟随：写到哪页预览切哪页", () => {
    seedParts([writePart("tc-1", "src/app/page.tsx")]);
    renderPanel();
    expect(frameSrc()).toBe(`${PREVIEW_URL}/`);

    act(() => pushParts(writePart("tc-2", "src/app/about/page.tsx")));
    expect(frameSrc()).toBe(`${PREVIEW_URL}/about`);

    act(() => pushParts(writePart("tc-3", "src/app/cart/page.tsx")));
    expect(frameSrc()).toBe(`${PREVIEW_URL}/cart`);
  });

  it("同一页面反复重写不导航（iframe 不重挂、地址不变）", () => {
    seedParts([writePart("tc-1", "src/app/about/page.tsx")]);
    renderPanel();
    const before = frameNode();
    expect(frameSrc()).toBe(`${PREVIEW_URL}/about`);

    act(() => pushParts(writePart("tc-2", "src/app/about/page.tsx")));
    act(() => pushParts(writePart("tc-3", "src/app/about/page.tsx")));

    expect(frameNode()).toBe(before);
    expect(frameSrc()).toBe(`${PREVIEW_URL}/about`);
  });

  it("组件／样式／库／布局／API 的变化不切页", () => {
    seedParts([writePart("tc-1", "src/app/about/page.tsx")]);
    renderPanel();
    const before = frameNode();

    act(() =>
      pushParts(
        writePart("tc-2", "src/components/order-table.tsx"),
        writePart("tc-3", "src/app/globals.css"),
        writePart("tc-4", "src/lib/db.ts"),
        writePart("tc-5", "src/app/layout.tsx"),
        writePart("tc-6", "src/app/api/orders/route.ts"),
      ),
    );

    expect(frameNode()).toBe(before);
    expect(frameSrc()).toBe(`${PREVIEW_URL}/about`);
  });

  it("「固定」暂停自动导航，再点「跟随」恢复", () => {
    seedParts([writePart("tc-1", "src/app/about/page.tsx")]);
    renderPanel();

    fireEvent.click(followToggle());
    expect(followToggle().getAttribute("aria-pressed")).toBe("false");
    act(() => pushParts(writePart("tc-2", "src/app/cart/page.tsx")));
    expect(frameSrc()).toBe(`${PREVIEW_URL}/about`); // 固定后不再自动导航

    fireEvent.click(followToggle());
    expect(followToggle().getAttribute("aria-pressed")).toBe("true");
    // 恢复跟随：切到内核正在写的页（当前最新可映射页面）
    expect(frameSrc()).toBe(`${PREVIEW_URL}/cart`);
  });

  it("手动地址跳转后自动转为固定（后续写入不再抢导航）", () => {
    seedParts([writePart("tc-1", "src/app/about/page.tsx")]);
    renderPanel();

    const input = screen.getByRole("textbox", { name: "预览地址" });
    fireEvent.change(input, { target: { value: "/contact" } });
    fireEvent.submit(input.closest("form")!);
    expect(frameSrc()).toBe(`${PREVIEW_URL}/contact`);
    // 手动导航 = 用户接管：开关已离「跟随」（当前为固定档）
    expect(followToggle().getAttribute("aria-pressed")).toBe("false");

    act(() => pushParts(writePart("tc-2", "src/app/cart/page.tsx")));
    expect(frameSrc()).toBe(`${PREVIEW_URL}/contact`);
  });

  it("手动刷新保持当前模式：刷新后在跟随页强制重挂，新写入照常切页", () => {
    seedParts([writePart("tc-1", "src/app/about/page.tsx")]);
    renderPanel();
    const before = frameNode();

    fireEvent.click(screen.getByRole("button", { name: "刷新预览" }));
    // 跟随档下刷新 = 在当前跟随页强制重挂（保持跟随，不退成固定）
    expect(frameNode()).not.toBe(before);
    expect(frameSrc()).toBe(`${PREVIEW_URL}/about`);
    expect(followToggle().getAttribute("aria-pressed")).toBe("true"); // 仍是跟随档

    act(() => pushParts(writePart("tc-2", "src/app/cart/page.tsx")));
    expect(frameSrc()).toBe(`${PREVIEW_URL}/cart`);
  });

  it("Demo 原型壳同样有跟随开关并自动导航", () => {
    seedParts([writePart("tc-1", "src/app/about/page.tsx")]);
    renderPanel("demo");

    const sandbox = screen.getByRole("region", { name: "Demo 沙箱窗口" });
    expect(within(sandbox).getByRole("button", { name: "预览跟随开关" })).toBeTruthy();
    expect(frameSrc()).toBe(`${PREVIEW_URL}/about`);
  });
});
