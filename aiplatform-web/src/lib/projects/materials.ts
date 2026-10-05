/**
 * 图片物料纯逻辑（#286，ADR-0027 图片管道底座）：上传面契约的前端镜像——
 * 五格式/10MB 与后端 ProjectMaterials 同口径（前端预检省一次注定 400 的上传）、
 * 附件 image 形态的构造与容错解析（请求类型取 schema 生成物，不手写）。
 */

import type { components } from "@/lib/api/schema";

/** 单张上传上限（10MB，后端 PRJ_043 拒收口径的前端镜像）。 */
export const MATERIAL_UPLOAD_LIMIT_BYTES = 10 * 1024 * 1024;

/** 上传格式的扩展名面（后端上传口径同集：png/jpg/webp/gif/svg，jpeg 别名同收）。 */
const UPLOADABLE_EXTENSIONS = new Set(["png", "jpg", "jpeg", "webp", "gif", "svg"]);

/** 上传文件名 → 格式判定（大小写不敏感；与后端 extensionOf 同构：点须在名内，无嗅探）。 */
export function isUploadableImageName(name: string): boolean {
  const dot = name.lastIndexOf(".");
  if (dot < 0) return false; // 无扩展名即不符
  return UPLOADABLE_EXTENSIONS.has(name.slice(dot + 1).toLowerCase());
}

/**
 * 上传端点响应（信封解包后的 data，schema 契约的保形视图——后端恒回三字段）：
 * path 即随话发送的附件载荷引用。size 为 Long（REST 序列化运行时是串，消费面未用）。
 */
export type UploadedMaterial = Required<
  Pick<components["schemas"]["MaterialUploadedResponse"], "path" | "name" | "size">
>;

/** 随话发送的图片附件命令（PostMessageCommand.attachments 的 image 元素——schema 消息附件的 image 形态字段面）。 */
export type ImageAttachmentCommand = Pick<
  components["schemas"]["MessageAttachment"],
  "attachmentType" | "name" | "path"
>;

/** 已上传物料 → 发送命令附件（载荷＝工作区路径引用，不带字节）。 */
export function toImageAttachmentCommand(material: {
  name: string;
  path: string;
}): ImageAttachmentCommand {
  return { attachmentType: "image", name: material.name, path: material.path };
}

/** 回显物料条目（水合/乐观 chip 的视图形：名 + 路径引用）。 */
export type AttachedMaterial = { name: string; path: string };

/** 对话史附件载荷 → 物料条目（容错收窄：非 image 形态/缺路径即 null）。 */
export function parseImageAttachment(raw: unknown): AttachedMaterial | null {
  if (!raw || typeof raw !== "object") return null;
  const record = raw as Record<string, unknown>;
  if (record.attachmentType !== "image" || typeof record.path !== "string" || !record.path) {
    return null;
  }
  const name = typeof record.name === "string" && record.name ? record.name : basenameOf(record.path);
  return { path: record.path, name };
}

/** 物料条目 → 文本行（问答作答时随答复文本一起送达——作答通道无附件位，渲染进
 * 文本；逐条编号与发言通道 AttachmentPrompt 同构，「第 N 张」指代对得上）。 */
export function renderMaterialsText(materials: { name: string; path: string }[]): string {
  if (!materials.length) return "";
  return materials.map((m, i) => `${i + 1}. ${m.name}（${m.path}）`).join("；");
}

/** 路径末段（name 缺省时的呈现回落）。 */
function basenameOf(path: string): string {
  const slash = path.lastIndexOf("/");
  return slash >= 0 ? path.slice(slash + 1) : path;
}
