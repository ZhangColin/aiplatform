"use client";

import { MessageSquarePlus, MousePointer2, SquareDashed, Type, X } from "lucide-react";

import { cn } from "@/lib/utils";
import type { AnnotationKind } from "@/lib/preview/annotation";

/**
 * 预览底部浮动工具条（#80 形态位占位 → #97 圈注落地 → #135 四键终稿，对齐
 * Lovable preview toolbar）：四键全留——**选择 / 圈选** 启用（点击进入标注态，
 * 非常驻：再点同键或「退出」即退出，切键直接换模式），**改字 / 评论** 置灰待
 * 启用（只留形态位，均为未来增强）。label 与 CONTEXT.md 词条口径一致（选择 /
 * 改字 / 圈选 / 评论）；激活态实底高亮 + aria-pressed，当前模式一眼可辨。工具条
 * 是平台件（主题随平台走），浮在浅色锁定的舞台上。activeTool 归装配层持态
 * （system-panel 据此对 iframe 发 postMessage 进出标注态）。
 */
export function PreviewToolbar({
  activeTool,
  onToolToggle,
  onExit,
}: {
  /** 当前激活的圈注工具（null = 正常预览浏览）。 */
  activeTool?: AnnotationKind | null;
  /** 工具点选：激活该工具进入标注态；再点已激活工具即退出。 */
  onToolToggle?: (tool: AnnotationKind) => void;
  /** 显式退出标注态（「退出」键）。 */
  onExit?: () => void;
}) {
  return (
    <div className="pointer-events-none absolute inset-x-0 bottom-3 z-10 flex justify-center">
      <div className="pointer-events-auto flex items-center gap-0.5 rounded-full border bg-background/95 px-1.5 py-1 shadow-lg backdrop-blur">
        <ToolButton
          icon={MousePointer2}
          label="选择"
          active={activeTool === "select"}
          onClick={() => onToolToggle?.("select")}
        />
        <DisabledToolButton icon={Type} label="改字（待启用）" title="改字（未来增强）" />
        <ToolButton
          icon={SquareDashed}
          label="圈选"
          active={activeTool === "circle"}
          onClick={() => onToolToggle?.("circle")}
        />
        <DisabledToolButton icon={MessageSquarePlus} label="评论（待启用）" title="评论（未来增强）" />
        <span className="mx-0.5 h-4 w-px bg-border" />
        {activeTool ? (
          <button
            type="button"
            onClick={onExit}
            aria-label="退出标注"
            className="flex items-center gap-1 rounded-full px-2 py-1 text-xs text-primary transition-colors hover:bg-muted"
          >
            <X className="size-3.5" />
            退出
          </button>
        ) : (
          <span className="px-1.5 text-xs text-muted-foreground/60">圈一下</span>
        )}
      </div>
    </div>
  );
}

function ToolButton({
  icon: Icon,
  label,
  active,
  onClick,
}: {
  icon: typeof MousePointer2;
  label: string;
  active: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={label}
      aria-pressed={active}
      title={label}
      className={cn(
        "rounded-full p-2 transition-colors",
        // 激活态实底高亮（#135 强化）：与未激活的 muted 一眼可辨
        active
          ? "bg-primary text-primary-foreground shadow-sm"
          : "text-muted-foreground hover:bg-muted hover:text-foreground",
      )}
    >
      <Icon className="size-3.5" />
    </button>
  );
}

/** 置灰形态位（改字 / 评论）：能力占位不可点——有这个位、现在不能用。 */
function DisabledToolButton({
  icon: Icon,
  label,
  title,
}: {
  icon: typeof MousePointer2;
  label: string;
  title: string;
}) {
  return (
    <button
      type="button"
      disabled
      aria-label={label}
      title={title}
      className="cursor-not-allowed rounded-full p-2 text-muted-foreground/40"
    >
      <Icon className="size-3.5" />
    </button>
  );
}
