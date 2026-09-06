"use client";

import { Rocket } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { useGenerate } from "@/hooks/use-generate";

/**
 * 重新发起（生成失败兜底，#101 生成无门自动发起后「开始做系统」按钮退役）：系统
 * 面板失败态的人工兜底入口——run-failed 后首次生成时点未落位、项目仍「未生成」，
 * 重发 POST /generate 再触发（异常态，非常驻门，与「重新修改」兜底同构）。生成
 * 正常流全自动，不出现任何常驻按钮。发起成功即回调 onGenerated（场景层切成果区
 * 系统模式呈现等待态）。
 */
export function StartSystemButton({
  projectId,
  onGenerated,
  label = "重新发起",
}: {
  projectId: string;
  onGenerated: () => void;
  label?: string;
}) {
  const generate = useGenerate(projectId);
  return (
    <Button
      size="sm"
      disabled={generate.isPending}
      onClick={() => generate.mutate(undefined, { onSuccess: onGenerated })}
    >
      {generate.isPending ? <Spinner /> : <Rocket className="size-4" />}
      {label}
    </Button>
  );
}
