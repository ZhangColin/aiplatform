"use client";

import { RotateCcw } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { useRestartFix } from "@/hooks/use-restart-fix";

/**
 * 重新修改（#48 修正 run 超限终态的人工兜底入口）：与「重新发起」对齐的紧凑
 * 按钮形态，系统面板的占位终态与页面失败轻提示两处共用。可点性归服务端终态账
 * 判定（本组件不做前置 gating——只在终态档渲染）；发起成功即乐观登记编码 run
 * 在途，面板自动回到进行中档。
 */
export function RestartFixButton({ projectId }: { projectId: string }) {
  const restartFix = useRestartFix(projectId);
  return (
    <Button
      size="sm"
      variant="outline"
      disabled={restartFix.isPending}
      onClick={() => restartFix.mutate()}
    >
      {restartFix.isPending ? <Spinner /> : <RotateCcw className="size-4" />}
      重新修改
    </Button>
  );
}
