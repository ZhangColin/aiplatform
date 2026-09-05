"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { SidebarInset } from "@/components/ui/sidebar";
import { Skeleton } from "@/components/ui/skeleton";
import { useProject } from "@/hooks/use-project";
import { errorText } from "@/lib/api/api-error";
import { useAgentEventChannel } from "@/lib/sse/agent-event-channel";
import { useSseStatus } from "@/lib/sse/provider";
import { coderStatusOf, useGenerationStore } from "@/lib/store/generation";
import { confirmOrderVisible } from "@/lib/projects/confirm-order";

import { CommandArea } from "./command-area";
import { ConfirmOrderButton } from "./confirm-order-button";
import { OutputsArea, useOutputsTabs } from "./outputs-area";
import { StartGenerationCard, StartSystemButton } from "./start-generation";
import { ProjectPageRunStatus, ProjectPageShell } from "./project-page-shell";
import { usePlaceOrder } from "@/hooks/use-order";
import { lockRowOf } from "@/lib/orders/lock";

/**
 * 项目页装配（issue #17 单站壳 + #19/#20 需求环 + #22 生成环① + #79 对话
 * 主角式定稿）：居中对话区（主智能体对话接通）+ 呼出式成果区（范式注册表 tab 簇，
 * PRD 产出后长出并自动滑出——判据 = prdProducedAt，document-updated 失效重拉
 * 即时切换）。闲聊期（prdProducedAt 未落）对话区占满全宽、成果区不渲染。
 *
 * <p>成果区开合与 tab 簇归此持有：有成果自动滑出一次（含回访/刷新）、发起
 * 生成/编码 run 起跑自动开并切「系统」、下单成功自动挂「订单」、「去看看」
 * 挂「文档」；用户手动收起/挂载/关闭优先至下一自动事件。「开始做系统」
 * eligibility 单点在此判定（PRD 已产出 && 未生成 && 不在生成中——纯动作无门，
 * 待定项未清也可点）；对话流内卡片与文件范式操作条同一动作。「确认下单」
 * 可见性同在此单点判定（#26：首次生成完成即常驻、零迭代可点）。交易环（#28）：
 * 订单事实（detail.activeOrder）接出——确认下单 mutation 挂输入条按钮、锁定式
 * 矩阵行在此判定（lockRowOf 单点）注入对话区与订单范式。本组件是 agent 流通道
 * 首个挂载方（ADR 0003「项目页 mount 建连、unmount 即断」）；断流超 ~10s 发
 * 一次 toast（呈现最小化约定：恢复不刷屏）。顶栏 LIVE 真绑定：项目建立即自动
 * 跑生成，进行中亮灯。mobile 页签受控：发起生成/下单跳成果区。</p>
 */
