import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import type { WorkClosing } from "@/lib/store/chat";

import { ClosingCard } from "./closing-card";

// 版本动作 hook 契约面 mock（起/停快照、回滚——请求面归 use-version 与后端测试，
// 本测试只验收尾卡形态与版本控件的成版锚点门控）
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

function closing(overrides: Partial<WorkClosing> = {}): WorkClosing {
  return {
    summary: "修订了需求文档，并更新了系统",
    prdChanged: true,
    prdNote: "配送范围改为全国",
    systemChanged: true,
    systemNote: "下单页新增配送范围说明",
    files: [
      { path: "/src/App.jsx", added: 4, removed: 0 },
      { path: "/src/pages/Orders.jsx", added: 12, removed: 3 },
    ],
    durationMs: 183_420,
    ...overrides,
  };
}

function renderCard(card: WorkClosing) {
  return renderToStaticMarkup(
    <QueryClientProvider client={new QueryClient()}>
      <ClosingCard closing={card} projectId="100" />
    </QueryClientProvider>,
  );
}

/**
 * 收尾卡四要素（#88 AC①）：摘要 / 判定行（服务端权威值直译，非前端推导）/
 * 变更清单（文件级 +N −M）/ 轮末统计（时长 + 文件数 + 变更行数 + 检查通过）
 * + 版本控件（#92/#93：成版锚点 version 在场才出「查看当时 / 回滚到此」）
 * ——SSR 断言（形态轻、事件→状态归桥接测试，Testing Decisions 口径）。
 */
describe("ClosingCard · 四要素（#88 定格收口）", () => {
  it("四要素齐：摘要、判定行（带原因）、变更清单、轮末统计", () => {
    const html = renderCard(closing());

    expect(html).toContain("本轮完成");
    expect(html).toContain("修订了需求文档，并更新了系统"); // 摘要（合并叙事）
    expect(html).toContain("需求文档：");
    expect(html).toContain("已修订");
    expect(html).toContain("配送范围改为全国"); // 判定行·PRD 原因（权威值）
    expect(html).toContain("系统：");
    expect(html).toContain("已更新");
    expect(html).toContain("下单页新增配送范围说明"); // 判定行·系统原因
    expect(html).toContain("/src/pages/Orders.jsx"); // 变更清单
    expect(html).toContain("+12");
    expect(html).toContain("−3");
    expect(html).toContain("2"); // 文件数（无独立断言面，随清单断言兜住）
    expect(html).toContain("3 分 03 秒"); // 轮末统计·时长（183420ms）
    expect(html).toContain("+16"); // 变更行数（4 + 12）
    expect(html).toContain("检查通过"); // 自检终值计入统计（closing ⟺ 核验通过）
  });

  it("系统无需改动轮：判定行如实呈现原因、空清单不出清单区与文件统计", () => {
    const html = renderCard(
      closing({
        summary: "本轮系统无需改动",
        prdChanged: false,
        prdNote: undefined,
        systemChanged: false,
        systemNote: "页面上没有写死配送范围，都以文档为准",
        files: [],
      }),
    );

    expect(html).toContain("本轮系统无需改动");
    expect(html).toContain("未修订");
    expect(html).toContain("无需改动");
    expect(html).toContain("页面上没有写死配送范围，都以文档为准");
    expect(html).not.toContain("个文件");
    expect(html).not.toContain("查看全部");
    expect(html).toContain("用时"); // 时长统计恒在
  });

  it("生成轮：摘要「首次生成了系统」、判定行无原因注脚（说明缺省）", () => {
    const html = renderCard(
      closing({
        summary: "首次生成了系统",
        prdChanged: false,
        prdNote: undefined,
        systemChanged: true,
        systemNote: undefined,
        files: [{ path: "/src/App.jsx", added: 40, removed: 0 }],
      }),
    );

    expect(html).toContain("首次生成了系统");
    expect(html).toContain("已更新");
    expect(html).toContain("+40");
  });

  it("长清单默认收五条，「查看全部 N 个文件」收进折叠", () => {
    const files = Array.from({ length: 7 }, (_, index) => ({
      path: `/src/File${index + 1}.jsx`,
      added: 1,
      removed: 0,
    }));
    const html = renderCard(closing({ files }));

    expect(html).toContain("/src/File5.jsx");
    expect(html).not.toContain("/src/File6.jsx"); // 五条之外收进折叠（SSR 默认收起）
    expect(html).toContain("查看全部 7 个文件");
  });
});

