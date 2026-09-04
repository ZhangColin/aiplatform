import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError } from "@/lib/api/api-error";

import { PreviewWindow } from "./preview-window";

// 预览新窗口独立页（#80）：只渲染用户系统本身——全幅 iframe、整页浅色锁定
// （用户产物不随平台 Light/Dark 翻转）。地址自取（探活轮询直到 URL 到）；
// 未就绪（WSP_012）同无错走接通中，真故障走打不开口径（轮询自会重试）。
// 预览地址读口 mock 掉（每用例摆 url 有无与 error）。
let previewResult: {
  data?: { url: string };
  error?: unknown;
  isPending: boolean;
  isError: boolean;
} = { isPending: false, isError: false };

vi.mock("@/hooks/use-project-preview", () => ({
  useProjectPreview: () => previewResult,
}));

function renderWindow() {
  return renderToStaticMarkup(
    <QueryClientProvider client={new QueryClient()}>
      <PreviewWindow projectId="p1" />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  previewResult = { isPending: false, isError: false };
});

describe("PreviewWindow · 预览独立页（#80 新窗口打开的落地）", () => {
  it("地址到手：全幅 iframe 出真页面，整页挂 light-lock（浅色锁定）", () => {
    previewResult = { data: { url: "http://localhost:42659" }, isPending: false, isError: false };

    const html = renderWindow();

    expect(html).toContain("<iframe");
    expect(html).toContain('src="http://localhost:42659"');
    expect(html).toContain("light-lock");
  });

  it("地址未到（无错 / 未就绪 WSP_012）：接通中等待，非故障不打扰", () => {
    previewResult = { error: new ApiError({ status: 503, code: "WSP_012", message: "预览应用尚未就绪" }), isPending: false, isError: true };

    const html = renderWindow();

    expect(html).toContain("正在接通系统…");
    expect(html).not.toContain("<iframe");
    expect(html).not.toContain("预览暂时打不开");
  });

  it("真故障（非未就绪）：打不开口径，稍后自动重试", () => {
    previewResult = { error: new ApiError({ status: 500, code: "WSP_002", message: "环境后端操作失败" }), isPending: false, isError: true };

    const html = renderWindow();

    expect(html).toContain("预览暂时打不开，稍后会自动重试");
    expect(html).not.toContain("<iframe");
  });
});
