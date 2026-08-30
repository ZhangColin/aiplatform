/**
 * agent 流分段 → 呈现字段的防御式收窄（issue #40，spec 0001 §4.1）。
 *
 * 透传分段的 `data` 为引擎 part 原样（opencode `{type, text|tool|state…}`，SSE 事件
 * 清单标「初版」、形状未锁死）。故只做收窄不建假字段：命中即取、缺字段给空/兜底，
 * Feed 兜底呈现不崩。纯函数，输入 unknown，不依赖 store 类型。
 */

export type ToolStatus = "running" | "done";

export type ToolSegmentView = {
  name: string;
  arg: string;
  status: ToolStatus;
};

export type PatchSegmentView = {
  path: string;
  added: number;
  removed: number;
  summary: string;
};

/** text / reasoning 段 → 文本。data = opencode part（`{type, text}`）或裸字符串。 */
export function segmentText(data: unknown): string {
  if (typeof data === "string") return data;
  if (data && typeof data === "object") {
    const d = data as Record<string, unknown>;
    if (typeof d.text === "string") return d.text;
    if (typeof d.content === "string") return d.content;
    if (typeof d.reasoning === "string") return d.reasoning;
  }
  return "";
}

/**
 * tool 段 → {name, arg, status}。opencode tool part 形状 = `{type, tool, state:{status,
 * input}}`；status pending/running 归「进行中」，其余（completed/error/未知）归「已执行」。
 */
export function segmentTool(data: unknown): ToolSegmentView {
  const d = (data && typeof data === "object" ? data : {}) as Record<string, unknown>;
  const state = (d.state && typeof d.state === "object" ? d.state : {}) as Record<string, unknown>;
  const statusRaw = d.status ?? state.status;
  const status: ToolStatus =
    statusRaw === "running" || statusRaw === "pending" || statusRaw === "in_progress"
      ? "running"
      : "done";
  const name = typeof d.tool === "string" ? d.tool : typeof d.name === "string" ? d.name : "";
  return { name, arg: summarizeToolInput(state.input ?? d.input ?? d.arg), status };
}

/** tool 入参 → 单行文本（对象 JSON 序列化，Feed 侧再做截断）。 */
function summarizeToolInput(input: unknown): string {
  if (input == null) return "";
  if (typeof input === "string") return input;
  return JSON.stringify(input);
}

/**
 * patch 段 → {path, added, removed, summary}。shape 未锁死：path/diff/edits（SSE 清单
 * 初版）或 prototype 的 {file, added, removed, summary} 都收。增删计数优先取显式字段，
 * 缺则从行级 diff 派生（头部计数与染色行数一致），再无行退化为 edits 组计数、再退化为 0。
 */
export function segmentPatch(data: unknown): PatchSegmentView {
  const d = (data && typeof data === "object" ? data : {}) as Record<string, unknown>;
  const file = (d.file && typeof d.file === "object" ? d.file : {}) as Record<string, unknown>;
  const path = firstString(d.path, typeof d.file === "string" ? d.file : file.path);
  const summary = firstString(d.summary);
  const lines = segmentPatchDiff(data);
  const counts = countDiffLines(lines);
  const edits = Array.isArray(d.edits) ? d.edits : [];
  return {
    path,
    added: finiteNumber(d.added) ?? (lines.length ? counts.add : editsOfKind(edits, "add")),
    removed: finiteNumber(d.removed) ?? (lines.length ? counts.remove : editsOfKind(edits, "remove")),
    summary,
  };
}

function firstString(...values: unknown[]): string {
  for (const v of values) if (typeof v === "string") return v;
  return "";
}