describe("ClosingCard · 自测统计（#96 清单式播报的收尾统计）", () => {
  it("自测统计在场：轮末统计带「自测 N 项」（只记项数，不伪报通过/未过）", () => {
    const html = renderCard(closing({ selfTest: { total: 3 } }));

    expect(html).toContain("自测 3 项");
  });

  it("自测缺省（子智能体未跑）：不出自测统计行", () => {
    const html = renderCard(closing());

    expect(html).not.toContain("自测");
    expect(html).toContain("检查通过"); // 平台收口判据（8081 探活）恒在
  });
});

describe("ClosingCard · 版本控件（#92/#93）", () => {
  it("成版锚点（version）在场：出「查看当时 / 回滚到此」两动作", () => {
    const html = renderCard(closing({ version: "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1" }));

    expect(html).toContain("查看当时");
    expect(html).toContain("回滚到此");
  });

  it("成版失败（缺 version 键）：不出版本控件", () => {
    const html = renderCard(closing({ version: undefined }));

    expect(html).not.toContain("查看当时");
    expect(html).not.toContain("回滚到此");
  });
});

describe("ClosingCard · 定稿收尾卡（#291 显式动作收口：成版锚＋后续触发行）", () => {
  /** 定稿收尾载荷（#291 平台侧发射形）：closing 携 version（成版锚）与 drafts 单条（triggers）。 */
  function finalizeClosing(triggers?: string[]): WorkClosing {
    return closing({
      summary: "定稿设计物：首页主视觉",
      prdChanged: false,
      prdNote: undefined,
      systemChanged: false,
      systemNote: undefined,
      files: [],
      durationMs: 320,
      version: "abc123def456",
      drafts: [
        { item: "首页主视觉", media: "html", path: "/design/home-2.html", triggers },
      ],
    });
  }

  it("定稿稿行＋「已触发」行＋版本控件（成版锚在场——「查看当时」即见定稿稿）", () => {
    const html = renderCard(finalizeClosing(["设计已全部定稿，可以确认下单了"]));

    expect(html).toContain("定稿设计物：首页主视觉"); // 定稿叙事
    expect(html).toContain("home-2.html"); // 定稿稿单条
    expect(html).toContain("已触发：设计已全部定稿，可以确认下单了"); // 后续触发事实
    // 版本控件：定稿例外携带 version——候选轮不出、定稿轮出（ADR-0025 只有定稿成版）
    expect(html).toContain("查看当时");
    expect(html).toContain("回滚到此");
  });

  it("triggers 空（未触发分岔——如非全部定稿的中间定稿）：不出「已触发」行", () => {
    const html = renderCard(finalizeClosing(undefined));

    expect(html).toContain("定稿设计物：首页主视觉");
    expect(html).not.toContain("已触发：");
    expect(html).toContain("查看当时"); // 成版锚仍在（定稿即成版，与触发无关）
  });

  it("多触发并置以「；」连缀（全部定稿起构建后仍可按稿对齐的复合场景面）", () => {
    const html = renderCard(finalizeClosing(["系统已开始按定稿设计对齐", "系统更新已排入下一轮"]));

    expect(html).toContain("已触发：系统已开始按定稿设计对齐；系统更新已排入下一轮");
  });
});

