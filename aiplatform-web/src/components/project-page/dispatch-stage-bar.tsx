"use client";

import { CheckCircle2, CircleX, Clock, LoaderCircle } from "lucide-react";

import { dispatchBarView, type DispatchBarTone } from "@/lib/chat/dispatch-stage";
import { useDispatchStageStore } from "@/lib/store/dispatch-stage";
import { cn } from "@/lib/utils";

/** tone → 图标（active 转圈 / waiting 时钟 / settled 对勾 / failed 叉号）。 */
const TONE_ICONS: Record<DispatchBarTone, typeof LoaderCircle> = {
  active: LoaderCircle,
  waiting: Clock,
  settled: CheckCircle2,
  failed: CircleX,
};

/**
 * 派发阶段状态条（#50 阶段状态条）：指令区输入条上方的阶段呈现——意见 / 咨询
 * 全过程按 dispatch-stage 帧驱动（项目内最新帧即当前阶段），不署智能体名（分派
 * 对用户隐式）。「正在分析您的意见…」→「正在更新 PRD…」→「正在修改系统…」（细节
 * 接直播侧栏）→「已按您的意见修改了系统 / 本轮意见未改动系统」。无状态（链未
 * 开口或发送重置后）不渲染；派发失败落「派发失败，请重提您的意见」（#51）；
 * 其余失败链停在事发阶段（error 帧另行呈现）。
 */
export function DispatchStageBar({ projectId }: { projectId: string }) {
  const state = useDispatchStageStore((s) => s.stages[projectId]);
  const view = dispatchBarView(state);
  if (!view) return null;

  const Icon = TONE_ICONS[view.tone];
  return (
    <div
      className={cn(
        "mb-2 flex items-center justify-center gap-2 rounded-lg border bg-muted/40 px-3 py-1.5 text-xs text-muted-foreground",
        view.tone === "failed" && "border-destructive/40 text-destructive",
      )}
      data-testid="dispatch-stage-bar"
    >
      <Icon
        className={cn("size-3.5 shrink-0", view.tone === "active" && "animate-spin")}
        aria-hidden
      />
      <span>{view.text}</span>
    </div>
  );
}
