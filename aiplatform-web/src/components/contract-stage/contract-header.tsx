"use client";

import { Check, ShieldCheck } from "lucide-react";

import { Skeleton } from "@/components/ui/skeleton";
import { cn } from "@/lib/utils";
import type { ContractStage, SubstepState } from "@/lib/orders/contract-stage";

/**
 * 签约付款页阶段头卡 + 三子步进度条（#348，spec #320 ⑥ 照原型 ai_2）：STAGE
 * P06/P07 徽标（装饰协议文案逐字照原型）＋标题「合同签署与开工款承接校验」＋
 * 企业级双重准入保障胶囊＋三子步（核对范围与报价/签署电子合同/开工款支付与
 * 核验）。子步态（pending/current/done）由 {@link contractStageOf} 推导后传入，
 * 这里只呈现不判定——Step 03 的「核验中/待支付/已到账」pill 与子步态、核验标志
 * 联动（verifying 来自付款节点待核验事实）。
 */
export function ContractHeader({
  projectName,
  stage,
  loading = false,
}: {
  projectName: string;
  stage: ContractStage;
  loading?: boolean;
}) {
  return (
    <section
      aria-label="签约付款阶段"
      className="flex flex-col gap-4 rounded-2xl border border-slate-soft/80 bg-white p-5 shadow-e1"
    >
      <div className="flex flex-wrap items-center justify-between gap-3">
        <span className="inline-flex items-center gap-1.5 rounded-md border border-tech-blue/25 bg-surface-mist px-2.5 py-1 font-mono text-code-sm font-semibold tracking-wider text-tech-blue uppercase">
          <ShieldCheck className="size-3.5" aria-hidden="true" />
          STAGE P06 / P07 · SG-DELIVERY-PROTOCOL-STRICT
        </span>
        <span className="inline-flex items-center gap-2 rounded-full bg-surface-mist/70 px-3 py-1.5 text-label-sm text-ink-muted">
          <span className="relative flex size-2">
            <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-emerald-active opacity-60" />
            <span className="relative inline-flex size-2 rounded-full bg-emerald-active" />
          </span>
          清算节点自动轮询中 (10s)
        </span>
      </div>

      <div className="flex flex-col gap-1">
        {loading ? (
          <Skeleton className="h-7 w-72" />
        ) : (
          <h1 className="flex items-center gap-2.5 text-[26px] font-bold tracking-tight text-ink-primary">
            合同签署与开工款承接校验
            <span className="rounded-full border border-tech-blue/25 bg-surface-mist px-2.5 py-0.5 text-label-sm font-medium text-tech-blue">
              企业级双重准入保障
            </span>
          </h1>
        )}
        <p className="text-body-md text-ink-muted">
          商业协议已双向存证锚定，通过双重门禁阻断机制保障交付工程准入，资金与权益全流程合规受护
        </p>
        {!loading && projectName ? (
          <p className="mt-0.5 font-mono text-code-sm text-ink-faint">
            项目：<span className="text-ink-secondary">{projectName}</span>
          </p>
        ) : null}
      </div>

      <div className="grid grid-cols-1 gap-3 pt-1 md:grid-cols-3">
        <SubstepCard
          seq="01"
          state={stage.scope}
          stateLabel={stage.scope === "done" ? "商业基线已冻结" : stage.scope === "current" ? "当前阶段" : "待开始"}
          name="核对范围与报价"
        />
        <SubstepCard
          seq="02"
          state={stage.contract}
          stateLabel={stage.contract === "done" ? "双方签署完毕" : stage.contract === "current" ? "当前阶段" : "待开始"}
          name="签署合同"
        />
        <SubstepCard
          seq="03"
          state={stage.payment}
          stateLabel={stage.payment === "done" ? "开工款已核验到账" : stage.payment === "current" ? "当前阶段" : "待开始"}
          name="开工款支付与核验"
          pill={
            stage.payment === "done"
              ? "已到账"
              : stage.payment === "current"
                ? stage.verifying
                  ? "核验中"
                  : "待支付"
                : undefined
          }
        />
      </div>
    </section>
  );
}

/** 单个子步卡：done＝薄荷绿打勾、current＝电靛高亮（Step 03 可带 pill）、pending＝灰置。 */
function SubstepCard({
  seq,
  state,
  stateLabel,
  name,
  pill,
}: {
  seq: string;
  state: SubstepState;
  stateLabel: string;
  name: string;
  pill?: string;
}) {
  const done = state === "done";
  const current = state === "current";
  return (
    <div
      className={cn(
        "flex items-center gap-3 rounded-xl border p-3.5",
        done && "border-emerald-active/25 bg-linear-to-r from-emerald-active/8 to-white",
        current && "border-tech-blue/30 bg-linear-to-r from-surface-mist to-surface-violet-tint/40",
        !done && !current && "border-slate-soft/70 bg-surface-dawn/40",
      )}
    >
      <div
        className={cn(
          "flex size-8 shrink-0 items-center justify-center rounded-lg font-mono text-sm font-bold",
          done && "bg-emerald-active text-white",
          current && "bg-linear-to-tr from-electric-indigo to-tech-blue text-white",
          !done && !current && "bg-surface-dawn text-ink-faint",
        )}
      >
        {done ? <Check className="size-4.5" aria-hidden="true" /> : seq}
      </div>
      <div className="flex min-w-0 flex-1 flex-col">
        <span
          className={cn(
            "text-label-sm font-semibold tracking-wide uppercase",
            done && "text-success-deep",
            current && "text-electric-indigo",
            !done && !current && "text-ink-faint",
          )}
        >
          Step {seq} · {stateLabel}
        </span>
        <span className="truncate text-headline-sm font-bold text-ink-primary">
          {name}
          {done ? <span className="ml-1 font-normal text-emerald-active">✓</span> : null}
        </span>
      </div>
      {pill ? (
        <span className="shrink-0 rounded-md bg-tech-blue px-2.5 py-1 text-label-sm font-semibold text-white">
          {pill}
        </span>
      ) : null}
    </div>
  );
}
