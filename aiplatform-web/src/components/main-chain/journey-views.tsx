import { Check } from "lucide-react";
import { Fragment } from "react";

import type { JourneyStep } from "@/lib/main-chain/journey";
import { cn } from "@/lib/utils";

import { StatusBreadcrumb } from "./step-breadcrumb";

/**
 * 需求端口径的旅程呈现（共享层资产，spec 0002 §5）：消费 mapStagesToJourney 的
 * 产出——文案全部来自映射表（禁词红线由 journey.test.ts 守住），组件不再另写
 * 文案。Timeline = 右栏旅程竖排；Breadcrumb = 顶栏六步面包屑；Mini = 卡片横排。
 */

export function JourneyTimeline({
  steps,
  className,
}: {
  steps: JourneyStep[];
  className?: string;
}) {
  if (steps.length === 0) return null;
  return (
    <div className={cn("space-y-1.5", className)}>
      {steps.map((step, i) => {
        const isCurrent = step.status === "current";
        const done = step.status === "done";
        return (
          <div
            key={step.key}
            className={cn(
              "flex items-center gap-2 text-sm",
              step.status === "future" && "text-muted-foreground/50",
            )}
          >
            <span
              className={cn(
                "grid size-4.5 shrink-0 place-items-center rounded-full text-[10px]",
                done || step.terminal
                  ? "bg-emerald-500 text-white"
                  : isCurrent
                    ? "bg-primary/15 font-semibold text-primary"
                    : "bg-muted text-muted-foreground",
              )}
            >
              {done || step.terminal ? <Check className="size-3" /> : i + 1}
            </span>
            <span className="truncate">{step.label}</span>
            {isCurrent && !step.terminal && (
              <span className="text-xs text-muted-foreground">← 现在</span>
            )}
            {isCurrent && step.gateLabel && (
              <span className="ml-auto shrink-0 text-xs font-medium text-amber-600 dark:text-amber-400">
                等你拍板 · {step.gateLabel}
              </span>
            )}
          </div>
        );
      })}
    </div>
  );
}

/** 顶栏六步面包屑：当前步高亮。steps 空时由场景层给兜底（组件不造文案）。 */
export function JourneyBreadcrumb({
  steps,
  className,
}: {
  steps: JourneyStep[];
  className?: string;
}) {
  if (steps.length === 0) return null;
  return (
    <StatusBreadcrumb
      className={className}
      items={steps.map(({ key, label, status }) => ({ key, label, status }))}
    />
  );
}

/** 卡片横排 mini 进度（列表卡消费）：done 实心 / current 高亮 / future 描边，尾随当前步文案。 */
export function JourneyMiniProgress({
  steps,
  className,
}: {
  steps: JourneyStep[];
  className?: string;
}) {
  if (steps.length === 0) return null;
  const current = steps.find((step) => step.status === "current") ?? steps[steps.length - 1];
  if (!current) return null;
  return (
    <div className={cn("flex items-center gap-0.5", className)}>
      {steps.map((step, i) => (
        <Fragment key={step.key}>
          {i > 0 && <span className="h-px flex-1 bg-border" />}
          <span
            aria-hidden
            className={cn(
              "size-2 shrink-0 rounded-full",
              step.status === "done" || step.terminal
                ? "bg-emerald-500"
                : step.status === "current"
                  ? "bg-primary"
                  : "bg-border",
            )}
          />
        </Fragment>
      ))}
      <span className="ml-2 truncate text-xs text-muted-foreground">{current.label}</span>
    </div>
  );
}
