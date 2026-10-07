// @vitest-environment happy-dom
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { WorkClosing } from "@/lib/store/chat";

import { ClosingCard } from "./closing-card";

// 版本动作 hook 契约面 mock（设计会话恒无 version 不出控件——mock 只为 hook 契约面成立）
vi.mock("@/hooks/use-version", () => ({
  useStartVersionView: () => ({
    isPending: false,
    isError: false,
    data: undefined,
    mutate: vi.fn(),
    reset: vi.fn(),
  }),
  useStopVersionView: () => ({ mutate: vi.fn() }),
  useRollbackVersion: () => ({ mutate: vi.fn() }),
}));

/** 设计会话收尾载荷（#289 closing.drafts 扩载形）。 */
function designClosing(drafts: WorkClosing["drafts"]): WorkClosing {
  return {
    summary: "完成设计物：首页主视觉",
    prdChanged: false,
    systemChanged: false,
    files: [],
    durationMs: 95_000,
    drafts,
  };
}

function renderCard(card: WorkClosing) {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <ClosingCard closing={card} projectId="100" />
    </QueryClientProvider>,
  );
}

afterEach(cleanup);

describe("ClosingCard · 稿清单交互（#290 设计会话收尾卡：去向直看＋长清单展开）", () => {
  const sevenDrafts = Array.from({ length: 7 }, (_, index) => ({
    item: "首页主视觉",
    media: (index % 2 === 0 ? "html" : "image") as "html" | "image",
    path: `/design/home-${index + 1}.${index % 2 === 0 ? "html" : "png"}`,
  }));

  it("图像稿行＝去向直看链接：平台文件服务 raw 直链、新窗口打开（未付费照看——门只盖下载面）；界面稿行不可点（raw 只伺服图片）", () => {
    renderCard(
      designClosing([
        { item: "首页主视觉", media: "image", path: "/design/home-hero-2.png" },
        { item: "首页主视觉", media: "html", path: "/design/home-1.html" },
      ]),
    );

    const link = screen.getByRole("link", { name: /home-hero-2\.png/ });
    expect(link.getAttribute("href")).toBe("/api/projects/100/files/raw?path=design%2Fhome-hero-2.png");
    expect(link.getAttribute("target")).toBe("_blank");
    // 界面稿不出直链（渲染式呈现归 #293 设计稿范式）——无可用链接即不出 role=link
    expect(screen.queryByRole("link", { name: /home-1\.html/ })).toBeNull();
    expect(screen.getByText(/文件区可看/)).toBeTruthy();
  });

  it("多稿长清单：默认五条、「查看全部 7 稿」点击展开全量、再点收起", () => {
    renderCard(designClosing(sevenDrafts));

    expect(screen.queryByText("home-6.png")).toBeNull(); // 五条之外收起
    fireEvent.click(screen.getByRole("button", { name: /查看全部 7 稿/ }));
    expect(screen.getByText("home-6.png")).toBeTruthy();
    expect(screen.getByText("home-7.html")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /收起/ }));
    expect(screen.queryByText("home-6.png")).toBeNull();
  });
});
