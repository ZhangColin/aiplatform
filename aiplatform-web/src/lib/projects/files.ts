/**
 * 文件树浏览纯逻辑（#27 文件模式）：后端只列文件（目录是合成物），这里把
 * 平铺文件清单组成展示树 + 选中保持 + 祖先展开判定。排序用代码点序（与后端
 * 路径排序同构，跨环境确定性）；目录先于文件是文件浏览器的常规预期。
 */

/** PRD 在工作区的路径（后端 WorkspaceLayout.PRD 的前端镜像，缺省选中的锚）。 */
export const PRD_PATH = "docs/PRD.md";

/** 文件树条目输入（GET /projects/{id}/files 的 files 项：相对路径 + 字节大小）。 */
export type WorkspaceFile = { path: string; size: number };

/** 信封解包后的文件树响应 → 消费口径条目（缺省字段防御归一，无路径的碎条丢弃）。 */
export function normalizeProjectFiles(
  raw: { files?: { path?: string; size?: number }[] } | undefined,
): WorkspaceFile[] {
  return (raw?.files ?? []).flatMap((file) =>
    file.path ? [{ path: file.path, size: file.size ?? 0 }] : [],
  );
}

export type FileTreeDir = {
  kind: "dir";
  name: string;
  path: string;
  children: FileTreeNode[];
};

export type FileTreeFile = {
  kind: "file";
  name: string;
  path: string;
  size: number;
};

export type FileTreeNode = FileTreeDir | FileTreeFile;

/** 平铺清单 → 展示树：目录按路径段合成（无文件的目录不出现），目录先、同级按名排。 */
export function buildFileTree(files: WorkspaceFile[]): FileTreeNode[] {
  const roots: FileTreeNode[] = [];
  for (const file of [...files].sort((a, b) => (a.path < b.path ? -1 : 1))) {
    const segments = file.path.split("/");
    let children = roots;
    let prefix = "";
    for (let i = 0; i < segments.length - 1; i++) {
      const name = segments[i];
      prefix = prefix ? `${prefix}/${name}` : name;
      let dir = children.find((c): c is FileTreeDir => c.kind === "dir" && c.name === name);
      if (!dir) {
        dir = { kind: "dir", name, path: prefix, children: [] };
        children.push(dir);
      }
      children = dir.children;
    }
    children.push({
      kind: "file",
      name: segments[segments.length - 1],
      path: file.path,
      size: file.size,
    });
  }
  return sortNodes(roots);
}

/** 选中保持：历史选中仍在树上则保留，否则回缺省（PRD 优先，退而首文件，空则 null）。 */
export function selectableFile(
  files: WorkspaceFile[] | undefined,
  current: string | null,
): string | null {
  if (!files || files.length === 0) return null;
  if (current && files.some((f) => f.path === current)) return current;
  if (files.some((f) => f.path === PRD_PATH)) return PRD_PATH;
  return files[0].path;
}

/** dirPath 是否是 filePath 的祖先目录（仅认 `前缀/`，不认同前缀字符串）。 */
export function isAncestorDir(dirPath: string, filePath: string): boolean {
  return filePath.startsWith(dirPath + "/");
}

/** 字节大小 → 人文可读（B/KB/MB 一位小数，文件树与内容头共用）。 */
export function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${trimToOneDecimal(bytes / 1024)} KB`;
  return `${trimToOneDecimal(bytes / (1024 * 1024))} MB`;
}

// ---- 内部 ----

/** 目录先于文件、同级按名代码点序（逐层就地重排）。 */
function sortNodes(nodes: FileTreeNode[]): FileTreeNode[] {
  const byName = (a: FileTreeNode, b: FileTreeNode) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0);
  const sorted = [
    ...nodes.filter((n) => n.kind === "dir").sort(byName),
    ...nodes.filter((n) => n.kind !== "dir").sort(byName),
  ];
  for (const node of sorted) {
    if (node.kind === "dir") node.children = sortNodes(node.children);
  }
  return sorted;
}

function trimToOneDecimal(value: number): string {
  return `${Math.round(value * 10) / 10}`;
}
