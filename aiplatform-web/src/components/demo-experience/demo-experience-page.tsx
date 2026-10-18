"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { CommandArea } from "@/components/project-page/command-area";
import { SystemPanel } from "@/components/project-page/system-panel";
import { useConfirmDemoVersion, useDemoVersions } from "@/hooks/use-demo-version";
import { usePrdVersions } from "@/hooks/use-prd";
import { useProject } from "@/hooks/use-project";
import { errorText } from "@/lib/api/api-error";
import { confirmOrderVisible } from "@/lib/projects/confirm-order";
import { planAreaOfDesignItems } from "@/lib/projects/detail";
import { ENDPOINT_TYPE } from "@/lib/projects/endpoint";
import { lockRowOf } from "@/lib/orders/lock";
import { useAgentEventChannel } from "@/lib/sse/agent-event-channel";
import { useSseStatus } from "@/lib/sse/provider";
import { coderStatusOf, useGenerationStore } from "@/lib/store/generation";
import { useVersionRecordStore } from "@/lib/store/version-record";

import { DeliveryBaselineCard } from "./delivery-baseline-card";
import { DemoConfirmCard } from "./demo-confirm-card";
import { DemoFilesDrawer } from "./demo-files-drawer";
import { DemoHeader, type DemoHeaderStatus } from "./demo-header";

/** Demo 体验页 P04/P05（#347）：预览/同页等待在左 8 列，基准、对话与确认在右 4 列。 */
export function DemoExperiencePage({ projectId }: { projectId: string }) {
  const { data: detail, isPending, isError, error, refetch } = useProject(projectId);
  const { data: demoVersions } = useDemoVersions(projectId);
  const { data: prdVersions } = usePrdVersions(projectId);
  const confirmDemoVersion = useConfirmDemoVersion(projectId);
  const openVersionRecord = useVersionRecordStore((s) => s.openVersionRecord);
  const [filesOpen, setFilesOpen] = useState(false);

  useAgentEventChannel(projectId);
  const agentStatus = useSseStatus("agent");
  const coderStatus = useGenerationStore((s) => coderStatusOf(s, projectId));

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
  const onConfirm = () => {
    if (!latest || latest.versionNo == null || confirmDisabled) return;
    confirmDemoVersion.mutate(latest.versionNo);
  };
  const lock = lockRowOf({ archived: detail?.archived, activeOrder: detail?.activeOrder });

  return (
    <div className="min-h-[calc(100svh-4rem)]">
      <DemoHeader
        projectId={projectId}
        projectName={detail?.name ?? ""}
        status={status}
        loading={isPending}
        confirmDisabled={confirmDisabled}
        confirmPending={confirmDemoVersion.isPending}
        onConfirm={onConfirm}
        onStageSave={openVersionRecord}
      />

      <div className="grid grid-cols-1 items-start gap-5 p-4 lg:px-8 lg:py-6 xl:grid-cols-12">
        <section aria-label="Demo 沙箱与视口" className="h-[44rem] min-h-0 xl:col-span-8">
          {isPending ? (
            <Skeleton className="h-full w-full rounded-2xl" />
          ) : (
            <SystemPanel
              projectId={projectId}
              generationState={generationState}
              coderStatus={coderStatus}
              onGenerated={() => {}}
              onOpenFiles={() => setFilesOpen(true)}
              variant="demo"
            />
          )}
        </section>

        <aside aria-label="Demo 反馈与确认" className="flex flex-col gap-4 xl:col-span-4">
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
          />
        </aside>
      </div>
      <DemoFilesDrawer projectId={projectId} open={filesOpen} onOpenChange={setFilesOpen} />
    </div>
  );
}
