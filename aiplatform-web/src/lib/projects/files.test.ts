import { describe, expect, it } from "vitest";

import {
  PRD_PATH,
  buildFileTree,
  formatFileSize,
  isAncestorDir,
  selectableFile,
  type FileTreeDir,
  type FileTreeFile,
  type WorkspaceFile,
} from "./files";

// 文件树浏览（#27 文件模式）：树构建/选中保持/祖先展开判定——纯逻辑，目录由
// 文件路径段合成（后端只列文件），排序目录先、同级按名代码点序（与后端一致）。

const files: WorkspaceFile[] = [
  { path: "AGENTS.md", size: 7 },
  { path: "docs/PRD.md", size: 12 },
  { path: "src/app/page.tsx", size: 340 },
  { path: "src/index.ts", size: 96 },
  { path: "src/lib/util.ts", size: 40 },
];

describe("buildFileTree · 目录合成与排序", () => {
  it("目录由路径段合成、目录先于文件、同级按名排序", () => {
    const tree = buildFileTree(files);

    expect(tree.map((n) => n.kind)).toEqual(["dir", "dir", "file"]);
    const docs = tree[0] as FileTreeDir;
    const src = tree[1] as FileTreeDir;
    const agents = tree[2] as FileTreeFile;
    expect(docs).toMatchObject({ kind: "dir", name: "docs", path: "docs" });
    expect(src).toMatchObject({ kind: "dir", name: "src", path: "src" });
    expect(agents).toMatchObject({ kind: "file", name: "AGENTS.md", path: "AGENTS.md", size: 7 });

    // docs 下唯一文件；src 下先目录（app、lib）后文件（index.ts），各自按名排
    expect(docs.children.map((c) => c.path)).toEqual(["docs/PRD.md"]);
    expect(src.children.map((c) => c.path)).toEqual(["src/app", "src/lib", "src/index.ts"]);
    expect((src.children[0] as FileTreeDir).children.map((c) => c.path)).toEqual([
      "src/app/page.tsx",
    ]);
  });

  it("空清单 = 空树（成果区未长出的正常态）", () => {
    expect(buildFileTree([])).toEqual([]);
  });
});

describe("selectableFile · 选中保持与缺省", () => {
  it("无历史选中时缺省落 PRD（#20 文件模式延续）", () => {
    expect(selectableFile(files, null)).toBe(PRD_PATH);
  });

  it("历史选中仍在树上则保持（不因刷新被拽走）", () => {
    expect(selectableFile(files, "src/index.ts")).toBe("src/index.ts");
  });

  it("历史选中已被移除（修正删文件）则回缺省", () => {
    expect(selectableFile(files, "src/gone.ts")).toBe(PRD_PATH);
    expect(selectableFile([{ path: "src/index.ts", size: 1 }], "src/gone.ts")).toBe("src/index.ts");
    expect(selectableFile([], "src/gone.ts")).toBeNull();
  });
});

describe("isAncestorDir · 祖先目录判定", () => {
  it("仅认目录前缀，不认同前缀字符串", () => {
    expect(isAncestorDir("docs", "docs/PRD.md")).toBe(true);
    expect(isAncestorDir("src", "src/app/page.tsx")).toBe(true);
    expect(isAncestorDir("docs", "docs-x/PRD.md")).toBe(false);
    expect(isAncestorDir("docs/PRD.md", "docs/PRD.md")).toBe(false);
  });
});

describe("formatFileSize · 大小人文可读", () => {
  it("B/KB/MB 阶梯，一位小数", () => {
    expect(formatFileSize(0)).toBe("0 B");
    expect(formatFileSize(512)).toBe("512 B");
    expect(formatFileSize(1024)).toBe("1 KB");
    expect(formatFileSize(12 * 1024 + 512)).toBe("12.5 KB");
    expect(formatFileSize(2 * 1024 * 1024)).toBe("2 MB");
  });
});
