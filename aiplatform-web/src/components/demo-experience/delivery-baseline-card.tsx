import { Check, Circle, ClipboardCheck, TriangleAlert } from "lucide-react";

import type { components } from "@/lib/api/schema";
import type { GenerationSegmentFact } from "@/lib/projects/detail";
import { cn } from "@/lib/utils";
import { formatRelativeTime } from "@/lib/utils/time";

type DemoVersion = components["schemas"]["DemoVersionSummaryResponse"];

/** 右栏第一段：基准清单吃真实生成轨切片，历史流水吃真实 Demo 成版记录。 */
export function DeliveryBaselineCard({
  segments,
  versions,
  prdVersionNo,
  generating,
  failed,
}: {
  segments?: GenerationSegmentFact[] | null;
  versions: DemoVersion[];
  prdVersionNo?: number | null;
  generating: boolean;
  failed: boolean;
}) {
  const baseline = segments ?? [];
  const allGreen =
    versions.length > 0 &&
    !generating &&
    !failed &&
    baseline.every((segment) => segment.status === "closed");
  const flowStatus = allGreen ? "ALL GREEN" : generating ? "GENERATING" : failed ? "ATTENTION" : "WAITING";

  return (
    <section
      aria-label="版本交付基准"
      className="flex flex-col gap-3 rounded-2xl border border-slate-soft/80 bg-white p-4 shadow-e1"
    >
      <div className="flex items-center justify-between gap-2 border-b border-slate-soft/60 pb-2">
        <div className="flex items-center gap-1.5">
          <ClipboardCheck className="size-4.5 text-electric-indigo" aria-hidden="true" />
          <h2 className="text-headline-sm font-bold text-ink-primary">版本交付基准</h2>
        </div>
        {prdVersionNo != null ? (
          <span className="rounded-full border border-electric-indigo/20 bg-surface-violet-tint px-2 py-0.5 font-mono text-code-sm font-semibold text-electric-indigo">
            PRD v{prdVersionNo} 对齐
          </span>
        ) : (
          <span className="rounded-full border border-slate-soft/60 bg-surface-dawn px-2 py-0.5 font-mono text-code-sm text-ink-faint">
            PRD 对齐
          </span>
        )}
      </div>

      <div className="flex flex-col gap-2">
        {baseline.length > 0 ? (
          baseline.map((segment) => (
            <div
              key={segment.ord}
              className="flex items-center gap-2.5 rounded-xl border border-slate-soft/60 bg-surface-dawn/70 p-2"
            >
              <SegmentMark status={segment.status} />
              <span className="text-body-sm font-medium text-ink-secondary">{segment.description}</span>
            </div>
          ))
        ) : (
          <p className="rounded-xl border border-slate-soft/60 bg-surface-dawn/70 p-3 text-body-sm text-ink-faint">
            当前交付基准将在生成计划就绪后显示。
          </p>
        )}
      </div>

      <div className="flex flex-col gap-2 border-t border-slate-soft/60 pt-2">
        <div className="flex items-center justify-between gap-2">
          <span className="text-label-sm font-medium text-ink-faint">历史版本生成流流水</span>
          <span
            className={cn(
              "flex items-center gap-1 rounded-full border px-2 py-0.5 font-mono text-[10px] font-bold",
              allGreen
                ? "border-emerald-active/30 bg-emerald-active/10 text-success-deep"
                : "border-electric-indigo/20 bg-surface-violet-tint text-electric-indigo",
            )}
          >
            <span className={cn("size-1.5 rounded-full", allGreen ? "animate-pulse bg-emerald-active" : "bg-electric-indigo")} />
            {flowStatus}
          </span>
        </div>
        {versions.length > 0 ? (
          <ol className="flex flex-col gap-1 rounded-xl border border-slate-soft/60 bg-surface-dawn/80 p-2.5 font-mono text-code-sm text-ink-muted">
            {versions.slice(0, 3).map((version, index, visible) => (
              <li key={version.versionNo} className="flex items-center justify-between gap-3">
                <span className="min-w-0 truncate" title={version.createdAt}>
                  [{String(index + 1).padStart(2, "0")}/{String(visible.length).padStart(2, "0")}] Demo v{version.versionNo}
                  {version.prdVersionNo != null ? ` · PRD v${version.prdVersionNo} 快照` : ""}
                  {version.createdAt ? ` · ${formatRelativeTime(version.createdAt)}` : ""}
                </span>
                <span className="shrink-0 font-bold text-emerald-600">✓ 已成版</span>
              </li>
            ))}
          </ol>
        ) : (
          <p className="rounded-xl border border-slate-soft/60 bg-surface-dawn/80 p-2.5 text-body-sm text-ink-faint">
            首轮构建收口后，真实版本流水会记录在这里。
          </p>
        )}
      </div>

      <div className="grid gap-2 border-t border-slate-soft/60 pt-2 text-body-sm sm:grid-cols-2 xl:grid-cols-1 2xl:grid-cols-2">
        <div className="rounded-xl bg-surface-mist/60 p-2.5">
          <span className="font-medium text-electric-indigo">演示数据</span>
          <p className="mt-0.5 text-ink-faint">预置示例数据已进入沙箱真库，便于体验完整业务流程。</p>
        </div>
        <div className="rounded-xl bg-surface-dawn/80 p-2.5">
          <span className="font-medium text-ink-muted">演示限制</span>
          <p className="mt-0.5 text-ink-faint">Demo 用于验证业务初设，正式交付范围以合同确认为准。</p>
        </div>
      </div>
    </section>
  );
}

function SegmentMark({ status }: { status: GenerationSegmentFact["status"] }) {
  if (status === "closed") {
    return (
      <span className="flex size-4 shrink-0 items-center justify-center rounded-full bg-emerald-500 text-white shadow-xs">
        <Check className="size-3" strokeWidth={3} aria-hidden="true" />
      </span>
    );
  }
  if (status === "failed") {
    return (
      <span className="flex size-4 shrink-0 items-center justify-center rounded-full bg-error/15 text-error">
        <TriangleAlert className="size-3" aria-hidden="true" />
      </span>
    );
  }
  return <Circle className="size-4 shrink-0 text-ink-faint/50" aria-hidden="true" />;
}
