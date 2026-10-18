"use client";

import { CircleCheck, Hourglass, Info, Rocket } from "lucide-react";
import Link from "next/link";

import { cn } from "@/lib/utils";
import type { ContractStage } from "@/lib/orders/contract-stage";
import type { ProductionHandoffView } from "@/lib/orders/production";

/**
 * 生产承接工作站（#348 门禁 checklist 呈现，spec #320 ⑥ 照原型）：开工门禁就绪
 * 指标 CHECKLIST＝黄金门禁公式的逐项拆解——①范围锁定（合同进场）②双方签署完成
 * ③开工款核验到账。状态与门禁态（{@link contractStageOf}）联动，这里只呈现不
 * 判定。「进入生产看板 →」在门禁放行（GATE PERMITTED）后才可点，直达生产承接页
 * （P07 归 #349，本页只留入口）。生产就绪状态只呈现真实承接字段（不虚构排期）。
 */
export function ProductionTrackCard({
  projectId,
  stage,
  handoff,
  stateName,
}: {
  projectId: string;
  stage: ContractStage;
  handoff?: ProductionHandoffView | null;
  /** 交付状态名（生产就绪状态的用户话术；缺省时按门禁态兜底）。 */
  stateName?: string;
}) {
  const permitted = stage.gate === "permitted";
  // 范围锁定＝子步一 done（合同进场）——不另行重判，消费 contractStageOf 的推导结果。
  const frozen = stage.scope === "done";
  const readiness = handoff?.nextStep?.trim() || stateName || (permitted ? "门禁校验通过，研发组织协同准备中" : "待款项核验后自动建仓排期");

  return (
    <section className="relative flex flex-col gap-4 overflow-hidden rounded-2xl border border-slate-soft/80 bg-linear-to-br from-white via-canvas-base to-surface-mist p-5 shadow-e1">
      <div className="absolute inset-x-0 top-0 h-1 bg-linear-to-r from-electric-indigo via-tech-blue to-emerald-active" aria-hidden="true" />

      <div className="flex items-center justify-between pt-1">
        <div className="flex items-center gap-2.5">
          <div className="flex size-8 items-center justify-center rounded-lg bg-surface-violet-tint text-electric-indigo">
            <Rocket className="size-4.5" aria-hidden="true" />
          </div>
          <h2 className="text-headline-md font-bold text-ink-primary">生产承接工作站 (PRODUCTION TRACK)</h2>
        </div>
        <span
          className={cn(
            "rounded-full border px-3 py-1 font-mono text-code-sm font-semibold tracking-wider",
            permitted
              ? "border-emerald-active/30 bg-emerald-active/10 text-success-deep"
              : "border-slate-soft bg-surface-dawn text-ink-muted",
          )}
        >
          {permitted ? "READINESS APPROVED" : "PENDING CLEARANCE"}
        </span>
      </div>

      <div className="flex items-center justify-between rounded-xl border border-subtle-blue/70 bg-white px-4 py-3 shadow-xs">
        <span className="text-label-sm text-ink-faint uppercase font-semibold">生产就绪状态</span>
        <span className="text-body-md font-bold text-ink-secondary">{readiness}</span>
      </div>

      <div className="flex flex-col gap-2">
        <span className="text-label-sm font-semibold tracking-wider text-ink-faint uppercase">
          开工门禁就绪指标 CHECKLIST
        </span>
        <ChecklistRow
          met={frozen}
          metText="已满足 ✓"
          unmetText="待确认 ⏱️"
          label="PRD 范围锁定与架构评估基线完成"
        />
        <ChecklistRow
          met={stage.contractSigned}
          metText="已满足 ✓"
          unmetText="待确认 ⏱️"
          label="双方数字电子签署与存证书链锁定"
        />
        <ChecklistRow
          met={stage.kickoffSettled}
          metText="已满足 ✓"
          unmetText="待确认 ⏱️"
          label="首期开工款账户核验到账"
        />
      </div>

      <div className="flex flex-col gap-3 border-t border-slate-soft/70 pt-3 md:flex-row md:items-center md:justify-between">
        <p className="flex items-center gap-1.5 text-body-sm text-ink-faint">
          <Info className="size-4 shrink-0 text-tech-blue" aria-hidden="true" />
          触发开工后系统将自动下发 GitHub 研发组织协同邀请与每日交付看板权限。
        </p>
        {permitted ? (
          <Link
            href={`/projects/${projectId}/production`}
            className="inline-flex shrink-0 items-center justify-center gap-2 rounded-xl bg-linear-to-r from-electric-indigo via-tech-blue to-tech-blue px-6 py-2.5 text-label-md font-bold text-white shadow-md transition-all hover:brightness-105 active:scale-[0.99]"
          >
            进入生产看板 →
          </Link>
        ) : (
          <span className="inline-flex shrink-0 cursor-not-allowed items-center justify-center gap-2 rounded-xl border border-slate-soft bg-surface-dawn px-6 py-2.5 text-label-md font-semibold text-ink-faint">
            进入生产看板 →
          </span>
        )}
      </div>
    </section>
  );
}

function ChecklistRow({
  met,
  label,
  metText,
  unmetText,
}: {
  met: boolean;
  label: string;
  metText: string;
  unmetText: string;
}) {
  return (
    <div className="flex items-center justify-between rounded-xl border border-slate-soft/70 bg-white p-3.5 shadow-xs">
      <div className="flex items-center gap-2.5">
        {met ? (
          <CircleCheck className="size-5 text-emerald-active" aria-hidden="true" />
        ) : (
          <Hourglass className="size-5 text-error" aria-hidden="true" />
        )}
        <span className="text-body-md font-medium text-ink-secondary">{label}</span>
      </div>
      <span
        className={cn(
          "rounded-md border px-2.5 py-0.5 text-label-sm font-bold",
          met
            ? "border-emerald-active/25 bg-emerald-active/10 text-success-deep"
            : "border-error/25 bg-error/8 text-error",
        )}
      >
        {met ? metText : unmetText}
      </span>
    </div>
  );
}
