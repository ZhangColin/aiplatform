"use client";

import { MessageSquarePlus, MousePointer2, Pencil, Type } from "lucide-react";

/**
 * 预览底部浮动工具条（#80 形态位占位 → #97 圈注落地 → #127 置灰待启用）：三能力
 * ——点选（选择组件）/ 画笔圈选 / 评论——#127 起置灰待启用，诚实呈现「未接好」
 * （圈注标注脚本未注入主应用，注入归网关 #122，落地后再启用）；「直接改文字」为
 * 未来增强备案，保持置灰。工具条是平台件（主题随平台走），浮在浅色锁定的舞台上。
 */
export function PreviewToolbar() {
  return (
    <div className="pointer-events-none absolute inset-x-0 bottom-3 z-10 flex justify-center">
      <div className="pointer-events-auto flex items-center gap-0.5 rounded-full border bg-background/95 px-1.5 py-1 shadow-lg backdrop-blur">
        <ToolButton icon={MousePointer2} label="选择组件" />
        <ToolButton icon={Pencil} label="画笔圈选" />
        <ToolButton icon={MessageSquarePlus} label="评论" />
        <button
          type="button"
          disabled
          aria-label="直接改文字（待启用）"
          title="直接改文字（未来增强）"
          className="cursor-not-allowed rounded-full p-2 text-muted-foreground/40"
        >
          <Type className="size-3.5" />
        </button>
        <span className="mx-0.5 h-4 w-px bg-border" />
        <span className="px-1.5 text-xs text-muted-foreground/60">圈一下</span>
      </div>
    </div>
  );
}

/** 置灰待启用的工具键（#127）：能力未接好前不可点，与「改字」同一置灰样式。 */
function ToolButton({
  icon: Icon,
  label,
}: {
  icon: typeof MousePointer2;
  label: string;
}) {
  return (
    <button
      type="button"
      disabled
      aria-label={`${label}（待启用）`}
      title={`${label}（待网关注入后启用）`}
      className="cursor-not-allowed rounded-full p-2 text-muted-foreground/40"
    >
      <Icon className="size-3.5" />
    </button>
  );
}