describe("ClosingCard · 稿清单与去向（#290 设计会话收尾卡扩载呈现）", () => {
  /** 设计会话收尾载荷（#289 closing.drafts 扩载形）：判定行恒「未动」、文件清单与稿同集。 */
  function designClosing(drafts: WorkClosing["drafts"]): WorkClosing {
    return closing({
      summary: "完成设计物：首页主视觉",
      prdChanged: false,
      prdNote: undefined,
      systemChanged: false,
      systemNote: "完成设计物：首页主视觉",
      files: [{ path: "/design/home-1.html", added: 120, removed: 0 }],
      durationMs: 95_000,
      version: undefined,
      drafts,
    });
  }

  it("稿清单在场：每稿一行（文件名＋形态标签）、图像稿 raw 直看链接、稿数统计；判定行/文件清单/版本控件让位", () => {
    const html = renderCard(
      designClosing([
        { item: "首页主视觉", media: "html", path: "/design/home-1.html" },
        { item: "首页主视觉", media: "image", path: "/design/home-hero-2.png" },
      ]),
    );

    expect(html).toContain("完成设计物：首页主视觉"); // 摘要（本场设计物叙事）
    // 稿清单：文件名＋形态标签（用户语言——界面稿/图像稿，不出 html/png 工程词）
    expect(html).toContain("home-1.html");
    expect(html).toContain("界面稿");
    expect(html).toContain("home-hero-2.png");
    expect(html).toContain("图像稿");
    // 去向＝平台文件服务 raw 直看（未付费照看：门只盖下载面，#287）——图像稿可点
    expect(html).toContain('href="/api/projects/100/files/raw?path=design%2Fhome-hero-2.png"');
    // 界面稿不出直链（raw 只伺服图片 PRJ_038——渲染式呈现归 #293 稿伺服通道）：
    // 如实提示文件区可看，不伪造不可用入口
    expect(html).not.toContain('href="/api/projects/100/files/raw?path=design%2Fhome-1.html"');
    expect(html).toContain("文件区可看");
    // 轮末统计：时长＋稿数（设计变体不出「检查通过」与文件行数）
    expect(html).toContain("本轮");
    expect(html).toContain("2");
    expect(html).toContain("稿");
    expect(html).toContain("1 分 35 秒");
    // 让位面：判定行（PRD/系统恒未动的结构常量）与文件清单（与稿同集重复）不出
    expect(html).not.toContain("需求文档：");
    expect(html).not.toContain("系统：");
    expect(html).not.toContain("+120");
    expect(html).not.toContain("个文件");
    expect(html).not.toContain("检查通过");
    // 设计候选不自动成版（ADR-0025）：版本控件不出
    expect(html).not.toContain("查看当时");
    expect(html).not.toContain("回滚到此");
  });

  it("多稿长清单默认收五条，「查看全部 N 稿」收进折叠", () => {
    const drafts = Array.from({ length: 7 }, (_, index) => ({
      item: "首页主视觉",
      media: "image" as const,
      path: `/design/home-${index + 1}.png`,
    }));
    const html = renderCard(designClosing(drafts));

    expect(html).toContain("home-5.png");
    expect(html).not.toContain("home-6.png"); // 五条之外收进折叠（SSR 默认收起）
    expect(html).toContain("查看全部 7 稿");
  });

  it("drafts 缺省（编码 run）：四要素照旧——判定行/文件清单在、无稿清单区", () => {
    const html = renderCard(closing());

    expect(html).toContain("需求文档：");
    expect(html).toContain("/src/App.jsx");
    expect(html).not.toContain("界面稿");
    expect(html).not.toContain("图像稿");
    expect(html).not.toContain("查看全部 7 稿");
  });

  it("drafts 空数组（畸形载荷防御面）：仍走设计形态如实「本轮 0 稿」——不闪编码 run 语料", () => {
    const html = renderCard(designClosing([]));

    expect(html).toContain("完成设计物：首页主视觉");
    expect(html).toMatch(/本轮 <b[^>]*>0<\/b> 稿/);
    expect(html).not.toContain("需求文档："); // 判定行不出（设计收口不是编码轮语料）
    expect(html).not.toContain("检查通过");
    expect(html).not.toContain("查看全部"); // 零稿行不出清单区
  });
});
