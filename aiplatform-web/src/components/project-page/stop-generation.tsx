"use client";

import { Square } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { useStopGeneration } from "@/hooks/use-stop-generation";

/**
 * 停止在途生成/更新（#359 生成取消）：生成中细条与 Demo 等待态的停止入口
 * （入口不藏——与进行中轻状态同排呈现）。停止＝中断口径：已完成的进度都保留，
 * 终态收口后失败档出「已停止生成/已停止更新」＋既有恢复出口（继续生成/继续更新）。
 * 幂等点击无害（服务端无在途即 stopped=false 如实回）；pending 期禁用防连点。
 */
export function StopGenerationButton({ projectId }: { projectId: string }) {
  const stop = useStopGeneration(projectId);
  return (
    <Button
      size="sm"
      variant="outline"
      disabled={stop.isPending}
      onClick={() => stop.mutate()}
    >
      {stop.isPending ? <Spinner /> : <Square className="size-3.5" />}
      停止
    </Button>
  );
}
