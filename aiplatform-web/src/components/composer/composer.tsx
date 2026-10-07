"use client";

import {
  ArrowUp,
  Check,
  ChevronDown,
  FileText,
  Image as ImageIcon,
  MessageSquarePlus,
  Mic,
  MousePointer2,
  Paperclip,
  Sparkles,
  SquareDashed,
  TriangleAlert,
  Upload,
  X,
} from "lucide-react";
import { useEffect, useId, useRef, useState, type ChangeEvent, type KeyboardEvent, type RefObject } from "react";

import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Spinner } from "@/components/ui/spinner";
import {
  Attachment,
  AttachmentAction,
  AttachmentActions,
  AttachmentContent,
  AttachmentDescription,
  AttachmentMedia,
  AttachmentTitle,
} from "@/components/ui/attachment";
import { cn } from "@/lib/utils";
import { isSubmitEnter } from "@/lib/chat/enter";
import { formatFileSize } from "@/lib/projects/files";
import {
  MATERIAL_UPLOAD_LIMIT_BYTES,
  isUploadableImageName,
  type UploadedMaterial,
} from "@/lib/projects/materials";
import { errorText } from "@/lib/api/api-error";
import { annotationLabel, annotationSummary, type AnnotationKind } from "@/lib/preview/annotation";
import type { AnnotationItem } from "@/lib/store/annotation";
import { PLATFORM_MODES, type EntryMode } from "@/lib/modes";

/**
 * 共享发送框（#72 定稿 / #76 落地）：首页 hero 与项目页同一组件的立体卡片
 * （ring + 分层阴影）——输入区 → 附件 chip 行（可删可加）→ 工具行（回形针
 * 物料区 / 类型下拉 / 语音位 / 圆形发送键）。
 *
 * 输入受控（首页示例 chips 点选填入、项目页接对话流都由调用侧持态）。附件两态
 * （#286 真上传）：调用侧传 {@link uploadFile} 时选文件即真上传（multipart 落
 * 工作区物料目录），chip 经附件组件族呈现上传中/失败/完成态、上传中或失败阻塞
 * 发送（不静默丢弃）；无上传管道（首页——归 #280 入口票）维持本地挂载态：选即
 * 挂 chip、可删可加，随 onSubmit 一并交出、发出即清。圈注附件归 store 持态、
 * 同行呈现。类型下拉＝入口两档（做系统/做设计，#299 收编两档 live——ADR-0029）：
 * 受控件（传 {@link onModeChange} 才呈现——首页 hero 与切换件状态同源；项目页
 * 不传＝不呈现，模式位是一次性分流语义、项目内终点变更唯一位＝设置 tab）。
 */

/** 附件条目（物料区与 chip 行共用形状）。state 缺省 = 本地挂载态（无上传管道）。 */
export type ComposerAttachment = {
  id: string;
  name: string;
  sizeLabel: string;
  /** 真上传态：uploading 上传中 / error 失败（error 随原因）/ done 完成（path 随行）。 */
  state?: "uploading" | "error" | "done";
  /** 工作区路径引用（state=done 携带——随话发送的载荷本体，不带字节）。 */
  path?: string;
  /** 失败原因（state=error 携带，chip 呈现）。 */
  error?: string;
};

/** 图片物料判定（本地挂载态 chip 图标分流：图用图片图标，其余按文档）。 */
function isImageMaterial(name: string, type?: string): boolean {
  if (type?.startsWith("image/")) return true;
  return /\.(png|jpe?g|gif|webp|svg|bmp|heic)$/i.test(name);
}

function AttachmentIcon({ name, type }: { name: string; type?: string }) {
  return isImageMaterial(name, type) ? (
    <ImageIcon className="size-3.5" />
  ) : (
    <FileText className="size-3.5" />
  );
}

/** 圈注条目图标（按标注类型分流，与预览工具条同源：选择/圈选/评论·历史兼容）。 */
function AnnotationKindIcon({ kind }: { kind: AnnotationKind }) {
  if (kind === "circle") return <SquareDashed className="size-3.5" />;
  if (kind === "comment") return <MessageSquarePlus className="size-3.5" />;
  return <MousePointer2 className="size-3.5" />;
}

