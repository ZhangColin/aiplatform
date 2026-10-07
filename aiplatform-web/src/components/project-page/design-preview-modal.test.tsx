// @vitest-environment happy-dom
import { cleanup, fireEvent, render, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { CanvasDraft } from "@/lib/projects/design-canvas";
import { DesignPreviewModal } from "./design-preview-modal";

// 稿预览弹窗（#294）：点开＝放大观看（界面类可交互帧/平面类大图），底部下载面
// ——下载图（界面类＝位图化端点、平面类＝通用下载）、下载 HTML（帧源）。支付门
// 判定在后端：402 直出信封 message 如实告知（file-download 同款契约）；成功取
// blob 锚点落盘（文件名＝词干＋画幅）。fetch/URL 锚点面全 mock。

const toastMock = vi.hoisted(() => ({ error: vi.fn() }));
vi.mock("sonner", () => ({ toast: toastMock }));

const fetchMock = vi.hoisted(() => vi.fn());
const createObjectURLMock = vi.hoisted(() => vi.fn(() => "blob:preview-test"));
const revokeObjectURLMock = vi.hoisted(() => vi.fn());
const anchorClickMock = vi.hoisted(() => vi.fn());

beforeEach(() => {
  vi.stubGlobal("fetch", fetchMock);
  vi.stubGlobal("URL", Object.assign(URL, {
    createObjectURL: createObjectURLMock,
    revokeObjectURL: revokeObjectURLMock,
  }));
  vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(anchorClickMock);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  fetchMock.mockReset();
  anchorClickMock.mockReset();
  toastMock.error.mockReset();
});

function htmlDraft(): CanvasDraft {
  return { path: "/design/home-1.html", media: "html", item: "首页主视觉", gen: 1 };
}

function imageDraft(): CanvasDraft {
  return { path: "/design/777-海报.png", media: "image", item: "电商海报", gen: 2 };
}

describe("DesignPreviewModal · 下载面（#294 所见即所下＋支付门）", () => {
  it("界面类下载图：走位图化端点（design-drafts/png），落盘名＝词干＋画幅", async () => {
    fetchMock.mockResolvedValue({ ok: true, blob: async () => new Blob(["\0png"]) });
    const { container } = render(
      <DesignPreviewModal projectId="p1" draft={htmlDraft()} name="home-1.html" onClose={vi.fn()} />,
    );

    fireEvent.click(container.querySelector('[data-download-png]')!);

    await waitFor(() => expect(anchorClickMock).toHaveBeenCalled());
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/projects/p1/design-drafts/png?path=${encodeURIComponent("design/home-1.html")}`,
    );
    const anchor = anchorClickMock.mock.instances[0] as HTMLAnchorElement;
    expect(anchor.download).toBe("home-1-1280x800.png");
  });

  it("界面类下载 HTML：走通用单文件下载（支付门同面），落盘名＝原文件名", async () => {
    fetchMock.mockResolvedValue({ ok: true, blob: async () => new Blob(["<html>"]) });
    const { container } = render(
      <DesignPreviewModal projectId="p1" draft={htmlDraft()} name="home-1.html" onClose={vi.fn()} />,
    );

    fireEvent.click(container.querySelector('[data-download-html]')!);

    await waitFor(() => expect(anchorClickMock).toHaveBeenCalled());
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/projects/p1/files/download?path=${encodeURIComponent("design/home-1.html")}`,
    );
    expect((anchorClickMock.mock.instances[0] as HTMLAnchorElement).download).toBe("home-1.html");
  });

  it("平面类：下载图即图片本体（通用下载），无下载 HTML 钮", async () => {
    fetchMock.mockResolvedValue({ ok: true, blob: async () => new Blob(["\0png"]) });
    const { container } = render(
      <DesignPreviewModal projectId="p1" draft={imageDraft()} name="海报.png" onClose={vi.fn()} />,
    );

    expect(container.querySelector("[data-download-html]")).toBeNull();
    fireEvent.click(container.querySelector('[data-download-png]')!);

    await waitFor(() => expect(anchorClickMock).toHaveBeenCalled());
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/projects/p1/files/download?path=${encodeURIComponent("design/777-海报.png")}`,
    );
  });

  it("支付门拦截（402 ORD_015）：直出信封 message 如实告知门语义，不落盘", async () => {
    fetchMock.mockResolvedValue({
      ok: false,
      status: 402,
      json: async () => ({
        code: 5015,
        message: "还未支付，暂不能下载：平台上可随意浏览和预览，带走文件需先完成订单支付",
      }),
    });
    const { container } = render(
      <DesignPreviewModal projectId="p1" draft={htmlDraft()} name="home-1.html" onClose={vi.fn()} />,
    );

    fireEvent.click(container.querySelector('[data-download-png]')!);

    await waitFor(() =>
      expect(toastMock.error).toHaveBeenCalledWith(
        "还未支付，暂不能下载：平台上可随意浏览和预览，带走文件需先完成订单支付",
      ),
    );
    expect(anchorClickMock).not.toHaveBeenCalled();
  });

  it("弹窗观看面：界面类＝raw 帧取件（1280×800 帧缩放）；平面类＝raw 大图", () => {
    const html = render(
      <DesignPreviewModal projectId="p1" draft={htmlDraft()} name="home-1.html" onClose={vi.fn()} />,
    );
    expect(
      html.container.querySelector(
        `iframe[data-preview-frame="${CSS.escape("/design/home-1.html")}"]`,
      ),
    ).toBeTruthy();
    cleanup();

    const image = render(
      <DesignPreviewModal projectId="p1" draft={imageDraft()} name="海报.png" onClose={vi.fn()} />,
    );
    expect(
      image.container.querySelector(
        `img[data-preview-image="${CSS.escape("/design/777-海报.png")}"]`,
      ),
    ).toBeTruthy();
  });
});
