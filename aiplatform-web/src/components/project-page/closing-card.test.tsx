import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, describe, expect, it, vi } from "vitest";

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
  useVersionViewSession: () => ({
    startView: { isPending: false, isError: false, data: undefined, mutate: vi.fn(), reset: vi.fn() },
    start: vi.fn(),
    finish: vi.fn(),
  }),
}));

// 预览入口（#617）取既有预览查询：此处替身直给 URL 就绪态（请求面归
// use-project-preview 测试）
const previewState = vi.hoisted(() => ({
  data: undefined as { url?: string } | undefined,
  refetch: vi.fn(),
}));
vi.mock("@/hooks/use-project-preview", () => ({
  useProjectPreview: () => previewState,
}));

afterEach(() => {
  previewState.data = undefined;
});

function closing(overrides: Partial<WorkClosing> = {}): WorkClosing {
  return {
    narrative: "本次交付了**下单与配送范围**说明。",
    criteria: { buildPassed: true, previewPassed: true },
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
 * 收尾卡五字段（#617）：用时 / 判据逐件（build＋预览）/ 变更清单合计 / 内核自述
 * （markdown）/ 预览入口（未就绪禁用、就绪可开）。SSR 断言（形态轻；点击开窗归
 * 交互测试）。
 */
describe("ClosingCard · 五字段（#617）", () => {
  it("五字段齐：标题「生成完成」、判据逐件、内核自述 markdown、清单合计、用时、预览入口", () => {
    const html = renderCard(closing());

    expect(html).toContain("生成完成");
    // 判据逐件如实
    expect(html).toContain("构建");
    expect(html).toContain("通过");
    expect(html).toContain("预览");
    expect(html).toContain("可用");
    // 内核自述：markdown 渲染（非源码记号）
    expect(html).toContain("内核自述");
    expect(html).toContain("<strong>下单与配送范围</strong>");
    expect(html).not.toContain("**下单与配送范围**");
    // 变更清单：文件级 ±行数 + 合计（文件数/行数在清单头一次呈现）
    expect(html).toContain("/src/pages/Orders.jsx");
    expect(html).toContain("+12");
    expect(html).toContain("−3");
    expect(html).toContain("2</b> 个文件"); // 合计·文件数
    expect(html).toContain("+16"); // 合计·新增行数（4 + 12）
    expect(html).toContain("−3"); // 合计·删除行数
    // 用时（183420ms）
    expect(html).toContain("用时");
    expect(html).toContain("3 分 03 秒");
    // 预览入口：未就绪禁用
    expect(html).toContain('aria-label="在新窗口打开预览"');
    expect(html).toContain('disabled=""');
  });

  it("内核自述为空：不出该块（其余字段照常）", () => {
    const html = renderCard(closing({ narrative: undefined }));

    expect(html).not.toContain("内核自述");
    expect(html).toContain("生成完成");
    expect(html).toContain("用时");
  });

  it("判据未过如实呈现（组件不写死「通过」）", () => {
    const html = renderCard(
      closing({ criteria: { buildPassed: false, previewPassed: true } }),
    );

    expect(html).toContain("未通过");
    expect(html).toContain("可用");
  });

  it("空清单不出清单区（如系统无实际文件改动的轮）", () => {
    const html = renderCard(closing({ files: [] }));

    expect(html).not.toContain("变更清单");
    expect(html).not.toContain("个文件");
    expect(html).toContain("用时");
  });

  it("patch 超限省略：行数保留、卡上标注「diff 过大」（#619）", () => {
    const html = renderCard(
      closing({ files: [{ path: "/src/Huge.jsx", added: 500, removed: 0, patchOmitted: true }] }),
    );

    expect(html).toContain("/src/Huge.jsx");
    expect(html).toContain("+500");
    expect(html).toContain("diff 过大");
  });

  it("变更清单不可用：标注不可用、不出合计与文件行，其余字段照常（#619 降级）", () => {
    const html = renderCard(closing({ files: [], filesUnavailable: true }));

    expect(html).toContain("变更清单不可用");
    expect(html).not.toContain("个文件");
    expect(html).toContain("生成完成");
    expect(html).toContain("用时");
  });

  it("首建目录汇总：默认按目录呈现（目录、文件数、± 行数），不裸列文件（#620）", () => {
    const html = renderCard(
      closing({
        files: [
          { path: "/src/App.jsx", added: 4, removed: 0 },
          { path: "/src/pages/Orders.jsx", added: 12, removed: 3 },
          { path: "/public/logo.png", added: 0, removed: 0 },
        ],
        directories: [
          { path: "/public", fileCount: 1, added: 0, removed: 0 },
          { path: "/src", fileCount: 1, added: 4, removed: 0 },
          { path: "/src/pages", fileCount: 1, added: 12, removed: 3 },
        ],
      }),
    );

    // 目录行（目录、文件数、± 行数）
    expect(html).toContain("/public");
    expect(html).toContain("/src/pages");
    expect(html).toContain("1 个文件");
    expect(html).toContain("+12");
    // 百文件不裸列：文件级默认收起，不出文件全路径行
    expect(html).not.toContain("/src/App.jsx");
    expect(html).not.toContain("/src/pages/Orders.jsx");
    // 合计仍按真实文件计（3 个文件，+16 −3）
    expect(html).toContain("3</b> 个文件");
    expect(html).toContain("+16");
  });

  it("迭代文件级清单（无 directories）：照旧文件级＋合计，不出目录行", () => {
    const html = renderCard(closing());

    expect(html).toContain("/src/pages/Orders.jsx");
    expect(html).not.toContain("展开 /src 目录");
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

  it("预览就绪：入口可点（未就绪的 disabled 退场）", () => {
    previewState.data = { url: "https://preview.example.com/" };
    const html = renderCard(closing());

    expect(html).toContain('aria-label="在新窗口打开预览"');
    expect(html).not.toContain('disabled=""');
  });
});

describe("ClosingCard · 版本控件（#92/#93，存量旧卡）", () => {
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
