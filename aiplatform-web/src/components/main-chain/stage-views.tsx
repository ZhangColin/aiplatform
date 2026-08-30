import { Check, DoorClosed } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import type { StageProgress } from "@/lib/main-chain/stages";
import { cn } from "@/lib/utils";

import { StatusBreadcrumb } from "./step-breadcrumb";

/**
 * 开发平台口径的段呈现（共享层资产，issue #19 / spec 0001 §3）：完全由详情
 * `stages[]` 推导（deriveStageProgress）驱动——已完成 ✓ / 当前高亮 / 未来半透明，
 * 不写死六段。Timeline = 右栏面板时间线；Breadcrumb = 顶栏紧凑步条。
 */

export function StageTimeline({
  progress,
  className,
}: {
  progress: StageProgress;
  className?: string;
}) {
  if (progress.steps.length === 0) return null;
  return (
    <div className={cn("space-y-1", className)}>
      {progress.steps.map(({ stage, status }) => {
        const isCurrent = status === "current";
        return (
          <div
            key={stage.name ?? stage.label}
            className={cn(
              "flex items-center gap-2 rounded-md px-2.5 py-2 text-sm",
              status === "done" && "text-muted-foreground",
              isCurrent && "border border-primary/40 bg-primary/5 font-medium text-foreground",
              status === "future" && "text-muted-foreground/60",
            )}
          >
            {status === "done" ? (
              <Check className="size-3.5 shrink-0 text-emerald-600" />
            ) : (
              <span
                className={cn(
                  "size-3.5 shrink-0 rounded-full border",
                  isCurrent && "border-primary bg-primary",
                )}
              />
            )}
            <span className="truncate">{stage.label ?? stage.name}</span>
            {stage.gateActor && (
              <DoorClosed className="size-3 shrink-0 text-muted-foreground/70" aria-label="有确认门" />
            )}
            {isCurrent &&
              (progress.terminal ? (
                <Badge variant="secondary" className="ml-auto h-5 text-[10px]">
                  已收口
                </Badge>
              ) : (
                <Badge className="ml-auto h-5 text-[10px]">当前</Badge>
              ))}
          </div>
        );
      })}
    </div>
  );
}

/** 顶栏紧凑步条。steps 缺失 / stage 未命中时回退单标签（详情 stageLabel）。 */
export function StageBreadcrumb({
  progress,
  fallbackLabel,
  className,
}: {
  progress: StageProgress;
  /** steps 缺失 / stage 未命中时的兜底标签（详情 stageLabel）。 */
  fallbackLabel?: string;
  className?: string;
}) {
  if (progress.steps.length === 0 || progress.currentIndex < 0) {
    if (!fallbackLabel) return null;
    return (
      <span className={cn("text-xs font-medium text-foreground", className)}>{fallbackLabel}</span>
    );
  }
  return (
    <StatusBreadcrumb
      className={className}
      items={progress.steps.map(({ index, stage, status }) => ({
        key: stage.name ?? stage.label ?? `stage-${index}`,
        label: stage.label ?? stage.name ?? "",
        status,
      }))}
    />
  );
}
