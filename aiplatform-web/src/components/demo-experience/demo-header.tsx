"use client";

import { ArrowRight, CloudCheck } from "lucide-react";

import { Skeleton } from "@/components/ui/skeleton";
import { cn } from "@/lib/utils";

import { DemoVersionChip } from "../project-page/demo-version-chip";

export type DemoHeaderStatus = "ready" | "generating" | "interrupted" | "waiting";

/** Demo P04/P05 项目横幅：真实项目/版本事实套原型话术与动作位。 */
export function DemoHeader({
  projectId,
  projectName,
  status,
  loading = false,
  confirmDisabled,
  confirmPending,
  confirmLabel,
  onConfirm,
  onStageSave,
}: {
  projectId: string;
  projectName: string;
  status: DemoHeaderStatus;
  loading?: boolean;
  confirmDisabled: boolean;
  confirmPending: boolean;
  /** 主按钮文案覆盖（#350 重试位＝「重新提交订单」）；缺省「确认此版本，准备签约」。 */
  confirmLabel?: string;
  onConfirm: () => void;
  onStageSave: () => void;
}) {
  const statusText = {
    ready: "预览运行中 (Ready)",
    generating: "Demo 生成中 (Generating)",
    interrupted: "生成中断 (Interrupted)",
    waiting: "等待生成 (Waiting)",
  }[status];

  return (
    <header className="sticky top-16 z-30 flex shrink-0 flex-wrap items-center justify-between gap-3 border-b border-slate-soft/70 bg-linear-to-r from-white via-surface-violet-tint/40 to-surface-mist/40 px-4 py-3 shadow-xs backdrop-blur-md lg:px-8">
      <div className="flex min-w-0 flex-wrap items-center gap-3">
        <div className="flex min-w-0 items-center gap-2">
          <span className="rounded-md border border-electric-indigo/20 bg-surface-violet-tint px-2 py-0.5 font-mono text-code-sm font-semibold tracking-wider text-electric-indigo">
            PROJECT
          </span>
          {loading ? (
            <Skeleton className="h-6 w-44" />
          ) : (
            <h1 className="truncate text-base font-bold text-ink-primary">
              {projectName || "未命名项目"}
            </h1>
          )}
        </div>
        <span className="text-ink-faint/40" aria-hidden="true">/</span>
        <DemoVersionChip projectId={projectId} variant="banner" />
        <span
          className={cn(
            "inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-label-sm font-medium shadow-xs",
            status === "ready" && "border-emerald-active/30 bg-emerald-active/10 text-success-deep",
            status === "generating" && "border-electric-indigo/25 bg-surface-violet-tint text-electric-indigo",
            status === "interrupted" && "border-error/30 bg-error/10 text-error",
            status === "waiting" && "border-slate-soft/70 bg-surface-dawn text-ink-muted",
          )}
        >
          <span
            className={cn(
              "size-1.5 rounded-full",
              status === "ready" && "animate-pulse bg-emerald-active",
              status === "generating" && "animate-pulse bg-electric-indigo",
              status === "interrupted" && "bg-error",
              status === "waiting" && "bg-ink-faint",
            )}
            aria-hidden="true"
          />
          {statusText}
        </span>
      </div>

      <div className="flex shrink-0 items-center gap-2.5">
        <button
          type="button"
          onClick={onStageSave}
          className="inline-flex items-center gap-1.5 rounded-lg border border-slate-soft/80 bg-white px-3 py-1.5 text-label-md font-medium text-ink-secondary shadow-xs transition-colors hover:bg-surface-dawn"
        >
          <CloudCheck className="size-4 text-ink-faint" aria-hidden="true" />
          阶段存盘
        </button>
        <button
          type="button"
          disabled={confirmDisabled || confirmPending}
          onClick={onConfirm}
          className="inline-flex items-center gap-1.5 rounded-lg bg-linear-to-r from-electric-indigo to-tech-blue px-3.5 py-1.5 text-label-md font-semibold text-white shadow-xs transition-all hover:brightness-105 active:scale-[0.99] disabled:pointer-events-none disabled:opacity-45"
        >
          {confirmPending ? "正在确认…" : (confirmLabel ?? "确认此版本，准备签约")}
          <ArrowRight className="size-4" aria-hidden="true" />
        </button>
      </div>
    </header>
  );
}
