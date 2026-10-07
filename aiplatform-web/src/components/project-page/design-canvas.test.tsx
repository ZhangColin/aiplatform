import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import type { ProjectDetail } from "@/lib/projects/detail";
import type { WorkspaceFile } from "@/lib/projects/files";
import type { ChatMessage } from "@/lib/store/chat";

import { DesignCanvas } from "./design-canvas";

// 全系统画布（#293 验收②③）：多屏共置/代际并置的 SSR 断言——件标签、代标签、
// 稿卡（界面类＝固定画幅帧 iframe live 取件 raw 伺服通道、平面类＝raw 大图）、
// 定稿徽记与删除口、空态。拖排/缩放/平移/删除的浏览器腿交互契约归
// design-canvas.interaction.test（happy-dom）。树 fixture 用真实 API 相对形
//（find %P），稿清单携锚定形——两形归一在纯逻辑测试钉死。

const seed = vi.hoisted(() => ({
  detail: { designItems: [
    { ord: 1, title: "首页主视觉", status: "closed" },
    { ord: 2, title: "品牌 logo", status: "finalized", finalizedPath: "/design/123-logo.png" },
  ] } as Partial<ProjectDetail>,
  files: [
    { path: "design/home-1.html", size: 100 },
    { path: "design/home-2.html", size: 100 },
    { path: "design/home-v2-1.html", size: 100 },
    { path: "design/123-logo.png", size: 100 },
  ] as WorkspaceFile[],
  messages: [
    { kind: "closing", id: "c1", closing: { drafts: [
      { item: "首页主视觉", media: "html", path: "/design/home-1.html" },
      { item: "首页主视觉", media: "html", path: "/design/home-2.html" },
    ] } },
    { kind: "closing", id: "c2", closing: { drafts: [
      { item: "首页主视觉", media: "html", path: "/design/home-v2-1.html" },
    ] } },
    { kind: "closing", id: "c3", closing: { drafts: [
      { item: "品牌 logo", media: "image", path: "/design/123-logo.png" },
    ] } },
  ] as unknown as ChatMessage[],
}));

vi.mock("@/hooks/use-project", () => ({
  useProject: () => ({ data: seed.detail }),
}));
vi.mock("@/hooks/use-project-files", () => ({
  useProjectFiles: () => ({ data: seed.files, isPending: false }),
}));
vi.mock("@/hooks/use-delete-design-draft", () => ({
  useDeleteDesignDraft: () => ({ mutate: vi.fn(), isPending: false }),
}));
vi.mock("@/lib/store/chat", () => ({
  useChatStore: <T,>(selector: (state: { chats: Record<string, { messages: unknown[] }> }) => T): T =>
    selector({ chats: { p1: { messages: seed.messages } } }),
}));

function renderCanvas() {
  return renderToStaticMarkup(<DesignCanvas projectId="p1" />);
}

describe("DesignCanvas · 全系统画布 SSR（#293）", () => {
  it("多屏共置＋代际并置：件标签两条带、代标签分代（改稿注记）、卡按代就位", () => {
    const html = renderCanvas();

    expect(html).toContain("首页主视觉");
    expect(html).toContain("品牌 logo");
    expect(html).toContain("已定稿");
    expect((html.match(/第 1 代（改稿）|>第 1 代</g) ?? []).length).toBeGreaterThanOrEqual(1);
    expect(html).toContain("第 2 代（改稿）");
    // 三张界面稿帧＋一张平面稿图（各卡 data 属性锚定）
    expect(html.match(/data-draft-frame="\/design\/home-/g)).toHaveLength(3);
    expect(html.match(/data-draft-media="\/design\/123-logo\.png"/g)).toHaveLength(1);
  });

  it("界面类稿＝固定画幅帧：iframe 取件走 raw 稿伺服通道、1280×800、sandbox 无行为面", () => {
    const html = renderCanvas();

    expect(html).toContain(
      `src="/api/projects/p1/files/raw?path=${encodeURIComponent("design/home-1.html")}"`,
    );
    expect(html).toContain('sandbox=""');
    expect(html).toMatch(/width:1280/);
    expect(html).toMatch(/height:800/);
  });

  it("平面类稿＝raw 大图；稿名去 TSID 前缀（词干即稿名——名条内容）", () => {
    const html = renderCanvas();

    expect(html).toContain(
      `src="/api/projects/p1/files/raw?path=${encodeURIComponent("design/123-logo.png")}"`,
    );
    // 名条呈现词干（去 TSID 数字前缀），非完整落盘名
    expect(html).toContain(">logo.png<");
    expect(html).not.toContain(">123-logo.png<");
  });

  it("定稿卡：定稿徽记＋不出删除口；候选卡有删除口（悬卡可见）", () => {
    const html = renderCanvas();

    expect(html).toContain('data-draft-card="/design/123-logo.png"');
    expect(html).not.toContain('aria-label="删除logo.png"');
    expect(html).toContain('aria-label="删除home-1.html"');
  });

  it("件计数行：N/M 件已定稿", () => {
    expect(renderCanvas()).toContain("1/2 件已定稿");
  });
});

describe("DesignCanvas · 空态与消卡", () => {
  it("无稿＝空态（设计稿会在这里长出来），不出缩放控件", () => {
    seed.messages = [];
    seed.files = [];
    const html = renderCanvas();

    expect(html).toContain("设计稿会在这里长出来");
    expect(html).not.toContain("回到最新");
  });

  it("悬卡删除即消卡：文件树没有的稿不呈现（存在性正本是树）", () => {
    seed.messages = [
      { kind: "closing", id: "c1", closing: { drafts: [
        { item: "首页主视觉", media: "html", path: "/design/home-1.html" },
        { item: "首页主视觉", media: "html", path: "/design/home-2.html" },
      ] } },
      { kind: "closing", id: "c3", closing: { drafts: [
        { item: "品牌 logo", media: "image", path: "/design/123-logo.png" },
      ] } },
    ] as unknown as ChatMessage[];
    // 只留 home-2.html 在树——其余稿已被删
    seed.files = [{ path: "design/home-2.html", size: 100 }];
    const html = renderCanvas();

    expect(html).toContain('data-draft-card="/design/home-2.html"');
    expect(html).not.toContain('data-draft-card="/design/home-1.html"');
    // 整件滤空（品牌 logo 只此一稿被删）——件带不呈现
    expect(html).not.toContain('data-draft-card="/design/123-logo.png"');
  });
});
