"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { MobileAreaSwitch } from "@/components/shell/mobile-area-switch";
import { CommandArea } from "@/components/project-page/command-area";
import { SystemPanel } from "@/components/project-page/system-panel";
import { useConfirmDemoVersion, useDemoVersions } from "@/hooks/use-demo-version";
import { usePlaceOrder } from "@/hooks/use-order";
import { usePrdVersions } from "@/hooks/use-prd";
import { useProject } from "@/hooks/use-project";
import { errorText } from "@/lib/api/api-error";
import { isBindingGateError } from "@/lib/orders/binding-gate";
import { confirmOrderVisible } from "@/lib/projects/confirm-order";
import { planAreaOfDesignItems } from "@/lib/projects/detail";
import { ENDPOINT_TYPE } from "@/lib/projects/endpoint";
import { lockRowOf } from "@/lib/orders/lock";
import { useAgentEventChannel } from "@/lib/sse/agent-event-channel";
import { useSseStatus } from "@/lib/sse/provider";
import { coderStatusOf, failureReasonOf, useGenerationStore } from "@/lib/store/generation";
import { useM01Store } from "@/lib/store/m01";
import { useVersionRecordStore } from "@/lib/store/version-record";
import { cn } from "@/lib/utils";

import { DeliveryBaselineCard } from "./delivery-baseline-card";
import { DemoConfirmCard } from "./demo-confirm-card";
import { DemoFilesDrawer } from "./demo-files-drawer";
import { DemoHeader, type DemoHeaderStatus } from "./demo-header";

/** Demo 体验页 P04/P05（#347）：预览/同页等待在左 8 列，基准、对话与确认在右 4 列。
 *  「确认此版本，准备签约」＝下单入口（#350 顶栏旧「确认下单」退役并入）：打版本
 *  确认标记 → 建单（未绑定访客被 ORD_017/5017 拦→弹 M01 携续单回调）→ 跳签约页。 */
