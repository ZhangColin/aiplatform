import { describe, expect, it } from "vitest";

import {
  PRD_PATH,
  RAW_IMAGE_SIZE_LIMIT_BYTES,
  buildFileTree,
  formatFileSize,
  isAncestorDir,
  isImagePath,
  downloadFileUrl,
  downloadFileNameOf,
  rawFileUrl,
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

describe("isImagePath · 点看判定（#283 图片放行）", () => {
  it("五格式大小写不敏感放行；文本/无扩展名/二进制非图片不在面内", () => {
    expect(isImagePath("materials/ref.png")).toBe(true);
    expect(isImagePath("materials/ref.PNG")).toBe(true);
    expect(isImagePath("design/poster.Jpg")).toBe(true);
    expect(isImagePath("a/b/c.webp")).toBe(true);
    expect(isImagePath("logo.svg")).toBe(true);
    expect(isImagePath("anim.gif")).toBe(true);

    expect(isImagePath("docs/PRD.md")).toBe(false);
    expect(isImagePath("src/app/page.tsx")).toBe(false);
    expect(isImagePath("assets/logo.bin")).toBe(false);
    expect(isImagePath("no-extension")).toBe(false);
    expect(isImagePath("png")).toBe(false); // 文件名恰好叫 png，不是扩展名
  });
});

describe("rawFileUrl · 图片直出 URL（#283）", () => {
  it("同源 /api 直链，path 整体编码（不经 api client——二进制不走 JSON）", () => {
    expect(rawFileUrl("p1", "materials/ref.png")).toBe(
      "/api/projects/p1/files/raw?path=materials%2Fref.png",
    );
    // 特殊字符（空格/引号/中文）原样过 URL 编码，不破 query 结构
    expect(rawFileUrl("p1", "materials/我的 图#1.png")).toBe(
      `/api/projects/p1/files/raw?path=${encodeURIComponent("materials/我的 图#1.png")}`,
    );
  });
});

describe("RAW_IMAGE_SIZE_LIMIT_BYTES · 前端预检上界", () => {
  it("与后端 PRJ_022 图片查看上限同值（25 MiB）", () => {
    expect(RAW_IMAGE_SIZE_LIMIT_BYTES).toBe(25 * 1024 * 1024);
  });
});

describe("downloadFileUrl · 单文件下载 URL（#287 支付门）", () => {
  it("同源 /api 直链，path 整体编码（同 raw 直链形——门判定归后端，前端不预判）", () => {
    expect(downloadFileUrl("p1", "exports/海报-终稿.png")).toBe(
      `/api/projects/p1/files/download?path=${encodeURIComponent("exports/海报-终稿.png")}`,
    );
  });
});

describe("downloadFileNameOf · 落盘文件名消毒（与后端 fileNameOf 同口径）", () => {
  it("路径末段直取；中文等非 ASCII 原样保留", () => {
    expect(downloadFileNameOf("materials/ref.png")).toBe("ref.png");
    expect(downloadFileNameOf("exports/海报 终稿.png")).toBe("海报 终稿.png");
  });

  it("控制字符与引号剔除（blob 锚点不走响应头，消毒镜像在后端同口径）", () => {
    expect(downloadFileNameOf("materials/ba\"d.png")).toBe("bad.png");
    expect(downloadFileNameOf("materials/line\nbreak.png")).toBe("linebreak.png");
  });
});
