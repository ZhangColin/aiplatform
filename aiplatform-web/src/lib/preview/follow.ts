import type { WorkPart } from "@/lib/store/work-message";

/**
 * 预览跟随的纯前端映射（#618，ADR-0057：write_file 路径纯前端映射路由、剥分组
 * 段、仅路由变化才导航，组件／库文件不切页）。
 *
 * 输入 = 写文件动作的目标路径（服务端 part-action 的 `path` 参数原形，相对/锚定
 * 两形自选）；输出 = 应用内路由（`/about` 形态），或 `undefined`（该文件不构成
 * 可导航页面——布局／样式／组件／库／API 路由／动态段等）。本模块零 React、零
 * 副作用：跟随与否（固定开关）归组件，导航动作仍走既有地址解析。
 *
 * 不做的兜底（ADR-0057「降级自担」）：不探测编译状态、不等待、不重试、不检测
 * 白屏——编译中跳过去短暂白屏是可接受降级，不加机制。
 */

/** App Router 页面文件名（`page.*`——Next.js 唯一的路由叶子约定）。 */
const PAGE_FILE = /^page\.(?:tsx|ts|jsx|js)$/;

/**
 * 拦截段标记：`(.)` / `(..)` / `(...)` 前缀（目录名携括号点号）——拦截路由的
 * URL 由源路径决定，不构成独立可导航页面。
 */
const INTERCEPTING = /^\(\.{1,3}/;

/**
 * 写文件路径 → 应用路由（App Router 文件系统约定）。
 *
 * 规则（正本 = Next.js App Router）：
 * - 定位 `src/app/`（或 `app/`）应用根，取其后相对段；
 * - 末段必须是 `page.tsx`（或 jsx/ts/js）——布局、loading、error、route 等非页面
 *   文件一律不切页；
 * - 剥路由组 `(group)`（不进 URL）；动态段 `[slug]`、catch-all、拦截段、平行
 *   路由 `@slot`、私有目录 `_x` 不构成确定 URL → 不切页；
 * - 根页 = `/`。
 *
 * @param path 写文件工具的 path 参数（可携工作区前缀/反斜杠/点前缀）
 * @returns 应用内路径（`/` 开头）；非页面文件返回 `undefined`
 */
export function previewRouteOfWritePath(path: string): string | undefined {
  const segments = normalizeSegments(path);
  if (!segments) return undefined;

  const base = appRootIndex(segments);
  if (base < 0) return undefined;

  const rest = segments.slice(base);
  if (!PAGE_FILE.test(rest[rest.length - 1])) return undefined;

  const route: string[] = [];
  for (const segment of rest.slice(0, -1)) {
    // 私有目录（_components）不进路由；平行路由（@modal）不是 URL 层级
    if (segment.startsWith("_") || segment.startsWith("@")) return undefined;
    // 动态段（[slug] / [...slug] / [[...slug]]）：URL 由数据决定，无确定目标
    if (segment.startsWith("[")) return undefined;
    if (segment.startsWith("(")) {
      if (INTERCEPTING.test(segment)) return undefined;
      if (segment.endsWith(")")) continue; // 路由组：剥出 URL
      return undefined; // 括号段无法归类：保守不切页
    }
    route.push(segment);
  }
  return "/" + route.join("/");
}

/**
 * 工作消息部件 → 当前应跟随的路由：取**最新一条**能映射成页面的写文件路径。
 * 非页面写入（组件／样式／库／布局／API）跳过、不改变结果——这正是「同页反复
 * 重写不跳、改组件不切页」的来源：路由值不变，调用方据此判等即可。
 */
export function followedPreviewRoute(parts: readonly WorkPart[]): string | undefined {
  for (let i = parts.length - 1; i >= 0; i -= 1) {
    const part = parts[i];
    if (part.kind !== "action" || !part.path) continue;
    const route = previewRouteOfWritePath(part.path);
    if (route !== undefined) return route;
  }
  return undefined;
}

/** 路径 → 段（统一斜杠、去空段/点段）；空路径或无段返回 undefined。 */
function normalizeSegments(path: string): string[] | undefined {
  if (typeof path !== "string") return undefined;
  const segments = path
    .trim()
    .replace(/\\/g, "/")
    .split("/")
    .filter((segment) => segment !== "" && segment !== ".");
  return segments.length > 0 ? segments : undefined;
}

/**
 * 应用根定位（段索引）：优先 `src/app/`（取最靠后的出现）；无 src 时接受路径
 * 以 `app/` 开头或工作区绝对前缀 `/workspace/app/`。非应用目录（如
 * `src/components/app/...`）返回 -1——不误把同名子目录当应用根。
 */
function appRootIndex(segments: readonly string[]): number {
  for (let i = segments.length - 2; i >= 0; i -= 1) {
    if (segments[i] === "src" && segments[i + 1] === "app") return i + 2;
  }
  for (let i = 0; i < segments.length; i += 1) {
    if (segments[i] !== "app") continue;
    if (segments.slice(0, i).every((segment) => segment === "workspace")) return i + 1;
  }
  return -1;
}
