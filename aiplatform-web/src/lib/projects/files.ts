/**
 * 文件树浏览纯逻辑（#27 文件模式）：后端只列文件（目录是合成物），这里把
 * 平铺文件清单组成展示树 + 选中保持 + 祖先展开判定。排序用代码点序（与后端
 * 路径排序同构，跨环境确定性）；目录先于文件是文件浏览器的常规预期。
 * #283 起收图片点看判定与 raw 直出 URL（ADR-0027：点看对图片放行）。
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

/** 图片点看的扩展名面（后端 ProjectFiles 判定的前端镜像：png/jpg/webp/gif/svg）。 */
const IMAGE_EXTENSIONS = new Set(["png", "jpg", "jpeg", "webp", "gif", "svg"]);

/**
 * 图片 inline 点看的大小上限（25 MiB，后端 PRJ_022 拒收口径的前端镜像）：
 * 超限不发请求直接如实提示——省一次注定 400 的取件。
 */
export const RAW_IMAGE_SIZE_LIMIT_BYTES = 25 * 1024 * 1024;

/**
 * 点看判定（#283，ADR-0027 点看对图片放行）：图片扩展名走 raw 直出 inline 大图，
 * 其余照旧文本内容端点（含 NUL 的真二进制非图片件由后端 PRJ_023 如实拒收）。
 */
export function isImagePath(path: string): boolean {
  const dot = path.lastIndexOf(".");
  const slash = path.lastIndexOf("/");
  if (dot <= slash) return false;
  return IMAGE_EXTENSIONS.has(path.slice(dot + 1).toLowerCase());
}

/**
 * 图片点看的 raw 直出 URL：同源 `/api/*` 直链（会话 cookie 自动携带，对偶
 * source-package 下载链接先例）——二进制不走 api client（其响应一律按 JSON 解包）。
 */
export function rawFileUrl(projectId: string, path: string): string {
  return `/api/projects/${projectId}/files/raw?path=${encodeURIComponent(path)}`;
}

/**
 * 单文件下载 URL（#287 通用下载，ADR-0027 支付门）：文件区一切文件皆可带走，
 * 门判定在后端（曾支付/已归档即开放）——前端不预判门态，被拦时按信封 message
 * 如实告知（体验免费、带走才付费）。同样同源直链、不经 api client。
 */
export function downloadFileUrl(projectId: string, path: string): string {
  return `/api/projects/${projectId}/files/download?path=${encodeURIComponent(path)}`;
}

/**
 * 下载落盘文件名（路径末段）：blob 锚点落盘不走响应头（Content-Disposition 的
 * 后端消毒对它无效），消毒同后端 fileNameOf 口径——控制字符（含 CR/LF）与
 * 引号剔除、非 ASCII（中文等）原样保留。
 */
export function downloadFileNameOf(path: string): string {
  const name = path.split("/").pop() ?? path;
  // 控制段显式区间（C0＋DEL＋C1＝后端 \\p{Cntrl} 同集；TS 不认 Cntrl 别名故不
  // 用属性转义）
  return name.replaceAll(/[\u0000-\u001f\u007f-\u009f"]/g, "");
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
