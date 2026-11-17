"use client";

import { CheckCircle2 } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { useBuildGate, useConfirmPrdGeneration } from "@/hooks/use-build-gate";

/**
 * 付费轨首建 PRD 确认门（#542，ADR-0049——#462 去确认门前的原动作形态回归）：
 * 付费态∧PRD 在∧首建未派发时的「确认 PRD，开始生成」块——确认即派发首个构建
 * （复用 startGeneration 共用编排）、点击幂等（已点火 409、失败重试走「继续
 * 生成」）、确认不写版本行（版本表构建基准序零改动）。呈现条件＝GET /build-gate
 * prdConfirmable 服务端单源；试用轨点火在岔路口三选卡，不经本门。
 *
 * <p>双挂载点共用本件（去重复——读面/动作/文案单源）：需求分析页 PrdPanel
 * （原型设计令牌，variant="panel"）与 Demo 页 PrdDoc（shadcn 令牌，
 * variant="doc"）；两视觉语言令牌不同，文案与行为契约同源。</p>
 */
export function PrdConfirmGate({
  projectId,
  variant,
}: {
  projectId: string;
  variant: "panel" | "doc";
}) {
  const gate = useBuildGate(projectId);
  const confirm = useConfirmPrdGeneration(projectId);
  if (gate.data?.prdConfirmable !== true) {
    return null;
  }
  if (variant === "panel") {
    return <PanelGate pending={confirm.isPending} onConfirm={() => confirm.mutate(undefined)} />;
  }
  return <DocGate pending={confirm.isPending} onConfirm={() => confirm.mutate(undefined)} />;
}

/** 需求分析版面（原型设计令牌——照原型三新件视觉语言）。 */
function PanelGate({ pending, onConfirm }: { pending: boolean; onConfirm: () => void }) {
  return (
    <div
      data-slot="prd-confirm-gate"
      className="sticky bottom-0 flex shrink-0 flex-col gap-2 border-t border-electric-indigo/25 bg-white/95 p-4 shadow-e1 backdrop-blur"
    >
      <span className="text-body-sm font-bold tracking-tight text-ink-primary">
        PRD 已就绪——确认后开始生成
      </span>
      <span className="text-label-sm text-ink-muted">
        确认即按这份 PRD 生成系统；生成过程中的用量从所选余额池扣减。
      </span>
      <button
        type="button"
        data-prd-confirm
        disabled={pending}
        onClick={onConfirm}
        className="self-start rounded-xl bg-electric-indigo px-4 py-2 text-label-sm font-bold text-white shadow-xs transition-colors hover:bg-electric-indigo/85 disabled:opacity-60"
      >
        {pending ? "确认中…" : "确认 PRD，开始生成"}
      </button>
    </div>
  );
}

/** Demo 页文档面（shadcn 令牌——随文档面，sticky 钉正文滚动区底）。 */
function DocGate({ pending, onConfirm }: { pending: boolean; onConfirm: () => void }) {
  return (
    <div
      data-slot="prd-confirm-gate"
      className="sticky bottom-0 flex flex-col gap-2 rounded-xl border border-primary/30 bg-background/95 p-4 shadow-e1 backdrop-blur"
    >
      <div className="flex items-start gap-2">
        <CheckCircle2 className="mt-0.5 size-4 shrink-0 text-primary" />
        <div className="flex min-w-0 flex-col gap-0.5">
          <span className="text-sm font-semibold">PRD 已就绪</span>
          <span className="text-xs text-muted-foreground">
            确认后平台按这份 PRD 开始生成系统；生成过程中的用量从所选余额池扣减。
          </span>
        </div>
      </div>
      <Button
        size="sm"
        className="self-start"
        data-prd-confirm
        disabled={pending}
        onClick={onConfirm}
      >
        {pending ? <Spinner className="size-3.5" /> : null}
        确认 PRD，开始生成
      </Button>
    </div>
  );
}
