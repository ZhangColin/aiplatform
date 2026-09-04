"use client";

import {
  ArrowUp,
  Check,
  ChevronDown,
  FileText,
  Image as ImageIcon,
  Mic,
  Paperclip,
  Sparkles,
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
import { cn } from "@/lib/utils";
import { isSubmitEnter } from "@/lib/chat/enter";
import { formatFileSize } from "@/lib/projects/files";
import { PLATFORM_MODES } from "@/lib/modes";

/**
 * 共享发送框（#72 定稿 / #76 落地）：首页 hero 与项目页同一组件的立体卡片
 * （ring + 分层阴影）——输入区 → 附件 chip 行（可删可加）→ 工具行（回形针
 * 物料区 / 类型下拉 / 语音位 / 圆形发送键）。
 *
 * 输入受控（首页示例 chips 点选填入、项目页接对话流都由调用侧持态）；附件为
 * 组件内本地态——上传管道不在本票（#76「沿用既有上传能力」，现有服务端无
 * 上传端点），选择即挂 chip、可删可加，随 onSubmit 一并交出、发出即清；
 * 调用侧消费不了附件时传 attachmentsEnabled=false 隐去入口（不邀请会被
 * 丢弃的操作）。类型下拉 v1 仅「做系统」，做页面/写文档为「敬请期待」占位。
 */

/** 附件条目（物料区与 chip 行共用形状）。 */
export type ComposerAttachment = {
  id: string;
  name: string;
  sizeLabel: string;
};

/** 图片物料判定（chip/列表图标分流：图用图片图标，其余按文档）。 */
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

export function Composer({
  hero = false,
  value,
  onValueChange,
  onSubmit,
  submitPending = false,
  disabled = false,
  attachmentsEnabled = true,
  inputRef,
  placeholder = "说说你想做什么…",
}: {
  /** hero = 首页居中大框（加大一号）；项目页常规尺寸。 */
  hero?: boolean;
  value: string;
  onValueChange: (text: string) => void;
  /** 发送：文本 + 当次附件（发出后组件内附件清空，输入归调用侧）。 */
  onSubmit: (text: string, attachments: ComposerAttachment[]) => void;
  /** 提交进行中（发送键转 Spinner 且禁用）。 */
  submitPending?: boolean;
  /** 整体禁用（项目页锁定态：输入与发送停用）。 */
  disabled?: boolean;
  /** 附件入口（回形针 + chip 行）：调用侧无上传管道时置 false 隐去——不邀请会被丢弃的操作。 */
  attachmentsEnabled?: boolean;
  /** 输入框外接 ref（项目页：问题到达自动聚焦）。 */
  inputRef?: RefObject<HTMLTextAreaElement | null>;
  placeholder?: string;
}) {
  const [attachments, setAttachments] = useState<ComposerAttachment[]>([]);
  const [materialsOpen, setMaterialsOpen] = useState(false);
  const [mode, setMode] = useState<string>(PLATFORM_MODES[0].label);
  const fileInputId = useId();

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

  const canSubmit = value.trim().length > 0 && !submitPending && !disabled;

  function submit() {
    if (!canSubmit) return;
    onSubmit(value, attachments);
    setAttachments([]);
  }

  function onKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (!isSubmitEnter(event)) return;
    event.preventDefault();
    submit();
  }

  function onFilesSelected(event: ChangeEvent<HTMLInputElement>) {
    const files = Array.from(event.target.files ?? []);
    if (files.length > 0) {
      setAttachments((prev) => [
        ...prev,
        ...files.map((file, index) => ({
          id: `${fileInputId}-${Date.now()}-${index}`,
          name: file.name,
          sizeLabel: formatFileSize(file.size),
        })),
      ]);
    }
    // 允许连续选同一文件再次挂载
    event.target.value = "";
  }

  function removeAttachment(id: string) {
    setAttachments((prev) => prev.filter((a) => a.id !== id));
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
            <span
              key={m.id}
              className="flex items-center gap-1.5 rounded-lg border bg-muted/50 py-1 pl-2 pr-1 text-xs text-foreground/80"
            >
              <span className="text-muted-foreground">
                <AttachmentIcon name={m.name} />
              </span>
              {m.name}
              <button
                type="button"
                className="rounded p-0.5 text-muted-foreground transition-colors hover:bg-background hover:text-foreground"
                onClick={() => removeAttachment(m.id)}
                aria-label={`移除${m.name}`}
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
                    <AttachmentIcon name={m.name} />
                  </span>
                  <span className="min-w-0 flex-1 truncate">{m.name}</span>
                  <span className="text-xs text-muted-foreground">{m.sizeLabel}</span>
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
                className="sr-only"
                aria-label="上传参考物料"
                onChange={onFilesSelected}
              />
              <p className="px-1 pt-2 text-xs leading-relaxed text-muted-foreground">
                照片、价目表、旧系统截图都可以传，做系统时智能体会参考。
              </p>
            </div>
          </PopoverContent>
        </Popover>
        ) : null}
        <DropdownMenu>
          <DropdownMenuTrigger
            className="flex items-center gap-1 rounded-lg px-2 py-1.5 text-xs text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
            aria-label={mode}
            disabled={disabled}
          >
            <Sparkles className="size-3.5" /> {mode} <ChevronDown className="size-3" />
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start">
            {PLATFORM_MODES.map((m) => (
              <DropdownMenuItem
                key={m.label}
                disabled={!m.live}
                onClick={() => m.live && setMode(m.label)}
              >
                <span className="flex-1">{m.label}</span>
                {m.label === mode ? <Check className="size-3.5" /> : null}
                {!m.live ? <span className="text-xs text-muted-foreground/60">敬请期待</span> : null}
              </DropdownMenuItem>
            ))}
          </DropdownMenuContent>
        </DropdownMenu>

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
