import { ArrowRight, CheckCircle2 } from "lucide-react";

/** 右栏第三段：确认当前 Demo 版本的门开动作（确认标记由装配层接出）。 */
export function DemoConfirmCard({
  disabled,
  pending,
  confirmed,
  onConfirm,
}: {
  disabled: boolean;
  pending: boolean;
  confirmed: boolean;
  onConfirm: () => void;
}) {
  return (
    <section
      aria-label="确认此版本进入签约"
      className="flex flex-col gap-3 rounded-2xl border border-electric-indigo/20 bg-linear-to-br from-surface-violet-tint via-white to-surface-mist/50 p-4 shadow-e1"
    >
      <div className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-1.5">
          <CheckCircle2 className="size-4.5 text-electric-indigo" aria-hidden="true" />
          <h2 className="text-headline-sm font-bold text-ink-primary">确认此版本进入签约</h2>
        </div>
        {confirmed ? (
          <span className="rounded-full border border-emerald-active/30 bg-emerald-active/10 px-2 py-0.5 text-label-sm font-medium text-success-deep">
            已确认
          </span>
        ) : null}
      </div>
      <p className="text-body-sm leading-relaxed text-ink-muted">
        Demo 满足业务初设后，即可推进至合同签署与排期承接阶段，锁定开发节点。
      </p>
      <div className="flex items-center justify-between rounded-xl border border-slate-soft/70 bg-white px-3 py-2 shadow-xs">
        <PipelineStep number="1" label="PRD 确定" state="done" />
        <ArrowRight className="size-3.5 text-slate-300" aria-hidden="true" />
        <PipelineStep number="2" label="Demo 确定" state={confirmed ? "done" : "current"} />
        <ArrowRight className="size-3.5 text-slate-300" aria-hidden="true" />
        <PipelineStep number="3" label="签约付款" state="next" />
      </div>
      <button
        type="button"
        disabled={disabled || pending || confirmed}
        onClick={onConfirm}
        className="flex w-full items-center justify-center gap-2 rounded-xl bg-linear-to-r from-electric-indigo via-electric-indigo to-tech-blue px-4 py-3 text-headline-sm font-bold text-white shadow-md transition-all hover:brightness-105 active:scale-[0.99] disabled:pointer-events-none disabled:opacity-50"
      >
        {pending ? "正在确认当前版本…" : confirmed ? "当前版本已确认" : "确认当前版本 · 进入签约流程"}
        <ArrowRight className="size-4.5" aria-hidden="true" />
      </button>
    </section>
  );
}

function PipelineStep({
  number,
  label,
  state,
}: {
  number: string;
  label: string;
  state: "done" | "current" | "next";
}) {
  return (
    <div className="flex items-center gap-1.5">
      <span
        className={
          state === "done"
            ? "flex size-5 items-center justify-center rounded-full border border-emerald-active/30 bg-emerald-active/10 font-mono text-[11px] font-semibold text-success-deep"
            : state === "current"
              ? "flex size-5 items-center justify-center rounded-full bg-electric-indigo font-mono text-[11px] font-semibold text-white shadow-xs"
              : "flex size-5 items-center justify-center rounded-full bg-surface-dawn font-mono text-[11px] text-ink-faint"
        }
      >
        {number}
      </span>
      <span
        className={
          state === "current"
            ? "text-label-sm font-bold text-electric-indigo"
            : state === "done"
              ? "text-label-sm font-medium text-ink-muted"
              : "text-label-sm font-medium text-ink-faint"
        }
      >
        {label}
      </span>
    </div>
  );
}
