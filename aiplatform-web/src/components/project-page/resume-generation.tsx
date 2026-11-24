"use client";

import { PlayCircle } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { useResumeGeneration } from "@/hooks/use-resume-generation";

/**
 * 继续生成（#222 单出口，ADR-0020——「重新发起」连概念带词条删）：会话内 run 失败/
 * 从未生成的恢复入口，重发 POST /generate 即在既有工作区重跑（#604 失败新语义＝
 * 明确展示＋重跑即恢复：服务端续跑机器已退役，不带断点、不携错误现场）；会话内
 * 失败态与 idle 态都挂本键，无推倒重来按钮（重走对话区提意见改
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
