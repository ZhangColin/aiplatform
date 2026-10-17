"use client";

import { Check } from "lucide-react";
import { useState } from "react";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Spinner } from "@/components/ui/spinner";
import { useConfirmPrd } from "@/hooks/use-confirm-prd";
import type { components } from "@/lib/api/schema";

type PrdReadiness = components["schemas"]["PrdReadinessResponse"];
type PendingItem = components["schemas"]["PendingItem"];

/**
 * 确认门（#330 确认即成版 ＋ #332 生成加门）：PRD 面板主 CTA——「确认此版 PRD 并
 * 生成 Demo」是首个构建 run 的唯一触发（旧「PRD 出即自动起」已退役）。门机制与
 * 呈现同票落地：不留「门在机制上关了、呈现上没有开门按钮」的中间态（spec #320
 * 段间耦合红线）——只要 PRD 在场就出按钮。
 *
 * <p><b>两段式摘要层</b>：点按钮开摘要层（第一段）——范围摘要（维度就绪 n/5）＋
 * 未解决事项（readiness.minor）＋阻碍级缺项（readiness.blocking）；层内「继续」
 * 进第二段（层内确认）——一段最终确认动作（「确认并生成 Demo」）才真正 POST。
 * 阻碍级缺项在场＝用户不可绕过（须先在对话区补齐），第二段动作禁用、就地指路。</p>
 */
export function PrdConfirmGate({
  projectId,
  readiness,
  changeSummary,
}: {
  projectId: string;
  readiness?: PrdReadiness;
  /** 差异摘要（#334 改需求回路）：范围级新草稿停驻待确认的 markdown 变更说明——
   * 摘要层「本版更新内容」节（null = 无新草稿待确认，节不渲染）。 */
  changeSummary?: string | null;
}) {
  const confirm = useConfirmPrd(projectId);
  const [open, setOpen] = useState(false);
  const [stage, setStage] = useState<"summary" | "confirm">("summary");

  const blocking = readiness?.blocking ?? [];
  const minor = readiness?.minor ?? [];
  const dimensions = readiness?.dimensions ?? [];
  const readyCount = dimensions.filter((dim) => dim.status === "ready").length;
  const blocked = blocking.length > 0;

  function openGate() {
    setStage("summary");
    setOpen(true);
  }

  function submit() {
    confirm.mutate(undefined, {
      onSuccess: () => {
        setOpen(false);
        setStage("summary");
      },
    });
  }

  return (
    <>
      <Button size="sm" onClick={openGate}>
        <Check className="size-4" />
        确认此版 PRD 并生成 Demo
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent>
          {stage === "summary" ? (
            <>
              <DialogHeader>
                <DialogTitle>确认本版需求，开始生成 Demo</DialogTitle>
                <DialogDescription>
                  确认后平台会把这版需求冻结成一个版本，并据它开始生成 Demo。
                </DialogDescription>
              </DialogHeader>
              <div className="space-y-4 text-sm">
                <section className="space-y-1">
                  <h3 className="font-medium">范围摘要</h3>
                  <p className="text-muted-foreground">
                    已梳理 {readyCount}/{dimensions.length || 5} 个维度。
                  </p>
                </section>
                {changeSummary ? (
                  // 本版更新内容（#334 改需求回路）：范围级新草稿的差异摘要——确认
                  // 前看到这版改了什么（改稿时产出的 markdown 变更说明，原样呈现）
                  <section className="space-y-1">
                    <h3 className="font-medium">本版更新内容</h3>
                    <p className="whitespace-pre-wrap text-muted-foreground">
                      {changeSummary}
                    </p>
                  </section>
                ) : null}
                {minor.length > 0 ? (
                  <section className="space-y-1">
                    <h3 className="font-medium">未解决事项（不阻碍生成）</h3>
                    <ul className="list-disc space-y-1 pl-5 text-muted-foreground">
                      {minor.map((item, index) => (
                        <li key={index}>{item.text}</li>
                      ))}
                    </ul>
                  </section>
                ) : null}
                {blocked ? (
                  <section className="space-y-1">
                    <h3 className="font-medium text-destructive">需先在对话区补齐</h3>
                    <ul className="list-disc space-y-1 pl-5 text-destructive">
                      {blocking.map((item: PendingItem, index: number) => (
                        <li key={index}>{item.text}</li>
                      ))}
                    </ul>
                  </section>
                ) : null}
              </div>
              <DialogFooter>
                <Button variant="outline" onClick={() => setOpen(false)}>
                  再看一眼
                </Button>
                <Button disabled={blocked} onClick={() => setStage("confirm")}>
                  继续
                </Button>
              </DialogFooter>
            </>
          ) : (
            <>
              <DialogHeader>
                <DialogTitle>确认并生成 Demo？</DialogTitle>
                <DialogDescription>
                  {changeSummary
                    ? "确认即冻结本版需求为新版本，平台随即按新版生成下一版 Demo。"
                    : "确认即冻结本版需求为不可变版本，平台随即开始生成首个 Demo。"}
                </DialogDescription>
              </DialogHeader>
              <DialogFooter>
                <Button variant="outline" onClick={() => setStage("summary")}>
                  返回
                </Button>
                <Button disabled={confirm.isPending} onClick={submit}>
                  {confirm.isPending ? <Spinner /> : <Check className="size-4" />}
                  确认并生成 Demo
                </Button>
              </DialogFooter>
            </>
          )}
        </DialogContent>
      </Dialog>
    </>
  );
}
