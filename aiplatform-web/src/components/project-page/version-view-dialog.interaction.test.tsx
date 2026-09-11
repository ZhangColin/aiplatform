// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { VersionViewDialog, viewThenTitle } from "./version-view-dialog";

/**
 * 「查看当时」弹窗交互契约（#140）：设备宽度切换不重挂 iframe（同 SystemPanel
 * #80 口径——快照不因换设备丢状态）、新窗口打开快照真实地址（window.open
 * noopener，弹窗仍是快照宿主、关窗即销毁）、标题带轮次语境（锚 = 该轮收口
 * 时刻可见）。沿 system-panel.interaction 先例（happy-dom 逐文件例外）；假地址
 * about:blank——happy-dom 会真去 fetch iframe 的 src。
 */

/** 弹窗开启态的常用渲染面（Portal 落 document.body，走 screen 查询）。 */
function renderDialog(overrides: Partial<Parameters<typeof VersionViewDialog>[0]> = {}) {
  render(
    <VersionViewDialog
      open
      onOpenChange={vi.fn()}
      pending={false}
      error={false}
      previewUrl="about:blank"
      {...overrides}
    />,
  );
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  (window as unknown as HappyDOMWindow).happyDOM.settings.disableIframePageLoading = false;
});

/** happy-dom 环境注入的 window.happyDOM（关 iframe 页面加载用，避免真实网络请求）。 */
type HappyDOMWindow = Window & {
  happyDOM: { settings: { disableIframePageLoading: boolean } };
};

/** 用真实 origin 的用例共用的预处理：关掉 happy-dom 的 iframe 页面加载（真地址不
 *  真发请求）——它仍会经 process.stderr 打一条告警，一并吞掉（system-panel.interaction 先例）。 */
function useRealSnapshotOrigin() {
  (window as unknown as HappyDOMWindow).happyDOM.settings.disableIframePageLoading = true;
  vi.spyOn(process.stderr, "write").mockImplementation(() => true);
}

describe("VersionViewDialog · 弹窗尺寸（#140）", () => {
  it("吃满屏幕宽高而非 384px 窄条：sm 档 max-w 覆盖到位（基座 sm:max-w-sm 陷阱回归锚）", () => {
    renderDialog();

    // 基座 DialogContent 自带 sm:max-w-sm（384px ≈ 手机宽），与传入的 max-w-*
    // 分属不同 variant、tw-merge 不互斥，sm 档 CSS 序在后胜出——「弹窗像手机
    // 屏幕」的根因。传入类必须同档（sm:max-w-*）才能压掉。
    const content = document.querySelector('[data-slot="dialog-content"]')!;
    expect(content.className).toContain("w-[94vw]");
    expect(content.className).toContain("sm:max-w-[1600px]");
  });
});

describe("VersionViewDialog · 设备宽度切换（#140，同预览 #80 口径）", () => {
  it("切手机宽度：舞台换 390px 手机框，iframe 不重挂（快照不丢状态）", () => {
    renderDialog();
    const before = screen.getByTitle("当时系统快照");

    fireEvent.click(screen.getByRole("button", { name: "手机预览" }));

    const after = screen.getByTitle("当时系统快照");
    expect(after).toBe(before); // 同一 DOM 节点：宽度是样式切换不是重建
    expect(after.parentElement!.className).toContain("w-[390px]");
  });

  it("切回桌面宽度：手机框撤下，iframe 仍不重挂", () => {
    renderDialog();
    fireEvent.click(screen.getByRole("button", { name: "手机预览" }));
    const before = screen.getByTitle("当时系统快照");

    fireEvent.click(screen.getByRole("button", { name: "桌面预览" }));

    expect(screen.getByTitle("当时系统快照")).toBe(before);
    expect(screen.getByTitle("当时系统快照").parentElement!.className).not.toContain("w-[390px]");
  });
});

describe("VersionViewDialog · 新窗口打开（#140）", () => {
  beforeEach(useRealSnapshotOrigin);

  it("点新窗口开快照真实地址（window.open(url)，noopener）；弹窗仍是宿主不关", () => {
    const openSpy = vi.spyOn(window, "open").mockReturnValue(null);
    renderDialog({ previewUrl: "http://localhost:42659" });

    fireEvent.click(screen.getByRole("button", { name: "在新窗口打开当时系统" }));

    expect(openSpy).toHaveBeenCalledWith("http://localhost:42659", "_blank", "noopener");
    expect(document.querySelector('[data-slot="dialog-content"]')).toBeTruthy(); // 弹窗不随之关
  });

  it("快照未就绪：新窗口入口置灰（无地址可开）", () => {
    renderDialog({ pending: true, previewUrl: undefined });

    expect(
      (screen.getByRole("button", { name: "在新窗口打开当时系统" }) as HTMLButtonElement).disabled,
    ).toBe(true);
  });
});

describe("VersionViewDialog · 标题轮次语境（#140：锚 = 该轮收口时刻可见）", () => {
  it("本轮用户发言在场：标题「<摘要>那轮结束时的系统」", () => {
    renderDialog({ roundPrompt: "帮我把首页改成蓝色" });

    expect(screen.getByText("「帮我把首页改成蓝色」那轮结束时的系统")).toBeTruthy();
  });

  it("发言缺场（无 runId / 被软上限裁剪）：回落无引语境式样", () => {
    renderDialog({ roundPrompt: undefined });

    expect(screen.getByText("那轮结束时的系统")).toBeTruthy();
  });

  it("起服中：快照未就绪仍先出标题与在途提示", () => {
    renderDialog({ pending: true, previewUrl: undefined, roundPrompt: "修下单按钮" });

    expect(screen.getByText("「修下单按钮」那轮结束时的系统")).toBeTruthy();
    expect(screen.getByText("正在准备当时系统…")).toBeTruthy();
    expect(screen.queryByTitle("当时系统快照")).toBeNull();
  });
});

describe("viewThenTitle · 标题式样纯函数", () => {
  it("多行发言折成单行（标题一行）", () => {
    expect(viewThenTitle("第一行\n第二行")).toBe("「第一行 第二行」那轮结束时的系统");
  });

  it("长发言截断加省略号（最简一行，不吞标题）", () => {
    const long = "一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十";
    expect(viewThenTitle(long)).toBe("「一二三四五六七八九十一二三四五六七八九十…」那轮结束时的系统");
  });

  it("空白 / 缺场回落无引语境式样", () => {
    expect(viewThenTitle(undefined)).toBe("那轮结束时的系统");
    expect(viewThenTitle("   ")).toBe("那轮结束时的系统");
  });
});
