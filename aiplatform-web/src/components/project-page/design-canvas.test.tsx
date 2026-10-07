import { renderToStaticMarkup } from "react-dom/server";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { ProjectDetail } from "@/lib/projects/detail";
import type { WorkspaceFile } from "@/lib/projects/files";
import type { ChatMessage } from "@/lib/store/chat";

import { DesignCanvas } from "./design-canvas";

// 全系统画布（#293 验收②③；#294 渐进长出＋点哪改哪＋定稿）：多屏共置/代际并置
// 的 SSR 断言——件标签、代标签、稿卡（界面类＝固定画幅帧 iframe live 取件 raw
// 伺服通道、平面类＝raw 大图）、定稿徽记与删除口/定稿口、空态、live 占位卡与
// 在途稿（incoming）。拖排/缩放/平移/删除/点选/定稿确认的浏览器腿交互契约归
// design-canvas.interaction.test（happy-dom）。树 fixture 用真实 API 相对形
//（find %P），稿清单携锚定形——两形归一在纯逻辑测试钉死。work/scope store 走
// mock（renderToStaticMarkup 下 zustand useSyncExternalStore 只读创建时快照，
// setState 不达 SSR——chat store mock 同款形态）。

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
  /** work store 投影（渐进长出的驱动事实——live designer 会话形状）。 */
  work: undefined as
    | undefined
    | {
        runId: string;
        frozen: boolean;
        parts: { kind: "action"; id: string; toolCallId: string; toolName: string;
          state: string; label: string }[];
        seenEventIds: string[];
        seat: "designer";
        slice?: { title: string; index?: number; total?: number };
      },
  /** scope store 投影（点哪改哪的选中态）。 */
  scope: undefined as undefined | { ord: number; itemTitle: string },
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
vi.mock("@/hooks/use-finalize-design-item", () => ({
  useFinalizeDesignItem: () => ({ mutate: vi.fn(), isPending: false }),
}));
vi.mock("@/lib/store/chat", () => ({
  useChatStore: <T,>(selector: (state: { chats: Record<string, { messages: unknown[] }> }) => T): T =>
    selector({ chats: { p1: { messages: seed.messages } } }),
}));
vi.mock("@/lib/store/work-message", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/store/work-message")>();
  return {
    ...actual, // DRAFT_WRITING_TOOLS 等常量保真（rev 戳口径与真身同源）
    useWorkMessageStore: <T,>(selector: (state: { works: Record<string, unknown> }) => T): T =>
      selector({ works: { p1: seed.work } }),
  };
});
vi.mock("@/lib/store/design-scope", () => ({
  useDesignScopeStore: <T,>(selector: (state: { scopes: Record<string, unknown> }) => T): T =>
    selector({ scopes: { p1: seed.scope } }),
}));

function renderCanvas() {
  return renderToStaticMarkup(<DesignCanvas projectId="p1" />);
}

/** 逐例重置 seed（渐进长出用例会改写目标件与树——不互染）。 */
beforeEach(() => {
  seed.detail = { designItems: [
    { ord: 1, title: "首页主视觉", status: "closed" },
    { ord: 2, title: "品牌 logo", status: "finalized", finalizedPath: "/design/123-logo.png" },
  ] } as Partial<ProjectDetail>;
  seed.files = [
    { path: "design/home-1.html", size: 100 },
    { path: "design/home-2.html", size: 100 },
    { path: "design/home-v2-1.html", size: 100 },
    { path: "design/123-logo.png", size: 100 },
  ];
  seed.messages = [
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
  ] as unknown as ChatMessage[];
  seed.work = undefined;
  seed.scope = undefined;
});

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

  it("定稿口（#294）：候选卡带显式定稿动作，定稿卡不出", () => {
    const html = renderCanvas();

    expect(html).toContain('data-finalize-open="1"');
    expect(html).not.toContain('data-finalize-open="2"'); // 已定稿件——只可再换稿时重开
  });

  it("点开预览口（#294）：候选卡带预览放大钮", () => {
    const html = renderCanvas();

    expect(html).toContain('data-preview-open="/design/home-1.html"');
  });

  it("件计数行：N/M 件已定稿", () => {
    expect(renderCanvas()).toContain("1/2 件已定稿");
  });
});

