// @vitest-environment happy-dom
import { cleanup, fireEvent, render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { PrdNoticesState } from "@/lib/store/prd-notices";

import { FilesPanel } from "./files-panel";

// 文件树浏览（#27 文件模式）：左侧树（目录缩进展开、文件随 run 长出）+ 右侧
// 内容（PRD 走 markdown 修订回路、其余文本点看）。PRD 呈现与 markSeen 兜底
// 口径自 #20 PrdPanel 平移。数据口全 mock；zustand 直读种子状态渲染（v5 server
// snapshot 限制同 workbench-shell.test）。
const seed = vi.hoisted(() => ({
  notices: { seen: {}, pending: {} } as Pick<PrdNoticesState, "seen" | "pending">,
  files: {
    data: [
      { path: "AGENTS.md", size: 7 },
      { path: "docs/PRD.md", size: 1234 },
      { path: "src/app/page.tsx", size: 340 },
    ] as { path: string; size: number }[] | undefined,
    isPending: false,
  },
  prd: {
    data: { content: "# 需求背景\n给宠物医院做预约管理系统。", updatedAt: "2026-08-31T08:00:00Z" },
    isPending: false,
  } as { data?: { content?: string; updatedAt?: string } | null; isPending: boolean },
  content: {
    byPath: {} as Record<string, { data?: { path: string; content: string }; isPending: boolean }>,
  },
  markSeen: vi.fn(),
}));

vi.mock("@/hooks/use-project-files", () => ({
  useProjectFiles: () => seed.files,
}));

vi.mock("@/hooks/use-prd", () => ({
  usePrd: () => seed.prd,
}));

vi.mock("@/hooks/use-project-file-content", () => ({
  useProjectFileContent: (_projectId: string, path: string | null) =>
    seed.content.byPath[path ?? ""] ?? { isPending: true },
}));

vi.mock("@/lib/store/prd-notices", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/store/prd-notices")>();
  return {
    ...actual,
    usePrdNoticesStore: <T,>(selector: (state: Pick<PrdNoticesState, "seen" | "pending"> & { markSeen: typeof seed.markSeen }) => T): T =>
      selector({ ...seed.notices, markSeen: seed.markSeen }),
  };
});

beforeEach(() => {
  seed.notices = { seen: {}, pending: {} };
  seed.files = {
    data: [
      { path: "AGENTS.md", size: 7 },
      { path: "docs/PRD.md", size: 1234 },
      { path: "src/app/page.tsx", size: 340 },
    ],
    isPending: false,
  };
  seed.prd = {
    data: { content: "# 需求背景\n给宠物医院做预约管理系统。", updatedAt: "2026-08-31T08:00:00Z" },
    isPending: false,
  };
  seed.content.byPath = {
    "src/app/page.tsx": { data: { path: "src/app/page.tsx", content: "export default function Page() {}" }, isPending: false },
  };
  seed.markSeen.mockClear();
});

afterEach(() => cleanup());

describe("FilesPanel · 文件树浏览（#27）", () => {
  it("缺省选中 PRD：markdown 正文直出 + 树上目录合成、PRD 祖先自动展开", () => {
    const { container } = render(<FilesPanel projectId="p1" />);

    // PRD 内容与文件标识（#20 口径平移）
    expect(container.textContent).toContain("给宠物医院做预约管理系统。");
    expect(container.textContent).toContain("docs/PRD.md");
    expect(container.querySelector("h1")?.textContent).toBe("需求背景");
    // 树：根级目录（docs 已随选中祖先展开见 PRD.md；src 收起）+ 根级文件
    expect(container.textContent).toContain("AGENTS.md");
    expect(container.querySelector('button[data-tree-dir="docs"]')).not.toBeNull();
    expect(container.querySelector('button[data-tree-dir="src"]')).not.toBeNull();
    expect(container.querySelector('button[data-tree-file="docs/PRD.md"]')).not.toBeNull();
    // src 非选中祖先：收起，深层文件不露
    expect(container.querySelector('button[data-tree-file="src/app/page.tsx"]')).toBeNull();
  });

  it("点其他文件 → 内容端点正文呈现；目录收起/展开可点", () => {
    const { container } = render(<FilesPanel projectId="p1" />);

    // docs 随选中祖先自动展开：手动收起 → PRD.md 行隐藏，再点展开复现
    fireEvent.click(container.querySelector('button[data-tree-dir="docs"]')!);
    expect(container.querySelector('button[data-tree-file="docs/PRD.md"]')).toBeNull();
    fireEvent.click(container.querySelector('button[data-tree-dir="docs"]')!);
    expect(container.querySelector('button[data-tree-file="docs/PRD.md"]')).not.toBeNull();

    // 展开未选中的祖先链（src → src/app）后点 page.tsx → 文本正文（pre 直出）
    fireEvent.click(container.querySelector('button[data-tree-dir="src"]')!);
    fireEvent.click(container.querySelector('button[data-tree-dir="src/app"]')!);
    fireEvent.click(container.querySelector('button[data-tree-file="src/app/page.tsx"]')!);
    expect(container.textContent).toContain("export default function Page() {}");
  });

  it("修正删掉选中文件后树刷新：选中回缺省 PRD（选中保持纯逻辑的面呈现）", () => {
    const { container } = render(<FilesPanel projectId="p1" />);
    fireEvent.click(container.querySelector('button[data-tree-file="AGENTS.md"]')!);

    // 修正 run 收口 → files 失效重拉，AGENTS.md 没了（选中保持归纯逻辑测试）
    seed.files = { data: [{ path: "docs/PRD.md", size: 1300 }], isPending: false };
    const rerendered = render(<FilesPanel projectId="p1" />);
    expect(rerendered.container.textContent).toContain("给宠物医院做预约管理系统。");
  });

  it("PRD 有未认领修订：「已更新」标记；PRD 数据落位即 markSeen（挂载兜底）", () => {
    seed.notices = { seen: { p1: true }, pending: { p1: true } };
    const { container } = render(<FilesPanel projectId="p1" />);
    expect(container.textContent).toContain("已更新");
    expect(seed.markSeen).toHaveBeenCalledWith("p1");

    seed.notices = { seen: { p1: true }, pending: {} };
    const plain = render(<FilesPanel projectId="p1" />);
    expect(plain.container.textContent).not.toContain("已更新");
  });

  it("空树（工作区还没文件）：占位引导，不报错", () => {
    seed.files = { data: [], isPending: false };
    seed.prd = { data: null, isPending: false };
    const { container } = render(<FilesPanel projectId="p1" />);
    expect(container.textContent).toContain("PRD 与系统文件会随进展出现在这里");
  });

  it("加载中：骨架占位（无正文闪现）", () => {
    seed.files = { data: undefined, isPending: true };
    const { container } = render(<FilesPanel projectId="p1" />);
    expect(container.querySelector('[data-slot="skeleton"]')).toBeTruthy();
  });
});
