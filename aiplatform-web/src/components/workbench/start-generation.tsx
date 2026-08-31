"use client";

import { Rocket } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { useGenerate } from "@/hooks/use-generate";

/**
 * 开始做系统（#22）：同一动作的两个呈现形态——对话流内卡片（解释性入口）
 * 与紧凑按钮（文件模式操作条 / 系统模式失败重发）。纯动作无门：待定项未清也可
 * 点；eligibility（PRD 已产出 && 未生成 && 不在生成中）归装配层（WorkbenchView）
 * 单点判定，本组件只管动作与进行中态。发起成功即回调 onGenerated（场景层切
 * 成果区系统模式，mobile 跳成果区页签）。
 */
export function StartGenerationCard({
  projectId,
  eligible,
  onGenerated,
}: {
  projectId: string;
  eligible: boolean;
  /** 发起成功回调（切系统模式呈现等待态）。 */
  onGenerated: () => void;
}) {
  const generate = useGenerate(projectId);
  if (!eligible) return null;

  return (
    <div className="flex w-full justify-start">
      <div className="w-full max-w-sm space-y-3 rounded-xl border bg-muted/40 p-4">
        <div className="flex items-center gap-2 text-sm font-medium">
          <Rocket className="size-4 shrink-0 text-primary" />
          需求整理好了，可以开始做系统
        </div>
        <p className="text-xs leading-relaxed text-muted-foreground">
          接下来平台会把你的系统做出来，做好后可以直接在这里操作试用。还有没聊清的事项
          也不影响先做，随时可以再提。
        </p>
        <StartButtonInner
          pending={generate.isPending}
          onClick={() => generate.mutate(undefined, { onSuccess: onGenerated })}
        />
      </div>
    </div>
  );
}

/** 紧凑形态（文件模式操作条 / 系统模式失败重发共用）。 */
export function StartSystemButton({
  projectId,
  onGenerated,
  label = "开始做系统",
}: {
  projectId: string;
  onGenerated: () => void;
  label?: string;
}) {
  const generate = useGenerate(projectId);
  return (
    <StartButtonInner
      pending={generate.isPending}
      onClick={() => generate.mutate(undefined, { onSuccess: onGenerated })}
      label={label}
    />
  );
}

function StartButtonInner({
  pending,
  onClick,
  label = "开始做系统",
}: {
  pending: boolean;
  onClick: () => void;
  label?: string;
}) {
  return (
    <Button size="sm" disabled={pending} onClick={onClick}>
      {pending ? <Spinner /> : <Rocket className="size-4" />}
      {label}
    </Button>
  );
}