describe("DesignCanvas · 渐进长出 SSR（#294）", () => {
  it("live 首产：目标件占位卡（正在出稿）＋树上未见稿提升为在途卡（正在写…）", () => {
    seed.messages = [];
    seed.files = [{ path: "design/poster-1.html", size: 10 }];
    seed.detail = { designItems: [
      { ord: 1, title: "首页主视觉", status: "closed" },
      { ord: 2, title: "电商海报", status: "pending" },
    ] } as Partial<ProjectDetail>;
    // live designer run：切片标题＝电商海报（目标件）
    seed.work = {
      runId: "r1",
      frozen: false,
      parts: [],
      seenEventIds: [],
      seat: "designer",
      slice: { title: "电商海报", index: 2, total: 2 },
    };

    const html = renderCanvas();

    // 占位卡（进行中第一形态）＋在途稿卡（正在写标记）
    expect(html).toContain('data-draft-placeholder="电商海报"');
    expect(html).toContain("正在出稿…");
    expect(html).toContain('data-draft-card="/design/poster-1.html"');
    expect(html).toContain("正在写…");
    // 在途稿无删除口无定稿口（未收口——整理与定稿都是对已到达候选的动作）
    expect(html).not.toContain('aria-label="删除poster-1.html"');
    expect(html).not.toContain("data-finalize-open");
  });

  it("在途界面稿随 rev 戳取件（写动作收口数——帧内容版本）；平面类在途稿即大图", () => {
    seed.messages = [];
    seed.files = [{ path: "design/poster-1.html", size: 10 }, { path: "design/777-poster.png", size: 10 }];
    seed.detail = { designItems: [{ ord: 2, title: "电商海报", status: "pending" }] } as Partial<ProjectDetail>;
    seed.work = {
      runId: "r1",
      frozen: false,
      parts: [
        {
          kind: "action",
          id: "a1",
          toolCallId: "t1",
          toolName: "write_file",
          state: "completed",
          label: "编写【poster-1】",
        },
        {
          kind: "action",
          id: "a2",
          toolCallId: "t2",
          toolName: "generate_image",
          state: "completed",
          label: "出图【poster】",
        },
        {
          kind: "action",
          id: "a3",
          toolCallId: "t3",
          toolName: "write_file",
          state: "running",
          label: "编写【poster-3】",
        },
      ],
      seenEventIds: [],
      seat: "designer",
      slice: { title: "电商海报", index: 1, total: 1 },
    };

    const html = renderCanvas();

    // rev=2（两次写动作完成）进在途稿取件地址（静态标记 & 转义）；占位活性行
    // 语料＝正在出第 3 稿（两张已落＋第三张在写——与直播卡活性行同口径）
    expect(html).toContain(
      `src="/api/projects/p1/files/raw?path=${encodeURIComponent("design/poster-1.html")}&amp;v=2"`,
    );
    expect(html).toContain("正在出第 3 稿…");
    // 平面类在途稿＝一次到位的大图（img 直出、无 rev）
    expect(html).toContain(
      `src="/api/projects/p1/files/raw?path=${encodeURIComponent("design/777-poster.png")}"`,
    );
  });

  it("收口定格（frozen）＝非 live：占位退场、树上未见稿不再提升", () => {
    seed.messages = [];
    seed.files = [{ path: "design/orphan.html", size: 10 }];
    seed.detail = { designItems: [{ ord: 1, title: "首页主视觉", status: "closed" }] } as Partial<ProjectDetail>;
    seed.work = {
      runId: "r1",
      frozen: true, // run-finish 已定格——收尾卡轮接管锚定
      parts: [],
      seenEventIds: [],
      seat: "designer",
      slice: { title: "首页主视觉" },
    };

    const html = renderCanvas();

    expect(html).not.toContain("data-draft-placeholder");
    expect(html).not.toContain('data-draft-card="/design/orphan.html"');
    expect(html).toContain("设计稿会在这里长出来");
  });
});

describe("DesignCanvas · 作用域选中态 SSR（#294 点哪改哪）", () => {
  it("作用域件的稿卡带已选中徽记与主色卡环", () => {
    seed.scope = { ord: 1, itemTitle: "首页主视觉" };

    const html = renderCanvas();

    expect(html).toContain("已选中");
    expect(html).toMatch(/data-draft-card="\/design\/home-1\.html"[^>]*ring-2/);
    // 非作用域件的卡不带选中态
    expect(html).not.toMatch(/data-draft-card="\/design\/123-logo\.png"[^>]*ring-2/);
  });
});

describe("DesignCanvas · 空态与消卡", () => {
  it("无稿＝空态（设计稿会在这里长出来），不出缩放控件", () => {
    seed.messages = [];
    seed.files = [];
    seed.detail = { designItems: [] } as Partial<ProjectDetail>;
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
