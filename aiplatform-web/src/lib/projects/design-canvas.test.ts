import { describe, expect, it } from "vitest";

import type { DesignItemFact } from "@/lib/projects/detail";
import type { WorkspaceFile } from "@/lib/projects/files";
import type { ClosingDraft } from "@/lib/store/chat";

import { buildDesignCanvas, draftDisplayName } from "./design-canvas";

function draft(item: string, path: string, media: "html" | "image" = "html"): ClosingDraft {
  return { item, path, media };
}

function file(path: string): WorkspaceFile {
  return { path, size: 100 };
}

function item(overrides: Partial<DesignItemFact> & { title: string }): DesignItemFact {
  return { ord: 1, status: "closed", ...overrides };
}

describe("buildDesignCanvas · 件×代×稿派生（#293 全系统画布）", () => {
  it("多屏共置＋代际并置：按件分组、轮收口开新代、代内稿路径稳定序；两形归一比较", () => {
    // 灵魂用例（验收②）：各设计物的稿卡多屏共置（件分组）、代际并置（轮＝代，
    // 左→右）、代际可辨（gen 序保留）。存在性比较两形归一：树逐路径相对形
    //（find %P 的真实 API 形状）、稿清单携锚定形——精确匹配永不相等（真缺陷
    // 回归锚，绿测≠能跑）
    const canvas = buildDesignCanvas(
      [
        [draft("首页", "/design/home-2.html"), draft("首页", "/design/home-1.html")],
        [draft("首页", "/design/home-v2-1.html"), draft("海报", "/design/123-poster.png", "image")],
        [draft("首页", "/design/home-v2-1.html")], // 定稿收尾卡复述同稿——不开新代
      ],
      [
        file("design/home-1.html"),
        file("design/home-2.html"),
        file("design/home-v2-1.html"),
        file("design/123-poster.png"),
      ],
      [item({ title: "首页", ord: 1 }), item({ title: "海报", ord: 2 })],
    );

    expect(canvas).toEqual([
      {
        item: "首页",
        ord: 1,
        status: "closed",
        finalizedPath: undefined,
        gens: [
          {
            gen: 1,
            drafts: [
              { path: "/design/home-1.html", media: "html", item: "首页", gen: 1 },
              { path: "/design/home-2.html", media: "html", item: "首页", gen: 1 },
            ],
          },
          {
            gen: 2,
            drafts: [
              { path: "/design/home-v2-1.html", media: "html", item: "首页", gen: 2 },
            ],
          },
        ],
      },
      {
        item: "海报",
        ord: 2,
        status: "closed",
        finalizedPath: undefined,
        gens: [
          { gen: 1, drafts: [{ path: "/design/123-poster.png", media: "image", item: "海报", gen: 1 }] },
        ],
      },
    ]);
  });

  it("悬卡删除即消卡：文件树没有的稿不呈现；整代滤空不呈现、代序不回收", () => {
    const canvas = buildDesignCanvas(
      [
        [draft("首页", "/design/home-1.html"), draft("首页", "/design/home-2.html")],
        [draft("首页", "/design/home-v2-1.html")],
      ],
      // 第 1 代两张与第 2 代一张都被删——件分组保留（清单事实）、代全滤空不呈现
      [file("docs/PRD.md")],
      [item({ title: "首页" })],
    );

    expect(canvas).toEqual([
      { item: "首页", ord: 1, status: "closed", finalizedPath: undefined, gens: [] },
    ]);
  });

  it("部分删除：残代保留原代序（第 2 代单存不重排为第 1 代——代际可辨以史为锚）", () => {
    const canvas = buildDesignCanvas(
      [
        [draft("首页", "/design/home-1.html")],
        [draft("首页", "/design/home-v2-1.html")],
      ],
      [file("design/home-v2-1.html")],
      [item({ title: "首页" })],
    );

    expect(canvas[0]?.gens).toEqual([
      { gen: 2, drafts: [{ path: "/design/home-v2-1.html", media: "html", item: "首页", gen: 2 }] },
    ]);
  });

  it("定稿事实挂件：finalized 件带 finalizedPath（画布定稿徽记与不可删判据）", () => {
    const canvas = buildDesignCanvas(
      [[draft("logo", "/design/123-logo.png", "image")]],
      [file("design/123-logo.png")],
      [item({ title: "logo", status: "finalized", finalizedPath: "/design/123-logo.png" })],
    );

    expect(canvas[0]).toMatchObject({ status: "finalized", finalizedPath: "/design/123-logo.png" });
  });

  it("清单演进措辞漂移：旧标题件按到达序殿后（ord null、无状态面）；清单 null 同律", () => {
    const canvas = buildDesignCanvas(
      [
        [draft("旧版首页标题", "/design/old.html")],
        [draft("首页", "/design/home-1.html")],
      ],
      [file("design/old.html"), file("design/home-1.html")],
      [item({ title: "首页" })],
    );

    expect(canvas.map((entry) => [entry.item, entry.ord])).toEqual([
      ["首页", 1],
      ["旧版首页标题", null],
    ]);
    expect(canvas[1]?.status).toBeNull();
  });

  it("文件树未达（undefined）＝不呈现任何稿（存在性正本是树，不预呈）", () => {
    expect(buildDesignCanvas([[draft("首页", "/design/home-1.html")]], undefined, null)).toEqual(
      [],
    );
  });

  it("空稿轮（改稿 0 稿合法收口）不开代；无 rounds＝无件（清单件仍入列）", () => {
    const canvas = buildDesignCanvas(
      [undefined, []],
      [],
      [item({ title: "首页", status: "pending" })],
    );
    // 件行在（清单事实、画布件带标注）、零代零稿
    expect(canvas).toEqual([
      { item: "首页", ord: 1, status: "pending", finalizedPath: undefined, gens: [] },
    ]);
  });
});

describe("draftDisplayName · 稿卡名（呈现）", () => {
  it("图片稿去 TSID 数字前缀（转存防撞名——词干才是稿名）；HTML 稿原样", () => {
    expect(draftDisplayName("/design/3987654321-海报主视觉.png")).toBe("海报主视觉.png");
    expect(draftDisplayName("/design/home-1.html")).toBe("home-1.html");
    // 前导数字不带连字符不是 TSID 前缀（如 v2 落名），原样
    expect(draftDisplayName("/design/2026版海报.png")).toBe("2026版海报.png");
  });
});
