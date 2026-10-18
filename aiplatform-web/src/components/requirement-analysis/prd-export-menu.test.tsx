// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { PrdExportMenu } from "./prd-export-menu";

/**
 * PRD 导出（#346，spec #320 ⑥ 需求分析页 header「导出 (PDF/Word)」——实现
 * 归属票注「首版最小可用形态」定为前端打印/客户端 .doc）：两项动作都先过
 * 既有支付门（GET 文件下载端点探测——门判定在后端，前端不预判门态、不做
 * 第二套口径，402 直出信封 message），门开才动手：PDF＝浏览器打印（打印
 * 样式只留 PRD 正文）；Word＝正文 innerHTML 包 .doc 落盘。
 */

const fetchMock = vi.hoisted(() => vi.fn());
const printMock = vi.hoisted(() => vi.fn());
const toastError = vi.hoisted(() => vi.fn());

vi.mock("sonner", () => ({
  toast: { error: toastError },
}));

vi.stubGlobal("fetch", fetchMock);
window.print = printMock;

function renderMenu({
  disabled = false,
  html = "<h1>PRD 正文</h1>",
}: {
  disabled?: boolean;
  html?: string | null;
} = {}) {
  return render(
    <PrdExportMenu
      projectId="p1"
      disabled={disabled}
      fileBaseName="宠物医院预约-PRD"
      getArticleHtml={() => html}
    />,
  );
}

beforeEach(() => {
  fetchMock.mockReset();
  printMock.mockReset();
  toastError.mockReset();
});

afterEach(cleanup);

describe("PrdExportMenu · 支付门（带走才付费，ADR-0027）", () => {
  it("门拦（402）：直出后端信封 message，不触发打印/落盘", async () => {
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify({ code: 4015, message: "支付完成后可下载文件" }), { status: 402 }),
    );
    renderMenu();

    fireEvent.click(screen.getByRole("button", { name: /导出/ }));
    fireEvent.click(await screen.findByText("打印 / 存为 PDF"));

    await waitFor(() => expect(toastError).toHaveBeenCalledWith("支付完成后可下载文件"));
    expect(printMock).not.toHaveBeenCalled();
  });

  it("门开（200）：「打印 / 存为 PDF」调浏览器打印", async () => {
    fetchMock.mockResolvedValue(new Response("# PRD", { status: 200 }));
    renderMenu();

    fireEvent.click(screen.getByRole("button", { name: /导出/ }));
    fireEvent.click(await screen.findByText("打印 / 存为 PDF"));

    await waitFor(() => expect(printMock).toHaveBeenCalledTimes(1));
    expect(toastError).not.toHaveBeenCalled();
  });

  it("网络层失败：如实兜底文案", async () => {
    fetchMock.mockRejectedValue(new Error("offline"));
    renderMenu();

    fireEvent.click(screen.getByRole("button", { name: /导出/ }));
    fireEvent.click(await screen.findByText("下载 Word (.doc)"));

    await waitFor(() => expect(toastError).toHaveBeenCalledWith("导出失败，请稍后重试"));
  });
});

describe("PrdExportMenu · Word 落盘", () => {
  it("门开：正文 HTML 包 .doc 落盘（Blob 类型 msword、文件名随项目）", async () => {
    fetchMock.mockResolvedValue(new Response("# PRD", { status: 200 }));
    const createObjectURL = vi.fn<(blob: Blob) => string>(() => "blob:mock");
    const revokeObjectURL = vi.fn();
    vi.stubGlobal("URL", { ...URL, createObjectURL, revokeObjectURL });
    const clickMock = vi.fn();
    const anchor = { click: clickMock } as unknown as HTMLAnchorElement;
    const createElement = document.createElement.bind(document);
    vi.spyOn(document, "createElement").mockImplementation((tag: string) =>
      tag === "a" ? anchor : createElement(tag),
    );

    renderMenu({ html: "<h2>需求背景</h2>" });
    fireEvent.click(screen.getByRole("button", { name: /导出/ }));
    fireEvent.click(await screen.findByText("下载 Word (.doc)"));

    await waitFor(() => expect(createObjectURL).toHaveBeenCalled());
    const blob = createObjectURL.mock.calls[0]![0];
    expect(blob.type).toBe("application/msword");
    expect(clickMock).toHaveBeenCalledTimes(1);
    vi.restoreAllMocks();
  });
});

describe("PrdExportMenu · 无 PRD", () => {
  it("disabled：入口禁用（无 PRD 无可导出）", () => {
    renderMenu({ disabled: true });
    expect(screen.getByRole("button", { name: /导出/ }).hasAttribute("disabled")).toBe(true);
  });
});
