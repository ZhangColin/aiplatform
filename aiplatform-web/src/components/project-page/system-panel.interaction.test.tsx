// @vitest-environment happy-dom
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { useGenerationStore } from "@/lib/store/generation";

import { SystemPanel } from "./system-panel";

/** happy-dom 环境注入的 window.happyDOM（关 iframe 页面加载用，避免真实网络请求）。 */
type HappyDOMWindow = Window & {
  happyDOM: { settings: { disableIframePageLoading: boolean } };
};

/**
 * 系统面板浏览器条交互契约（#80 验收锚）：桌面/手机宽度切换不重挂 iframe
 * （用户的系统不因换设备丢状态）、手动刷新强制重挂（本地节拍并入预览纪元）、
 * 新窗口打开应用真实地址（window.open(url)，noopener，#126）。沿 outputs-area.interaction
 * 先例（happy-dom 逐文件例外）；断言用原生属性。数据口 mock 掉；假地址用
 * about:blank——happy-dom 会真去 fetch iframe 的 src，真地址会发网络请求。
 */
// 预览地址读口换可摆变量：默认 about:blank（happy-dom 不真去 fetch），
// 导航类用例切真实 origin 以测路径解析（配合 disableIframePageLoading 关掉 iframe 加载）。
let previewUrl = "about:blank";
vi.mock("@/hooks/use-project-preview", () => ({
  useProjectPreview: (_projectId: string, active: boolean) =>
    active
      ? { data: { url: previewUrl }, error: undefined, isPending: false, isError: false }
      : { data: undefined, error: undefined, isPending: false, isError: false },
}));

vi.mock("@/hooks/use-generate", () => ({
  useGenerate: () => ({ isPending: false, mutate: vi.fn() }),
}));

vi.mock("@/hooks/use-restart-fix", () => ({
  useRestartFix: () => ({ isPending: false, mutate: vi.fn() }),
}));

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
  previewUrl = "about:blank";
  (window as unknown as HappyDOMWindow).happyDOM.settings.disableIframePageLoading = false;
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

describe("SystemPanel · 新窗口打开（#126）", () => {
  it("点新窗口开应用真实地址（window.open(url)，noopener）", () => {
    previewUrl = "http://localhost:42659";
    // 关掉 happy-dom 的 iframe 页面加载（真地址不真发请求）
    (window as unknown as HappyDOMWindow).happyDOM.settings.disableIframePageLoading = true;
    vi.spyOn(process.stderr, "write").mockImplementation(() => true);
    const openSpy = vi.spyOn(window, "open").mockReturnValue(null);
    renderPanel();

    fireEvent.click(screen.getByRole("button", { name: "在新窗口打开预览" }));

    expect(openSpy).toHaveBeenCalledWith("http://localhost:42659", "_blank", "noopener");
  });
});

describe("SystemPanel · 地址栏 goto（#125）", () => {
  it("地址框可聚焦编辑：输入可改值", () => {
    renderPanel();

    const input = screen.getByRole("textbox", { name: "预览地址" });
    fireEvent.change(input, { target: { value: "/login" } });

    expect((input as HTMLInputElement).value).toBe("/login");
  });

  describe("真实 origin 下（http://localhost:42659）", () => {
    beforeEach(() => {
      previewUrl = "http://localhost:42659";
      // 关掉 happy-dom 的 iframe 页面加载（真地址不真发请求）——它仍会经 process.stderr
      // 打一条「Iframe page loading is disabled」告警，这里一并吞掉（仅导航用例，scoped）
      (window as unknown as HappyDOMWindow).happyDOM.settings.disableIframePageLoading = true;
      vi.spyOn(process.stderr, "write").mockImplementation(() => true);
    });

    it("提交路径 → 导航到应用 origin 内对应页（iframe 换新地址、地址框回显）", () => {
      const { frame } = renderPanel();
      expect(frame()!.getAttribute("src")).toBe("http://localhost:42659");

      const input = screen.getByRole("textbox", { name: "预览地址" });
      fireEvent.change(input, { target: { value: "/login" } });
      fireEvent.submit(input.closest("form")!);

      expect(frame()!.getAttribute("src")).toBe("http://localhost:42659/login");
      expect(
        (screen.getByRole("textbox", { name: "预览地址" }) as HTMLInputElement).value,
      ).toBe("http://localhost:42659/login");
    });

    it("跨源输入拒绝：不跳出沙箱预览（iframe src 不变、地址框回显当前）", () => {
      const { frame } = renderPanel();
      const input = screen.getByRole("textbox", { name: "预览地址" });
      fireEvent.change(input, { target: { value: "https://evil.com" } });
      fireEvent.submit(input.closest("form")!);

      expect(frame()!.getAttribute("src")).toBe("http://localhost:42659");
      expect(
        (screen.getByRole("textbox", { name: "预览地址" }) as HTMLInputElement).value,
      ).toBe("http://localhost:42659");
    });
  });
});
