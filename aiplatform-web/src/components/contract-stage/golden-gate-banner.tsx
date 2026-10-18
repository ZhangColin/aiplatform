"use client";

import { ShieldAlert, ShieldCheck } from "lucide-react";

import { cn } from "@/lib/utils";
import type { GoldenGateState } from "@/lib/orders/contract-stage";

/**
 * 黄金门禁横幅（#348，spec #320 ⑥ 照原型）：DELIVERY GOLDEN GATE 双重门禁
 * 阻断机制——公式逐字照原型：{@code 【允许进入生产】= 合同要求的全部签署完成 &&
 * 合同约定的开工付款条件已满足}。左项（签署）与右项（开工款到账）各自「已达标 ✓
 * / 待达成」、横幅右侧状态位 DISPATCH LOCKED → GATE PERMITTED 翻态——输入即
 * {@link contractStageOf} 的推导结果，这里只呈现不判定。
 */
export function GoldenGateBanner({
  gate,
  contractSigned,
  kickoffSettled,
}: {
  gate: GoldenGateState;
  contractSigned: boolean;
  kickoffSettled: boolean;
}) {
  const permitted = gate === "permitted";

  return (
    <section
      aria-label="黄金门禁"
      className="flex flex-col gap-3 rounded-xl border border-subtle-blue bg-linear-to-r from-surface-mist via-emerald-active/5 to-surface-dawn p-4 shadow-sm md:flex-row md:items-center md:justify-between"
    >
      <div className="flex items-start gap-3.5 md:items-center">
        <div
          className={cn(
            "flex size-10 shrink-0 items-center justify-center rounded-xl text-white",
            permitted
              ? "bg-linear-to-br from-emerald-active to-mint-teal"
              : "bg-linear-to-br from-electric-indigo to-mint-teal",
          )}
        >
          {permitted ? (
            <ShieldCheck className="size-5" aria-hidden="true" />
          ) : (
            <ShieldAlert className="size-5" aria-hidden="true" />
          )}
        </div>
        <div className="flex flex-col">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-headline-sm font-bold text-tech-blue">严正开工法则 (DELIVERY GOLDEN GATE)</span>
            <span className="rounded-md border border-tech-blue/25 bg-white/90 px-2 py-0.5 font-mono text-code-sm font-semibold text-tech-blue">
              双重门禁阻断机制
            </span>
          </div>
          <p className="mt-0.5 text-body-md text-ink-muted">
            <span className="font-semibold text-ink-primary">【允许进入生产】</span>
            <span aria-hidden="true"> </span>= 合同要求的全部签署完成{" "}
            <TermBadge met={contractSigned} metText="已达标 ✓" unmetText="待签署" />
            <span aria-hidden="true"> ＆＆ </span>
            合同约定的开工付款条件已满足{" "}
            <TermBadge met={kickoffSettled} metText="已达标 ✓" unmetText="待上传核验" />
          </p>
        </div>
      </div>

      <div
        className={cn(
          "flex shrink-0 items-center gap-2 self-start rounded-lg border bg-white/80 px-3 py-1.5 md:self-auto",
          permitted ? "border-emerald-active/30" : "border-slate-soft",
        )}
      >
        <span
          className={cn(
            "size-2.5 rounded-full",
            permitted ? "bg-emerald-active" : "bg-error",
          )}
          aria-hidden="true"
        />
        <span
          className={cn(
            "font-mono text-xs font-bold tracking-wide uppercase",
            permitted ? "text-success-deep" : "text-ink-secondary",
          )}
        >
          {permitted ? "GATE PERMITTED" : "DISPATCH LOCKED"}
        </span>
      </div>
    </section>
  );
}

/** 公式单项：已达标＝薄荷绿「已达标 ✓」、未达标＝玫瑰「待达成」话术。 */
function TermBadge({
  met,
  metText,
  unmetText,
}: {
  met: boolean;
  metText: string;
  unmetText: string;
}) {
  return (
    <span
      className={cn(
        "inline-block rounded border px-1.5 py-0.5 text-label-sm font-semibold",
        met
          ? "border-emerald-active/30 bg-emerald-active/10 text-success-deep"
          : "border-error/25 bg-error/8 text-error",
      )}
    >
      {met ? metText : unmetText}
    </span>
  );
}
