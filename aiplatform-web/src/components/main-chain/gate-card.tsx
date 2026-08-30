"use client";

import { Check, Undo2 } from "lucide-react";
import { useState, type FormEvent } from "react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import { Textarea } from "@/components/ui/textarea";
import { errorText } from "@/lib/api/api-error";
import type { GateView } from "@/lib/main-chain/project";
import { useApproveStage, useRejectStage } from "@/hooks/use-project";
import { cn } from "@/lib/utils";

/**
 * 决策门卡（共享层资产，spec 0001 §5 / issue #19）：`gate` 非 null 才渲染。
 * 通过 = 无确认体直接 POST；驳回 = 展开必填 reason 表单（空值不可提交）。
 * 挂卡点就绪才挂（`isGateReady` 收口，issue #58）：卡出现即处于可操作态，
 * 锁定分支已删；409 等错误直出后端 message（门禁不足 / 当前阶段无确认门 /
 * 存在未关闭 Bug）。
 * 文案口径归场景：默认 = 开发平台口径，需求端场景（spec 0002 §5 禁词）以
 * `copy` 覆盖（如「意见已原样转给顾问」）。
 */

export type GateCardCopy = {
  /** 卡头动作提示（CardDescription 前缀）。 */
  title: string;
  /** 卡标题主文案：开发平台「决策门」，需求端该门等待语（spec 0002 §5 禁词）。 */
  heading: string;
  /** 是否显示拍板方（actor）徽章；需求端隐藏技术称谓（默认 true）。 */
  showActor?: boolean;
  approveLabel: string;
  rejectToggleLabel: string;
  rejectPlaceholder: string;
  rejectSubmitLabel: string;
  cancelLabel: string;
  approvedToast: string;
  rejectedToast: string;
  /** 底部提示行。 */
  footer: string;
};

const DEFAULT_COPY: GateCardCopy = {
  title: "等你拍板",
  heading: "决策门",
  showActor: true,
  approveLabel: "通过",
  rejectToggleLabel: "驳回",
  rejectPlaceholder: "驳回意见（必填），将原样转给智能体",
  rejectSubmitLabel: "提交驳回",
  cancelLabel: "取消",
  approvedToast: "已通过",
  rejectedToast: "意见已原样转给智能体",
  footer: "通过即推进；驳回会停留在当前环节，可修改后再次拍板。",
};

export function GateCard({
  projectId,
  stageLabel,
  gate,
  copy,
  className,
}: {
  projectId: string;
  /** 当前段展示名（卡头上下文）。 */
  stageLabel: string;
  /** 详情 `gate`；null = 当前段无确认门，整卡不渲染。 */
  gate: GateView | null;
  copy?: Partial<GateCardCopy>;
  className?: string;
}) {
  const text = { ...DEFAULT_COPY, ...copy };
  const [rejectOpen, setRejectOpen] = useState(false);
  const [reason, setReason] = useState("");
  const approve = useApproveStage(projectId);
  const reject = useRejectStage(projectId);

  if (gate === null) return null;

  const trimmed = reason.trim();
  const busy = approve.isPending || reject.isPending;

  const onApprove = () => {
    approve.mutate(undefined, {
      onSuccess: () => {
        setRejectOpen(false);
        toast.success(text.approvedToast);
      },
      onError: (error) => toast.error(errorText(error)),
    });
  };

  const onReject = (event: FormEvent) => {
    event.preventDefault();
    if (!trimmed) return;
    reject.mutate(
      // requirementChange 缺省 false（纯意见不惊动 BA）；需求变更标记 UI 随回流票落
      { reason: trimmed, requirementChange: false },
      {
        onSuccess: () => {
          setReason("");
          setRejectOpen(false);
          toast.success(text.rejectedToast);
        },
        onError: (error) => toast.error(errorText(error)),
      },
    );
  };

  return (
    <Card className={cn("border-primary/40 gap-3 ring-1 ring-primary/10", className)}>
      <CardHeader className="gap-1">
        <CardDescription className="font-medium text-primary">
          {text.title} · {stageLabel}
        </CardDescription>
        <CardTitle className="flex items-center gap-2 text-base">
          {text.heading}
          {text.showActor !== false && gate.actor && (
            <Badge variant="secondary" className="h-5 text-[10px]">{gate.actor}</Badge>
          )}
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        {!rejectOpen ? (
          <div className="flex flex-wrap items-center gap-2">
            <Button onClick={onApprove} disabled={busy}>
              <Check /> {text.approveLabel}
            </Button>
            <Button
              variant="destructive"
              onClick={() => setRejectOpen(true)}
              disabled={busy}
            >
              <Undo2 /> {text.rejectToggleLabel}
            </Button>
          </div>
        ) : (
          <form className="space-y-2" onSubmit={onReject}>
            <Textarea
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder={text.rejectPlaceholder}
              rows={3}
              autoFocus
              disabled={busy}
            />
            <div className="flex items-center gap-2">
              <Button type="submit" variant="destructive" disabled={!trimmed || busy}>
                {text.rejectSubmitLabel}
              </Button>
              <Button
                type="button"
                variant="ghost"
                onClick={() => {
                  setRejectOpen(false);
                  setReason("");
                }}
                disabled={busy}
              >
                {text.cancelLabel}
              </Button>
            </div>
          </form>
        )}

        <Separator />
        <p className="text-xs text-muted-foreground">{text.footer}</p>
      </CardContent>
    </Card>
  );
}
