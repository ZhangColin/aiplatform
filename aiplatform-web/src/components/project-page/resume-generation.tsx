"use client";

import { PlayCircle } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { useResumeGeneration } from "@/hooks/use-resume-generation";

/**
 * 继续生成（#222 单出口，ADR-0020——「重新发起」连概念带词条删）：生成中断/
 * 从未生成的恢复入口，重发 POST /generate 即断点续跑（#221：跳过已收口片、只重跑
 * 失败/中断片）或计划重派（#220：轨道表无计划——存量项目同路）；中断态（含刷新后）
 * 与 idle 态都挂本键（REST 四态投影派生），无推倒重来按钮（重走对话区提意见改
 * PRD）。发起成功即乐观登记编码 run 在途＋失效项目域（投影回生成中档），面板回到
 * 进行中呈现。
 */
export function ResumeGenerationButton({
  projectId,
  onGenerated,
}: {
  projectId: string;
  /** 发起成功回调（切系统范式呈现等待态），归装配层。 */
  onGenerated: () => void;
}) {
  const resume = useResumeGeneration(projectId);
  return (
    <Button
      size="sm"
      disabled={resume.isPending}
      onClick={() => resume.mutate(undefined, { onSuccess: onGenerated })}
    >
      {resume.isPending ? <Spinner /> : <PlayCircle className="size-4" />}
      继续生成
    </Button>
  );
}
