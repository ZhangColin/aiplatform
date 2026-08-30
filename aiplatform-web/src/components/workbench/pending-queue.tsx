"use client";

import { Check, Inbox } from "lucide-react";
import { useState } from "react";

import { WaitCard } from "@/components/agent/wait-card";
import { GateCard } from "@/components/main-chain/gate-card";
import { Button } from "@/components/ui/button";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Skeleton } from "@/components/ui/skeleton";
import { useProjectJourney } from "@/hooks/use-project";
import { useProjectWaits } from "@/hooks/use-waits";
import { waitKindLabel } from "@/lib/agent/wait";
import { errorText } from "@/lib/api/api-error";
import { isGateReady } from "@/lib/main-chain/project";

/**
 * 待处理模式（issue #44，spec 0001 §4.3）：HITL 等待 + 门拍板聚合为决策队列大卡
 * 纵排。处理后收为「已处理」一行（会话内 settle 轨迹，等待点本身经 re-fetch 离
 * 队）；空态 =「一切自动运行中」。徽章计数由 Agent 区（对话/直播/待处理 tab）按
 * 「待处理等待点数 + 门就绪」聚合，本组件只渲染队列。
 */
export function PendingQueue({ projectId }: { projectId: string }) {
  const waits = useProjectWaits(projectId);
  const { data: detail } = useProjectJourney(projectId);
  const [settled, setSettled] = useState<Array<{ waitId: string; label: string }>>([]);

  const settledIds = new Set(settled.map((s) => s.waitId));
  const pending = (waits.data ?? []).filter((w) => !settledIds.has(w.waitId));
  const gate = detail?.gate ?? null;

  const markSettled = (waitId: string) => {
    setSettled((prev) => {
      if (prev.some((s) => s.waitId === waitId)) return prev;
      const wait = (waits.data ?? []).find((w) => w.waitId === waitId);
      return [...prev, { waitId, label: wait ? waitKindLabel(wait.kind) : "等待" }];
    });
  };

  if (waits.isPending) {
    return (
      <div className="space-y-3 p-4">
        <Skeleton className="h-24 w-full" />
        <Skeleton className="h-16 w-full" />
      </div>
    );
  }

  if (waits.isError) {
    return (
      <div className="flex flex-col items-center gap-3 py-16 text-sm text-muted-foreground">
        <p>{errorText(waits.error, "待处理列表加载失败")}</p>
        <Button variant="outline" size="sm" onClick={() => void waits.refetch()}>
          重试
        </Button>
      </div>
    );
  }

  // 就绪才挂（#58 收口）：门未就绪不入队，空态判定同源
  const empty = pending.length === 0 && settled.length === 0 && !isGateReady(gate);

  return (
    <ScrollArea className="h-full">
      <div className="space-y-3 p-4 pb-6">
        {empty ? (
          <EmptyQueue />
        ) : (
          <>
            {pending.map((wait) => (
              <WaitCard
                key={wait.waitId}
                projectId={projectId}
                wait={wait}
                onSettled={markSettled}
              />
            ))}
            {settled.map((item) => (
              <SettledRow key={item.waitId} label={item.label} />
            ))}
            {isGateReady(gate) && (
              <GateCard projectId={projectId} stageLabel={detail?.stageLabel ?? ""} gate={gate} />
            )}
          </>
        )}
      </div>
    </ScrollArea>
  );
}

function SettledRow({ label }: { label: string }) {
  return (
    <div className="flex items-center gap-2 rounded-lg border border-dashed px-4 py-3 text-sm text-muted-foreground">
      <Check className="size-4 text-emerald-600" />
      {label}已处理
    </div>
  );
}

function EmptyQueue() {
  return (
    <div className="rounded-lg border border-dashed py-16 text-center">
      <Inbox className="mx-auto size-8 text-muted-foreground/50" />
      <p className="mt-3 text-sm font-medium">一切自动运行中</p>
      <p className="mx-auto mt-1 max-w-sm text-xs text-muted-foreground">
        智能体执行中，无需你处理；对话 / 直播模式可围观执行过程
      </p>
    </div>
  );
}
