// @vitest-environment happy-dom
import { cleanup, fireEvent, render, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { FileDownloadButton } from "./file-download";

// 单文件下载按钮（#287 通用下载＋支付门）：门判定在后端（曾支付/已归档即
// 开放），前端不预判——被拦时直出信封 message 如实告知门语义；成功取 blob
// 经临时锚点落盘。fetch/URL 锚点面全 mock，聚焦交互契约。

const toastMock = vi.hoisted(() => ({ error: vi.fn() }));
vi.mock("sonner", () => ({ toast: toastMock }));

const fetchMock = vi.hoisted(() => vi.fn());
const createObjectURLMock = vi.hoisted(() => vi.fn(() => "blob:download-test"));
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

function buttonOf(container: HTMLElement) {
  return container.querySelector('button[data-file-download]')!;
}

describe("FileDownloadButton · 单文件下载（#287）", () => {
  it("成功：同源直链 fetch 取 blob → 锚点落盘（文件名取路径末段）→ 释放临时 URL", async () => {
    fetchMock.mockResolvedValue({ ok: true, blob: async () => new Blob(["\0png"]) });
    const { container } = render(
      <FileDownloadButton projectId="p1" path="exports/海报-终稿.png" />,
    );

    fireEvent.click(buttonOf(container));

    await waitFor(() => expect(anchorClickMock).toHaveBeenCalled());
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/projects/p1/files/download?path=${encodeURIComponent("exports/海报-终稿.png")}`,
    );
    // 锚点形态：blob URL + download 文件名（路径末段，不带目录）
    const anchor = anchorClickMock.mock.instances[0] as HTMLAnchorElement;
    expect(anchor.href).toBe("blob:download-test");
    expect(anchor.download).toBe("海报-终稿.png");
    expect(revokeObjectURLMock).toHaveBeenCalledWith("blob:download-test");
    expect(toastMock.error).not.toHaveBeenCalled();
  });

  it("支付门拦截（402 ORD_015）：直出后端信封 message 如实告知门语义，不落盘", async () => {
    fetchMock.mockResolvedValue({
      ok: false,
      status: 402,
      json: async () => ({
        code: 5015,
        message: "还未支付，暂不能下载：平台上可随意浏览和预览，带走文件需先完成订单支付",
      }),
    });
    const { container } = render(<FileDownloadButton projectId="p1" path="materials/ref.png" />);

    fireEvent.click(buttonOf(container));

    await waitFor(() =>
      expect(toastMock.error).toHaveBeenCalledWith(
        "还未支付，暂不能下载：平台上可随意浏览和预览，带走文件需先完成订单支付",
      ),
    );
    expect(anchorClickMock).not.toHaveBeenCalled();
  });

  it("错误体非信封（json 不可解析）：兜底文案带 HTTP 状态，不臆造语义", async () => {
    fetchMock.mockResolvedValue({
      ok: false,
      status: 502,
      json: async () => {
        throw new SyntaxError("not json");
      },
    });
    const { container } = render(<FileDownloadButton projectId="p1" path="src/app.ts" />);

    fireEvent.click(buttonOf(container));

    await waitFor(() => expect(toastMock.error).toHaveBeenCalledWith("下载失败（HTTP 502）"));
  });

  it("网络层失败（fetch 抛出）：如实提示，不假装成功", async () => {
    fetchMock.mockRejectedValue(new TypeError("network down"));
    const { container } = render(<FileDownloadButton projectId="p1" path="src/app.ts" />);

    fireEvent.click(buttonOf(container));

    await waitFor(() => expect(toastMock.error).toHaveBeenCalledWith("下载失败，请稍后重试"));
    expect(anchorClickMock).not.toHaveBeenCalled();
  });
});