export function Composer({
  hero = false,
  value,
  onValueChange,
  onSubmit,
  submitPending = false,
  disabled = false,
  attachmentsEnabled = true,
  uploadFile,
  materialUrl,
  annotations,
  onAnnotationRemove,
  inputRef,
  mode,
  onModeChange,
  placeholder = "说说你想做什么…",
}: {
  /** hero = 首页居中大框（加大一号）；项目页常规尺寸。 */
  hero?: boolean;
  value: string;
  onValueChange: (text: string) => void;
  /** 发送：文本 + 当次文件附件 + 圈注附件（发出后组件内文件附件清空，输入归调用侧）。 */
  onSubmit: (text: string, attachments: ComposerAttachment[], annotations: AnnotationItem[]) => void;
  /** 提交进行中（发送键转 Spinner 且禁用）。 */
  submitPending?: boolean;
  /** 整体禁用（项目页锁定态：输入与发送停用）。 */
  disabled?: boolean;
  /** 附件入口（回形针 + chip 行）：调用侧无上传管道时置 false 隐去——不邀请会被丢弃的操作。 */
  attachmentsEnabled?: boolean;
  /** 文件上传管道（#286）：选文件即真上传（multipart 落工作区物料目录）；缺省 =
   *  本地挂载态（首页现状，上传管道归 #280 入口票）。 */
  uploadFile?: (file: File) => Promise<UploadedMaterial>;
  /** 已上传物料的取件 URL（完成态 chip 缩略图——raw 直出同源直链）。 */
  materialUrl?: (path: string) => string;
  /** 圈注附件（#97 预览回传的标注条目，归 store 持态）：随附件 chip 行呈现（序号 +
   * 类型 + 摘要，多条指代靠序号——描述写主输入框）、发送前可删。 */
  annotations?: AnnotationItem[];
  /** 圈注删除（发送前可删）。 */
  onAnnotationRemove?: (id: string) => void;
  /** 输入框外接 ref（项目页：问题到达自动聚焦）。 */
  inputRef?: RefObject<HTMLTextAreaElement | null>;
  /** 当前入口档（类型下拉受控显示；仅 onModeChange 在场才有意义）。 */
  mode?: EntryMode;
  /** 类型下拉切换（传＝呈现下拉——首页 hero 分流位；不传＝不呈现，项目页口径）。 */
  onModeChange?: (mode: EntryMode) => void;
  placeholder?: string;
}) {
  const [attachments, setAttachments] = useState<ComposerAttachment[]>([]);
  const [materialsOpen, setMaterialsOpen] = useState(false);
  const fileInputId = useId();
  // 下拉触发面显示档（受控缺省＝首档做系统）
  const activeMode = mode ?? PLATFORM_MODES[0].label;

  // 自动增高：随输入长高、上限后内部滚动（create-project-form 既有口径迁入）。
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const setTextarea = (el: HTMLTextAreaElement | null) => {
    textareaRef.current = el;
    if (inputRef) inputRef.current = el;
  };
  useEffect(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${el.scrollHeight}px`;
  }, [value]);

  // 上传中/失败的附件阻塞发送（如实呈现不静默丢弃——失败件须移除后才发）
  const attachmentsBlocking = attachments.some((a) => a.state === "uploading" || a.state === "error");
  const canSubmit = value.trim().length > 0 && !submitPending && !disabled && !attachmentsBlocking;

  function submit() {
    if (!canSubmit) return;
    onSubmit(value, attachments, annotations ?? []);
    setAttachments([]);
  }

  function onKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (!isSubmitEnter(event)) return;
    event.preventDefault();
    submit();
  }

  /** 本地挂 chip（无管道态直挂；有管道态作为 uploading 占位）。 */
  function appendAttachments(entries: ComposerAttachment[]) {
    setAttachments((prev) => [...prev, ...entries]);
  }

  function patchAttachment(id: string, patch: Partial<ComposerAttachment>) {
    setAttachments((prev) => prev.map((a) => (a.id === id ? { ...a, ...patch } : a)));
  }

  function onFilesSelected(event: ChangeEvent<HTMLInputElement>) {
    const files = Array.from(event.target.files ?? []);
    // 允许连续选同一文件再次挂载
    event.target.value = "";
    if (files.length === 0) return;
    if (!uploadFile) {
      // 本地挂载态（首页现状）：选择即挂 chip，发出即弃（#280 入口票备案）
      appendAttachments(
        files.map((file, index) => ({
          id: `${fileInputId}-${Date.now()}-${index}`,
          name: file.name,
          sizeLabel: formatFileSize(file.size),
        })),
      );
      return;
    }
    // 真上传（#286）：前端预检（五格式/10MB，省注定 400 的上传）→ 逐文件独立上传，
    // chip 呈现上传中/失败/完成态
    files.forEach((file, index) => {
      const id = `${fileInputId}-${Date.now()}-${index}`;
      if (!isUploadableImageName(file.name)) {
        appendAttachments([
          { id, name: file.name, sizeLabel: formatFileSize(file.size), state: "error",
            error: "只支持 png、jpg、webp、gif、svg 格式" },
        ]);
        return;
      }
      if (file.size > MATERIAL_UPLOAD_LIMIT_BYTES) {
        appendAttachments([
          { id, name: file.name, sizeLabel: formatFileSize(file.size), state: "error",
            error: "超过 10MB 上限" },
        ]);
        return;
      }
      appendAttachments([
        { id, name: file.name, sizeLabel: formatFileSize(file.size), state: "uploading" },
      ]);
      uploadFile(file).then(
        (uploaded) =>
          patchAttachment(id, {
            state: "done",
            path: uploaded.path,
            name: uploaded.name,
          }),
        (failure) =>
          patchAttachment(id, {
            state: "error",
            error: errorText(failure, "上传失败，请重试"),
          }),
      );
    });
  }

  function removeAttachment(id: string) {
    setAttachments((prev) => prev.filter((a) => a.id !== id));
  }

  /** chip 辅助描述：失败显原因、上传中显进度语、完成显大小。 */
  function attachmentDescriptionOf(m: ComposerAttachment): string {
    if (m.state === "error") return m.error ?? "上传失败";
    if (m.state === "uploading") return "上传中…";
    return m.sizeLabel;
  }

  return (
    <div
      className={cn(
        "rounded-2xl bg-background ring-1 ring-border/60 transition-shadow",
        "shadow-[0_12px_32px_-16px_rgb(0_0_0/0.25)]",
        "focus-within:shadow-[0_16px_40px_-16px_rgb(0_0_0/0.3)] focus-within:ring-primary/30",
        hero ? "p-4" : "p-3",
      )}
    >
      <textarea
        ref={setTextarea}
        value={value}
        onChange={(e) => onValueChange(e.target.value)}
        onKeyDown={onKeyDown}
        placeholder={placeholder}
        rows={hero ? 3 : 2}
        disabled={disabled}
        aria-label={placeholder}
        className={cn(
          "w-full resize-none border-0 bg-transparent p-0 outline-none placeholder:text-muted-foreground/70",
          hero ? "min-h-20 max-h-52 text-base" : "min-h-10 max-h-40 text-sm",
        )}
      />

      {attachmentsEnabled && attachments.length > 0 ? (
        <div className="mt-2 flex flex-wrap gap-1.5">
          {attachments.map((m) => (
            <Attachment
              key={m.id}
              size="xs"
              state={m.state ?? "done"}
              data-file-attachment={m.id}
            >
              <AttachmentMedia
                variant={m.state === "done" && materialUrl && m.path ? "image" : "icon"}
              >
                {m.state === "uploading" ? (
                  <Spinner className="size-3.5" />
                ) : m.state === "error" ? (
                  <TriangleAlert className="size-3.5" />
                ) : materialUrl && m.path ? (
                  // eslint-disable-next-line @next/next/no-img-element -- 平台文件服务直出的用户图片，非静态资源（Next Image 不适用）
                  <img src={materialUrl(m.path)} alt={m.name} />
                ) : (
                  <AttachmentIcon name={m.name} />
                )}
              </AttachmentMedia>
              <AttachmentContent>
                <AttachmentTitle>{m.name}</AttachmentTitle>
                <AttachmentDescription>{attachmentDescriptionOf(m)}</AttachmentDescription>
              </AttachmentContent>
              <AttachmentActions>
                <AttachmentAction
                  aria-label={`移除${m.name}`}
                  onClick={() => removeAttachment(m.id)}
                >
                  <X className="size-3" />
                </AttachmentAction>
              </AttachmentActions>
            </Attachment>
          ))}
        </div>
      ) : null}

      {annotations && annotations.length > 0 ? (
        <div className="mt-2 flex flex-wrap gap-1.5">
          {annotations.map((a, i) => (
            <span
              key={a.id}
              className="flex items-center gap-1.5 rounded-lg border bg-muted/50 py-1 pl-2 pr-1 text-xs text-foreground/80"
            >
              <span className="text-muted-foreground">
                <AnnotationKindIcon kind={a.kind} />
              </span>
              {/* 序号 + 类型 + 摘要（#135）：多条圈注靠序号指代，主输入框写「第 1 条…」 */}
              <span className="shrink-0 font-medium text-primary">
                {i + 1}·{annotationLabel(a.kind)}
              </span>
              <span className="max-w-40 truncate">{annotationSummary(a)}</span>
              <button
                type="button"
                className="rounded p-0.5 text-muted-foreground transition-colors hover:bg-background hover:text-foreground"
                onClick={() => onAnnotationRemove?.(a.id)}
                aria-label="移除圈注"
              >
                <X className="size-3" />
              </button>
            </span>
          ))}
        </div>
      ) : null}

      <div className={cn("flex items-center gap-1", hero ? "mt-3" : "mt-2")}>
        {attachmentsEnabled ? (
        <Popover open={materialsOpen} onOpenChange={setMaterialsOpen}>
          <PopoverTrigger
            className="flex items-center gap-1 rounded-lg px-2 py-1.5 text-xs text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
            aria-label="附件"
            disabled={disabled}
          >
            <Paperclip className="size-4" /> 附件
          </PopoverTrigger>
          <PopoverContent align="start" className="w-80 p-0">
            <div className="border-b px-3 py-2 text-xs font-semibold">参考物料</div>
            <div className="max-h-48 overflow-y-auto p-1.5">
              {attachments.map((m) => (
                <div key={m.id} className="flex items-center gap-2 rounded-md px-2 py-1.5 text-sm hover:bg-muted/60">
                  <span className="text-muted-foreground">
                    {m.state === "uploading" ? (
                      <Spinner className="size-3.5" />
                    ) : (
                      <AttachmentIcon name={m.name} />
                    )}
                  </span>
                  <span className="min-w-0 flex-1 truncate">{m.name}</span>
                  <span
                    className={cn(
                      "text-xs text-muted-foreground",
                      m.state === "error" && "text-destructive",
                    )}
                  >
                    {attachmentDescriptionOf(m)}
                  </span>
                </div>
              ))}
              {attachments.length === 0 ? (
                <div className="px-2 py-3 text-center text-xs text-muted-foreground">还没有物料</div>
              ) : null}
            </div>
            <div className="border-t p-2">
              <label
                htmlFor={fileInputId}
                className="flex w-full cursor-pointer flex-col items-center gap-1 rounded-lg border border-dashed px-3 py-4 text-xs text-muted-foreground transition-colors hover:border-primary/40 hover:text-foreground"
              >
                <Upload className="size-4" />
                点击上传
              </label>
              <input
                id={fileInputId}
                type="file"
                multiple
                accept={uploadFile ? ".png,.jpg,.jpeg,.webp,.gif,.svg" : undefined}
                className="sr-only"
                aria-label="上传参考物料"
                onChange={onFilesSelected}
              />
              <p className="px-1 pt-2 text-xs leading-relaxed text-muted-foreground">
                {uploadFile
                  ? "png / jpg / webp / gif / svg，单张不超过 10MB，智能体会参考它做设计与系统。"
                  : "照片、价目表、旧系统截图都可以传，做系统时智能体会参考。"}
              </p>
            </div>
          </PopoverContent>
        </Popover>
        ) : null}
        {onModeChange ? (
        <DropdownMenu>
          <DropdownMenuTrigger
            className="flex items-center gap-1 rounded-lg px-2 py-1.5 text-xs text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
            aria-label={activeMode}
            disabled={disabled}
          >
            <Sparkles className="size-3.5" /> {activeMode} <ChevronDown className="size-3" />
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start">
            {PLATFORM_MODES.map((m) => (
              <DropdownMenuItem
                key={m.label}
                disabled={!m.live}
                onClick={() => m.live && onModeChange(m.label)}
              >
                <span className="flex-1">{m.label}</span>
                {m.label === mode ? <Check className="size-3.5" /> : null}
                {!m.live ? <span className="text-xs text-muted-foreground/60">敬请期待</span> : null}
              </DropdownMenuItem>
            ))}
          </DropdownMenuContent>
        </DropdownMenu>
        ) : null}

        <span className="ml-auto" />
        <Button
          variant="ghost"
          size="icon"
          className="size-8 rounded-full text-muted-foreground"
          aria-label="语音输入"
          disabled
        >
          <Mic className="size-4" />
        </Button>
        <Button
          type="button"
          size="icon"
          className={cn("rounded-full transition-transform active:scale-90", hero ? "size-9" : "size-8")}
          disabled={!canSubmit}
          onClick={submit}
          aria-label="发送"
        >
          {submitPending ? <Spinner className="size-4" /> : <ArrowUp className="size-4" />}
        </Button>
      </div>
    </div>
  );
}
