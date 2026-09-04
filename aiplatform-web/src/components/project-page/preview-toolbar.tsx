"use client";

import { MessageSquarePlus, MousePointer2, Pencil, Type } from "lucide-react";

/**
 * 预览底部浮动工具条（#80 形态位占位）：选择 / 改字 / 圈选 / 评论四件形态
 * 就位——本票只落形态，可点击的仅真实现的能力，眼下四件皆未实现，整条置灰
 * 标注「待启用」：圈选（圈一下）真功能归圈注票 #97，改字为未来增强备案，
 * 选择 / 评论随后续票接入启用。平台件（主题随平台走），浮在浅色锁定的舞台上。
 */
const TOOLS = [
  { id: "select", icon: MousePointer2, label: "选择组件" },
  { id: "text", icon: Type, label: "直接改文字" },
  { id: "draw", icon: Pencil, label: "画笔圈选" },
  { id: "comment", icon: MessageSquarePlus, label: "评论" },
] as const;

export function PreviewToolbar() {
  return (
    <div className="pointer-events-none absolute inset-x-0 bottom-3 z-10 flex justify-center">
      <div className="pointer-events-auto flex items-center gap-0.5 rounded-full border bg-background/95 px-1.5 py-1 shadow-lg backdrop-blur">
        {TOOLS.map((tool) => (
          <button
            key={tool.id}
            type="button"
            disabled
            aria-label={`${tool.label}（待启用）`}
            className="cursor-not-allowed rounded-full p-2 text-muted-foreground/60"
          >
            <tool.icon className="size-3.5" />
          </button>
        ))}
        <span className="mx-0.5 h-4 w-px bg-border" />
        <span className="px-1.5 text-xs text-muted-foreground/60">待启用</span>
      </div>
    </div>
  );
}
