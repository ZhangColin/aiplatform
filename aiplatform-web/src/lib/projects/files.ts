/**
 * 工作区文件取件纯逻辑（#27 文件模式的历史入口；#608 呈现面清场：文件树面板与
 * 内容点看退役，只留 PRD 路径常量与取件 URL/落盘名的口径单点）。
 */

/** PRD 在工作区的路径（后端 WorkspaceLayout.PRD 的前端镜像）。 */
export const PRD_PATH = "docs/PRD.md";

/** 字节大小 → 人文可读（B/KB/MB 一位小数）。 */
export function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${trimToOneDecimal(bytes / 1024)} KB`;
  return `${trimToOneDecimal(bytes / (1024 * 1024))} MB`;
}

/**
 * 文件取件的同源 `/api/*` 直链（会话 cookie 自动携带；二进制不走 api client——
 * 其响应一律按 JSON 解包）。路径形归一：前导斜杠剥掉（工作区锚定形「/x.png」与
 * 相对形两形同收，后端只收相对形——消费面不再各自转换）。
 */
export function rawFileUrl(projectId: string, path: string): string {
  return `/api/projects/${projectId}/files/raw?path=${encodeURIComponent(relativeFormOf(path))}`;
}

/**
 * 单文件下载 URL（#287 通用下载，ADR-0027 支付门）：文件皆可带走，门判定在后端
 * （曾支付/已归档即开放）——前端不预判门态，被拦时按信封 message 如实告知。
 */
export function downloadFileUrl(projectId: string, path: string): string {
  return `/api/projects/${projectId}/files/download?path=${encodeURIComponent(relativeFormOf(path))}`;
}

/** 工作区锚定形（前导 /）→ 相对形；相对形原样。 */
export function relativeFormOf(path: string): string {
  return path.startsWith("/") ? path.slice(1) : path;
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

function trimToOneDecimal(value: number): string {
  return `${Math.round(value * 10) / 10}`;
}