/** 有限数值收窄：非 number / 非有限值 → undefined（供 `??` 链降级）。 */
function finiteNumber(value: unknown): number | undefined {
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

/** edits 数组里 type 命中（add/remove）的条目数，作增删行的退化估计。 */
function editsOfKind(edits: unknown[], kind: string): number {
  return edits.filter(
    (e) => e && typeof e === "object" && (e as Record<string, unknown>).type === kind,
  ).length;
}

/** 行级 diff → 增/删行数（供头部计数与染色行数对齐）。 */
function countDiffLines(lines: DiffLine[]): { add: number; remove: number } {
  let add = 0;
  let remove = 0;
  for (const line of lines) {
    if (line.kind === "add") add++;
    else if (line.kind === "remove") remove++;
  }
  return { add, remove };
}

/** 行对象袋 → 文本（text/s/content/value 依序收窄）。 */
function lineText(o: Record<string, unknown>): string {
  return firstString(o.text, o.s, o.content, o.value);
}

/** 行级 diff 单行（spec 0001 §4.2「diff 行级块」）：+ 增 / − 删 / 上下文。 */
export type DiffLineKind = "add" | "remove" | "context";
export type DiffLine = { kind: DiffLineKind; text: string };

/**
 * patch 段 → 行级 diff。shape 未锁死：统一 diff 字符串（data.diff）或行数组
 * （data.lines / data.edits）都收；解析失败给空行——Feed 侧退化为仅摘要 + 计数，
 * 不崩。纯函数，输入 unknown。
 */
export function segmentPatchDiff(data: unknown): DiffLine[] {
  const d = (data && typeof data === "object" ? data : {}) as Record<string, unknown>;
  const diff = typeof d.diff === "string" ? d.diff : "";
  if (diff) return parseUnifiedDiff(diff);
  const lines = Array.isArray(d.lines) ? d.lines : [];
  if (lines.length) return parseDiffLines(lines);
  const edits = Array.isArray(d.edits) ? d.edits : [];
  if (edits.length) return parseEdits(edits);
  return [];
}

/** step 段 → 步骤名（opencode step part 的 title/name/step/label，缺则空串）。 */
export function segmentStep(data: unknown): string {
  const d = (data && typeof data === "object" ? data : {}) as Record<string, unknown>;
  return firstString(d.title, d.name, d.step, d.label);
}

/** 统一 diff 的元信息行前缀（文件头 / hunk 头 / 换行标记），不进行级块。 */
const UNIFIED_META_PREFIXES = [
  "diff ",
  "index ",
  "--- ",
  "+++ ",
  "@@",
  "new file mode",
  "deleted file mode",
  "\\ No newline",
];

function parseUnifiedDiff(diff: string): DiffLine[] {
  return diff.split("\n").flatMap<DiffLine>((raw) => {
    const line = raw.endsWith("\r") ? raw.slice(0, -1) : raw;
    if (UNIFIED_META_PREFIXES.some((p) => line.startsWith(p))) return [];
    return classifyPrefixedLine(line);
  });
}

/** 行数组（prototype DIFF_LINES 形态：{t, s} 或带 +/-/空格前缀的裸字符串）。 */
function parseDiffLines(lines: unknown[]): DiffLine[] {
  return lines.flatMap<DiffLine>((item) => {
    if (typeof item === "string") return classifyPrefixedLine(item);
    if (item && typeof item === "object") {
      const o = item as Record<string, unknown>;
      const text = lineText(o);
      if (text) return [{ kind: markToKind(firstString(o.t, o.type, o.kind)), text }];
    }
    return [];
  });
}

/** edits 数组 → 行级块：edit.type 决定整组行的 add/remove 归属。 */
function parseEdits(edits: unknown[]): DiffLine[] {
  return edits.flatMap<DiffLine>((edit) => {
    if (!edit || typeof edit !== "object") return [];
    const o = edit as Record<string, unknown>;
    const kind: DiffLineKind = o.type === "remove" ? "remove" : "add";
    const lines = Array.isArray(o.lines) ? o.lines : [];
    if (lines.length) {
      return lines.flatMap<DiffLine>((l) => {
        if (typeof l === "string") return [{ kind, text: l }];
        if (l && typeof l === "object") {
          const text = lineText(l as Record<string, unknown>);
          if (text) return [{ kind, text }];
        }
        return [];
      });
    }
    const single = lineText(o);
    return single ? [{ kind, text: single }] : [];
  });
}

/** 带 +/-/空格前缀的裸行 → 单行 diff；空格前缀剥掉，余者原样归上下文。 */
function classifyPrefixedLine(line: string): DiffLine[] {
  if (line.startsWith("+")) return [{ kind: "add", text: line.slice(1) }];
  if (line.startsWith("-")) return [{ kind: "remove", text: line.slice(1) }];
  return [{ kind: "context", text: line.startsWith(" ") ? line.slice(1) : line }];
}

/** 行数组标记（+/add / -/remove / 其余）→ DiffLineKind。 */
function markToKind(mark: string): DiffLineKind {
  if (mark === "+" || mark === "add") return "add";
  if (mark === "-" || mark === "remove" || mark === "del") return "remove";
  return "context";
}
