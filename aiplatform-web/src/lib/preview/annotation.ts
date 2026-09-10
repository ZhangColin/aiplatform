/**
 * 圈注协议与载荷形状（#97 圈注 B 档）：预览 iframe（用户系统，跨源）与平台前端
 * 之间的 postMessage 协议 + 结构化 DOM 锚的载荷形状。双侧契约——注入脚本
 * （服务端 docker/workspace/annotation.js）与本节同源，字段形状与 SSE 事件清单
 * 「消息附件部件锚载荷 schema」同源。
 *
 * 协议（postMessage data 皆为信封 { __aiplatform__: true, type, ... }）：
 *   父 → 子：{ type: "annotate", mode: "enter"|"exit", tool: "select"|"circle"|"comment" }
 *   子 → 父：{ type: "anchor", payload: { kind, anchor, note } }（锚）|
 *            { type: "exit" }（Esc 退出，#134：顶层信封，不包进锚 payload）
 * 回传目标 = 呼出消息的 origin（注入脚本据 event.origin 回传）；父侧校验
 * event.origin 等于预览 URL 的 origin（防伪锚）。
 */

/** 标注类型（工具条四键中启用的两键：select = 选择·点选锚定 / circle = 圈选·拖框圈区域；
 * comment 仅历史兼容——UI 不再产生新评论圈注，落库旧件只读回显）。 */
export type AnnotationKind = "select" | "circle" | "comment";

/** 圈选矩形（页面级坐标，circle 专用；选择器可缺省）。 */
export type AnnotationRegion = { x: number; y: number; width: number; height: number };

/** 结构化定位：选择器 / 文本引用（点选与评论），圈选矩形（circle）。 */
export type AnnotationAnchor = {
  selector?: string;
  text?: string;
  region?: AnnotationRegion;
};

/** 一条圈注的载荷（无本地 id——id 由前端 store 生成）。 */
export type AnnotationDraft = {
  kind: AnnotationKind;
  anchor: AnnotationAnchor;
  note: string;
};

/** 父 → 子：呼出标注态（指定工具）。 */
export function encodeAnnotate(mode: "enter" | "exit", tool?: AnnotationKind): unknown {
  return { __aiplatform__: true, type: "annotate", mode, tool };
}

/** 子 → 父锚消息的容错解析：形状不符返回 null（伪锚/坏消息不落地）。 */
export function parseAnchorEvent(data: unknown): AnnotationDraft | null {
  if (!data || typeof data !== "object") return null;
  const record = data as Record<string, unknown>;
  if (record.__aiplatform__ !== true || record.type !== "anchor") return null;
  return parseAnnotationBody(record.payload as Record<string, unknown>);
}

/** 附件命令载荷 → 圈注条目（对话史水合回显用；attachmentType 必须 annotation）。 */
export function parseAnnotationAttachment(raw: unknown): AnnotationDraft | null {
  if (!raw || typeof raw !== "object") return null;
  const record = raw as Record<string, unknown>;
  if (record.attachmentType !== "annotation") return null;
  return parseAnnotationBody(record.annotation as Record<string, unknown>);
}

/** 圈注体（annotation payload）→ 条目（kind/anchor/note 容错收窄）。 */
function parseAnnotationBody(body: Record<string, unknown> | null | undefined): AnnotationDraft | null {
  if (!body || typeof body !== "object") return null;
  const kind = body.kind;
  if (kind !== "select" && kind !== "circle" && kind !== "comment") return null;
  const anchor = parseAnchor(body.anchor);
  if (anchor === null) return null;
  const note = typeof body.note === "string" ? body.note : "";
  return { kind, anchor, note };
}

/** 子 → 父的退出信封（注入脚本 Esc 退出时发顶层 { __aiplatform__, type: "exit" }）。 */
export function parseExitEvent(data: unknown): boolean {
  if (!data || typeof data !== "object") return false;
  const record = data as Record<string, unknown>;
  return record.__aiplatform__ === true && record.type === "exit";
}

/** 锚载荷容错解析：选择器/文本至少其一、或矩形在，才成立；否则 null。 */
function parseAnchor(raw: unknown): AnnotationAnchor | null {
  if (!raw || typeof raw !== "object") return null;
  const a = raw as Record<string, unknown>;
  const selector = typeof a.selector === "string" ? a.selector : undefined;
  const text = typeof a.text === "string" ? a.text : undefined;
  const region = parseRegion(a.region);
  if (!region && !selector && !text) return null;
  return { selector, text, region };
}

/** 矩形容错解析：四值皆有限数才成立（circle 锚的硬判据）。 */
function parseRegion(raw: unknown): AnnotationRegion | undefined {
  if (!raw || typeof raw !== "object") return undefined;
  const r = raw as Record<string, unknown>;
  const x = r.x, y = r.y, width = r.width, height = r.height;
  if (
    typeof x !== "number" || typeof y !== "number"
    || typeof width !== "number" || typeof height !== "number"
    || !Number.isFinite(x) || !Number.isFinite(y)
    || !Number.isFinite(width) || !Number.isFinite(height)
  ) {
    return undefined;
  }
  return { x, y, width, height };
}

/** 随消息发送的附件命令形状（PostMessageCommand.attachments 元素，与 swagger 契约同源）。 */
export type AnnotationAttachmentCommand = {
  attachmentType: "annotation";
  annotation: { kind: AnnotationKind; anchor: AnnotationAnchor; note: string };
};

/** 圈注条目 → 发送命令附件（发送时映射；本地 id 不进载荷——载荷只定要点）。 */
export function toAttachmentCommand(item: AnnotationDraft): AnnotationAttachmentCommand {
  return {
    attachmentType: "annotation",
    annotation: { kind: item.kind, anchor: item.anchor, note: item.note },
  };
}

/** 标注类型 → 用户面标签（与工具条四键口径一致：选择 / 圈选 / 评论）。 */
export function annotationLabel(kind: AnnotationKind): string {
  return kind === "select" ? "选择" : kind === "circle" ? "圈选" : "评论";
}

/** 圈注条目 → 文本行（问答作答时随答复文本一起送达——作答通道无附件位，渲染进
 * 文本；逐条编号与发言通道 AnnotationPrompt 同构，「第 N 条」指代对得上）。 */
export function renderAnnotationsText(items: AnnotationDraft[]): string {
  if (!items.length) return "";
  return items.map((a, i) => `${i + 1}. ${annotationLabel(a.kind)}：${annotationSummary(a)}`).join("；");
}

/** 圈注条目的可读摘要（附件 chip 呈现用：元素文本 / 选择器 / 矩形区域）。 */
export function annotationSummary(item: AnnotationDraft): string {
  if (item.kind === "circle" && item.anchor.region) {
    const r = item.anchor.region;
    const region = `区域 (${Math.round(r.x)}, ${Math.round(r.y)}) ${Math.round(r.width)}×${Math.round(r.height)}`;
    const ref = item.anchor.text || item.anchor.selector;
    return ref ? `${region} · ${ref}` : region;
  }
  if (item.anchor.text) return item.anchor.text;
  if (item.anchor.selector) return item.anchor.selector;
  return annotationLabel(item.kind);
}