export function ProjectPageView({ projectId }: { projectId: string }) {
  const { data: detail, isPending, isError, error, refetch } = useProject(projectId);
  const [mobileTab, setMobileTab] = useState("chat");
  const outputsTabs = useOutputsTabs();
  /** 成果区呼出态：有成果自动滑出一次（含回访）；用户可收起、顶栏「成果」呼出。 */
  const [outputsOpen, setOutputsOpen] = useState(false);
  const placeOrder = usePlaceOrder(projectId);

  useAgentEventChannel(projectId);
  const agentStatus = useSseStatus("agent");
  const coderStatus = useGenerationStore((s) => coderStatusOf(s, projectId));

  // 有成果（PRD 产出）即滑出一次——渲染期派生态（同 seenGenerating 先例），
  // 含刷新/回访挂载（成果在那里，应当场可见）：seenOutputs 起步 false，
  // 首条见成果即开
  const hasOutputs = !!detail?.prdProducedAt;
  const [seenOutputs, setSeenOutputs] = useState(false);
  if (hasOutputs && !seenOutputs) {
    setSeenOutputs(true);
    setOutputsOpen(true);
  }

  // 编码 run 起跑（含生成中回页/重连）自动开成果区并切「系统」——渲染期派生态
  // 调整（不用 effect）；用户手动切换保留至下一自动事件
  const generating = coderStatus === "running";
  const [seenGenerating, setSeenGenerating] = useState(generating);
  if (generating !== seenGenerating) {
    setSeenGenerating(generating);
    if (generating) openOutputsTo("system");
  }

  /** 开成果区并挂某范式（mobile 跳成果区页）——自动切换三入口共用的动作。 */
  function openOutputsTo(id: string) {
    setOutputsOpen(true);
    outputsTabs.mount(id);
    setMobileTab("outputs");
  }

  // 「断线」语义 = 连上过再掉线：通道从未连上（probe 慢 / 后端挂起时的初始
  // offline）不武装计时，免得对刚进页面的用户误报「已断开」。
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
      <SidebarInset className="flex h-svh min-h-0 flex-col">
        <div className="flex min-h-0 flex-1 flex-col items-center justify-center gap-3 text-sm text-muted-foreground">
          <p>{errorText(error, "项目加载失败")}</p>
          <div className="flex gap-2">
            <Button variant="outline" size="sm" onClick={() => void refetch()}>
              重试
            </Button>
            <Button
              variant="ghost"
              size="sm"
              nativeButton={false}
              render={<Link href="/projects" />}
            >
              返回项目列表
            </Button>
          </div>
        </div>
      </SidebarInset>
    );
  }

  // 闲聊期（尚无产物）：对话区占满全宽；PRD 产出后长出成果区（呼出式 + 双页签）
  const chatOnly = !detail?.prdProducedAt;

  // 「开始做系统」eligibility（单点）：PRD 已产出 && 未生成过 && 不在生成中
  //（超限终态 error 时按钮回来 = 人工兜底重新发起）；归档终态全只读不再发起
  const generationEligible =
    !!detail?.prdProducedAt && !detail?.generatedAt && !generating && !detail?.archived;

  // 「确认下单」可见性（单点，#26 规则 + #28 订单事实接出）：随首次生成完成
  // 常驻、零迭代可点；仅无未终结订单时显示
  const showConfirmOrder = confirmOrderVisible({
    generatedAt: detail?.generatedAt,
    archived: detail?.archived,
    activeOrderId: detail?.activeOrder?.id ?? null,
  });

  // 锁定式矩阵（#28 单点）：订单存在即冻结迭代——对话区禁用+提示、成果区只读
  const lock = lockRowOf({ archived: detail?.archived, activeOrder: detail?.activeOrder });

  // 订单卡挂的单（#30）：未终结单优先；归档终态挂最近单出完整记录（支付归档后
  // activeOrder 归空，不挂最近单会掉回「还没有订单」占位）
  const orderCardId =
    detail?.activeOrder?.id ?? (detail?.archived ? (detail?.latestOrder?.id ?? null) : null);

  return (
    <ProjectPageShell
      header={
        isPending ? (
          <Skeleton className="h-5 w-32" />
        ) : (
          <span className="truncate text-sm font-semibold">
            {detail?.name || "未命名项目"}
          </span>
        )
      }
      running={<ProjectPageRunStatus projectId={projectId} />}
      chat={
        <CommandArea
          projectId={projectId}
          lock={lock}
          stage={chatOnly ? "interview" : "iterate"}
          onSeePrd={() => openOutputsTo("docs")}
          generationCard={
            <StartGenerationCard
              projectId={projectId}
              eligible={generationEligible}
              onGenerated={() => openOutputsTo("system")}
            />
          }
          confirmOrder={
            showConfirmOrder ? (
              <ConfirmOrderButton
                onConfirm={() => placeOrder.mutate(undefined, { onSuccess: () => openOutputsTo("order") })}
              />
            ) : null
          }
        />
      }
      outputs={
        chatOnly ? undefined : (
          <OutputsArea
            tabs={outputsTabs}
            ctx={{
              projectId,
              generatedAt: detail?.generatedAt,
              coderStatus,
              orderCardId,
              projectArchived: !!detail?.archived,
              onGenerated: () => openOutputsTo("system"),
              generationAction: generationEligible ? (
                <StartSystemButton projectId={projectId} onGenerated={() => openOutputsTo("system")} />
              ) : null,
            }}
            onClose={() => setOutputsOpen(false)}
          />
        )
      }
      outputsOpen={outputsOpen}
      onOutputsOpen={() => setOutputsOpen(true)}
      mobileTabs={chatOnly ? ["对话"] : ["对话", "成果"]}
      mobileTab={chatOnly ? undefined : mobileTab}
      onMobileTabChange={setMobileTab}
    />
  );
}
