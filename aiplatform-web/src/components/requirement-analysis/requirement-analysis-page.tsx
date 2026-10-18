"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useRef } from "react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { useProject } from "@/hooks/use-project";
import { usePrd, usePrdVersions } from "@/hooks/use-prd";
import { errorText } from "@/lib/api/api-error";
import { prdBadgeOf } from "@/lib/projects/prd-badge";
import { ENDPOINT_TYPE } from "@/lib/projects/endpoint";
import { planAreaOfDesignItems } from "@/lib/projects/detail";
import { lockRowOf } from "@/lib/orders/lock";
import { useAgentEventChannel } from "@/lib/sse/agent-event-channel";
import { useSseStatus } from "@/lib/sse/provider";
import { usePrdNoticesStore } from "@/lib/store/prd-notices";

import { CommandArea } from "../project-page/command-area";
import { ConvergenceCard } from "./convergence-card";
import { PrdPanel } from "./prd-panel";
import { RequirementAnalysisHeader } from "./ra-header";

/**
 * 需求分析页 P02/P03（#346，spec #320 ⑥ IA 全量重建——阶段页制的需求分析
 * 阶段页＝项目页本路由）：粘性二级 header（代号胶囊/项目名/PRD 版本徽标/
 * 「对话调优」/「导出 (PDF/Word)」/主 CTA 确认门）＋ 12 列双栏（左 5＝收敛
 * 卡＋对话时间流＋澄清输入，右 7＝PRD 文档面）。闲聊期（无 PRD）对话占全宽；
 * 首产 PRD 右栏滑出（live 切面机制迁移——判据＝prdProducedAt，document-updated
 * 失效重拉即时切换，滑入动效即状态切换反馈）。
 *
 * <p>本组件是 agent 流通道首个挂载方（ADR 0003「项目页 mount 建连、unmount
 * 即断」）；断流超 ~10s 发一次 toast（呈现最小化约定）。已确认无停驻草稿时主
 * CTA 换「前往 Demo 体验」（阶段推进位）。「确认下单」顶栏按钮已退役（spec
 * 删除清单：并入 Demo 确认门——Demo 阶段页归 #347）。</p>
 */
export function RequirementAnalysisPage({ projectId }: { projectId: string }) {
  const router = useRouter();
  const { data: detail, isPending, isError, error, refetch } = useProject(projectId);
  const prd = usePrd(projectId);
  const { data: prdVersions } = usePrdVersions(projectId);
  const markSeen = usePrdNoticesStore((s) => s.markSeen);

  useAgentEventChannel(projectId);
  const agentStatus = useSseStatus("agent");

  const chatInputRef = useRef<HTMLTextAreaElement>(null);
  const articleRef = useRef<HTMLElement>(null);

  // PRD 挂出兜底登记（prd-doc 同款口径）：后续修订才该出横幅（首次产出不打扰）
  useEffect(() => {
    if (prd.data) markSeen(projectId);
  }, [prd.data, projectId, markSeen]);

  // 「断线」语义 = 连上过再掉线（装配口径沿旧项目页）：通道从未连上不武装计时
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
      <div className="flex h-[calc(100svh-4rem)] min-h-0 flex-col">
        <div className="flex min-h-0 flex-1 flex-col items-center justify-center gap-3 text-sm text-muted-foreground">
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
      </div>
    );
  }

  // 闲聊期（尚无产物）：对话占全宽；PRD 产出后右栏滑出（判据 = prdProducedAt）
  const chatOnly = !detail?.prdProducedAt;
  const draftPending = !!prd.data?.draftChangeSummary;
  const badge = prdBadgeOf({
    hasPrd: !!detail?.prdProducedAt,
    latestVersionNo: prdVersions?.[0]?.versionNo ?? null,
    draftPending,
  });

  // 锁定式矩阵（#28 单点）：订单存在即冻结迭代——澄清输入禁用+提示
  const lock = lockRowOf({ archived: detail?.archived, activeOrder: detail?.activeOrder });

  /** 「对话调优」：聚焦澄清输入并滚动到位。 */
  function focusChat() {
    chatInputRef.current?.scrollIntoView({ behavior: "smooth", block: "nearest" });
    chatInputRef.current?.focus();
  }

  const chat = (
    <CommandArea
      projectId={projectId}
      lock={lock}
      stage={chatOnly ? "interview" : "iterate"}
      // 常驻文案轨道（#299）：设计主线＝设计形；系统/系统＋设计＝系统形
      track={detail?.endpointType === ENDPOINT_TYPE.design ? "design" : "system"}
      plan={detail?.segments}
      designPlan={planAreaOfDesignItems(detail?.designItems)}
      generated={!!detail?.generatedAt}
      variant="stage"
      inputRef={chatInputRef}
      // 报价卡「查看订单详情」（#203）：签约域在 Demo/签约阶段页——过渡期归
      // /demo 过渡桥（旧布局暂挂，#347/#348 落页替换）
      onSeeOrder={() => router.push(`/projects/${projectId}/demo`)}
    />
  );

  return (
    <div className="flex min-h-0 flex-col xl:h-[calc(100svh-4rem)]">
      <RequirementAnalysisHeader
        projectId={projectId}
        projectName={detail?.name ?? ""}
        badge={badge}
        hasPrd={!chatOnly}
        readiness={prd.data?.readiness ?? undefined}
        changeSummary={prd.data?.draftChangeSummary}
        onFocusChat={focusChat}
        getArticleHtml={() => articleRef.current?.innerHTML ?? null}
        loading={isPending}
      />

      {chatOnly ? (
        // 闲聊期：对话占全宽（收敛卡 0/5 全灰兜底在场——五维流水线先行可见）
        <div className="min-h-0 flex-1 p-4 lg:px-8 lg:py-6">
          <div className="mx-auto flex h-[70svh] max-w-3xl flex-col gap-4 xl:h-full">
            <ConvergenceCard dimensions={prd.data?.readiness?.dimensions} />
            <div className="min-h-0 flex-1">{chat}</div>
          </div>
        </div>
      ) : (
        // 双栏 5:7（照原型 12 列栅格）；窄屏堆叠（移动端收口归 #350）
        <div className="grid min-h-0 flex-1 grid-cols-1 gap-6 p-4 lg:p-8 xl:grid-cols-12">
          <div className="flex h-[70svh] min-h-0 flex-col gap-4 xl:col-span-5 xl:h-auto">
            <ConvergenceCard dimensions={prd.data?.readiness?.dimensions} />
            <div className="min-h-0 flex-1">{chat}</div>
          </div>
          {/* 首产滑出：滑入动效即状态切换反馈（成果区 outputs-slide-in 同款） */}
          <div className="h-[80svh] min-h-0 xl:col-span-7 xl:h-auto [animation:outputs-slide-in_.22s_ease-out]">
            {prd.isPending ? (
              <Skeleton className="h-full w-full rounded-2xl" />
            ) : prd.data ? (
              <PrdPanel
                content={prd.data.content ?? ""}
                readiness={prd.data.readiness ?? undefined}
                articleRef={articleRef}
              />
            ) : (
              <div className="flex h-full items-center justify-center rounded-2xl border border-slate-soft/80 bg-white p-6 text-body-sm text-ink-faint shadow-e1">
                需求文档整理中，稍候即出。
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