export function DemoExperiencePage({ projectId }: { projectId: string }) {
  const router = useRouter();
  const { data: detail, isPending, isError, error, refetch } = useProject(projectId);
  const { data: demoVersions } = useDemoVersions(projectId);
  const { data: prdVersions } = usePrdVersions(projectId);
  const confirmDemoVersion = useConfirmDemoVersion(projectId);
  const placeOrder = usePlaceOrder(projectId);
  const openM01 = useM01Store((s) => s.openM01);
  const openVersionRecord = useVersionRecordStore((s) => s.openVersionRecord);
  const [filesOpen, setFilesOpen] = useState(false);

  useAgentEventChannel(projectId);
  const agentStatus = useSseStatus("agent");
  const coderStatus = useGenerationStore((s) => coderStatusOf(s, projectId));
  const failureReason = useGenerationStore((s) => failureReasonOf(s, projectId));

  // 移动单区域切换（#350，交接包 13.2）：窄屏（<xl）8:4 双栏退化为「预览/对话」
  // 单区域——缺省预览（Demo 即主角；生成中等待态同位）；确认动作常达＝粘性横幅
  // 主按钮（右栏确认卡为说明性副位，随对话区同栏）。（hooks 序：须在 isError 早退前）
  const [mobileArea, setMobileArea] = useState<"preview" | "chat">("preview");

  const wasConnected = useRef(false);
  useEffect(() => {
    if (agentStatus === "connected") {
      wasConnected.current = true;
      return;
    }
    if (agentStatus !== "offline" || !wasConnected.current) return;
    const timer = setTimeout(() => {
      toast.warning("过程事件连接已断开，正在自动重连", { id: "agent-channel-offline" });
    }, 10_000);
    return () => clearTimeout(timer);
  }, [agentStatus]);

  if (isError) {
    return (
      <div className="flex min-h-[calc(100svh-4rem)] flex-col items-center justify-center gap-3 text-body-md text-ink-faint">
        <p>{errorText(error, "项目加载失败")}</p>
        <div className="flex gap-2">
          <Button variant="outline" size="sm" onClick={() => void refetch()}>
            重试
          </Button>
          <Button variant="ghost" size="sm" nativeButton={false} render={<Link href="/projects" />}>
            返回项目列表
          </Button>
        </div>
      </div>
    );
  }

  const versions = demoVersions ?? [];
  const latest = versions[0];
  const generationState = detail?.generationState;
  const generating = generationState === "generating" || coderStatus === "running";
  const failed = generationState === "interrupted" || coderStatus === "error";
  const status: DemoHeaderStatus = failed
    ? "interrupted"
    : generating
      ? "generating"
      : generationState === "generated"
        ? "ready"
        : "waiting";
  const confirmed = latest?.confirmedAt != null;
  const confirmVisible = confirmOrderVisible({
    generatedAt: detail?.generatedAt,
    archived: detail?.archived,
    activeOrderId: detail?.activeOrder?.id ?? null,
  });
  const confirmDisabled = !latest || latest.versionNo == null || !confirmVisible || confirmed;

  /** 建单（确认门并入的下单动作）：成功跳签约页；签约门拦下弹 M01 携续单回调
   * （绑定后放行——续跑只重建单，版本确认标记已落不重复打）。 */
  const placeOrderThenGo = () =>
    placeOrder.mutate(undefined, {
      onSuccess: () => router.push(`/projects/${projectId}/contract`),
      onError: (orderError) => {
        if (isBindingGateError(orderError)) openM01("stage-gate", placeOrderThenGo);
      },
    });

  const onConfirm = () => {
    if (!latest || latest.versionNo == null || confirmDisabled) return;
    confirmDemoVersion.mutate(latest.versionNo, {
      // ORD_019 前置序：确认标记落地才建单（无 Demo 确认版本不可下单）
      onSuccess: placeOrderThenGo,
    });
  };

  // 已确认未建单＝重试位（确认后建单失败/存量已确认未下单项目——确认门是全库
  // 唯一建单入口，按钮锁死「已确认」即死路：横幅与确认卡转「重新提交订单」
  // 直建单，不重复打确认标记）。confirmVisible 含「无未终结订单」判定
  const orderMissing = confirmed && confirmVisible;
  const lock = lockRowOf({ archived: detail?.archived, activeOrder: detail?.activeOrder });

  return (
    <div className="min-h-[calc(100svh-4rem)]">
      <DemoHeader
        projectId={projectId}
        projectName={detail?.name ?? ""}
        status={status}
        loading={isPending}
        confirmDisabled={orderMissing ? false : confirmDisabled}
        confirmPending={orderMissing ? placeOrder.isPending : confirmDemoVersion.isPending}
        confirmLabel={orderMissing ? "重新提交订单" : undefined}
        onConfirm={orderMissing ? placeOrderThenGo : onConfirm}
        onStageSave={openVersionRecord}
      />

      <div className="flex flex-col gap-4 p-4 lg:px-8 lg:py-6 xl:grid xl:grid-cols-12 xl:items-start xl:gap-5">
        <div className="shrink-0 xl:hidden">
          <MobileAreaSwitch
            areas={[
              { key: "preview", label: "预览" },
              { key: "chat", label: "对话" },
            ]}
            value={mobileArea}
            onChange={setMobileArea}
          />
        </div>
        <section
          data-area="preview"
          aria-label="Demo 沙箱与视口"
          className={cn(
            "h-[70svh] min-h-0 xl:col-span-8 xl:block xl:h-[44rem]",
            mobileArea === "preview" ? "block" : "hidden",
          )}
        >
          {isPending ? (
            <Skeleton className="h-full w-full rounded-2xl" />
          ) : (
            <SystemPanel
              projectId={projectId}
              generationState={generationState}
              coderStatus={coderStatus}
              failureReason={failureReason}
              onGenerated={() => {}}
              onOpenFiles={() => setFilesOpen(true)}
              variant="demo"
            />
          )}
        </section>

        <aside
          data-area="chat"
          aria-label="Demo 反馈与确认"
          className={cn(
            "flex-col gap-4 xl:col-span-4 xl:flex",
            mobileArea === "chat" ? "flex" : "hidden",
          )}
        >
          {isPending ? (
            <Skeleton className="h-72 w-full rounded-2xl" />
          ) : (
            <DeliveryBaselineCard
              segments={detail?.segments}
              versions={versions}
              prdVersionNo={latest ? latest.prdVersionNo : prdVersions?.[0]?.versionNo}
              generating={generating}
              failed={failed}
            />
          )}

          <section
            aria-label="反馈与需求迭代"
            className="flex flex-col gap-3 rounded-2xl border border-slate-soft/80 bg-white p-4 shadow-e1"
          >
            <div className="flex items-center justify-between gap-2 border-b border-slate-soft/60 pb-2">
              <h2 className="text-headline-sm font-bold text-ink-primary">反馈与需求迭代</h2>
              <span className="text-label-sm text-ink-faint">围绕此 Demo 提建议</span>
            </div>
            <div className="h-[32rem] min-h-0">
              <CommandArea
                projectId={projectId}
                lock={lock}
                stage="iterate"
                track={detail?.endpointType === ENDPOINT_TYPE.design ? "design" : "system"}
                plan={detail?.segments}
                designPlan={planAreaOfDesignItems(detail?.designItems)}
                generated={!!detail?.generatedAt}
                variant="stage"
              />
            </div>
          </section>

          <DemoConfirmCard
            disabled={confirmDisabled}
            pending={confirmDemoVersion.isPending}
            confirmed={confirmed}
            onConfirm={onConfirm}
            orderMissing={orderMissing}
            retryPending={placeOrder.isPending}
            onRetryOrder={placeOrderThenGo}
          />
        </aside>
      </div>
      <DemoFilesDrawer projectId={projectId} open={filesOpen} onOpenChange={setFilesOpen} />
    </div>
  );
}
