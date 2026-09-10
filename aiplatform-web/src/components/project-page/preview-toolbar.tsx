"use client";

import { MessageSquarePlus, MousePointer2, Pencil, Type, X } from "lucide-react";

import { cn } from "@/lib/utils";
import type { AnnotationKind } from "@/lib/preview/annotation";

/**
 * 预览底部浮动工具条（#80 形态位占位 → #97 圈注落地）：三能力——点选（选择组件）/
 * 画笔圈选 / 评论——真实现、可点击，点击即呼出标注态（非常驻：再点同键或「退出」
 * 即退出，不影响正常预览浏览）；「直接改文字」为未来增强备案，保持置灰。工具条是
 * 平台件（主题随平台走），浮在浅色锁定的舞台上。activeTool 归装配层持态（system-
 * panel 据此对 iframe 发 postMessage 进出标注态）。
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
          label="选择组件"
          active={activeTool === "select"}
          onClick={() => onToolToggle?.("select")}
        />
        <ToolButton
          icon={Pencil}
          label="画笔圈选"
          active={activeTool === "circle"}
          onClick={() => onToolToggle?.("circle")}
        />
        <ToolButton
          icon={MessageSquarePlus}
          label="评论"
          active={activeTool === "comment"}
          onClick={() => onToolToggle?.("comment")}
        />
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
      title={label}
      className={cn(
        "rounded-full p-2 transition-colors",
        active
          ? "bg-primary/15 text-primary"
          : "text-muted-foreground hover:bg-muted hover:text-foreground",
      )}
    >
      <Icon className="size-3.5" />
    </button>
  );
}
