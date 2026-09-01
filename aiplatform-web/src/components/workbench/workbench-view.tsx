"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { SidebarInset } from "@/components/ui/sidebar";
import { Skeleton } from "@/components/ui/skeleton";
import { useProject } from "@/hooks/use-project";
import { errorText } from "@/lib/api/api-error";
import { useAgentStreamChannel } from "@/lib/sse/agent-channel";
import { useSseStatus } from "@/lib/sse/provider";
import { coderStatusOf, useGenerationStore } from "@/lib/store/generation";
import { confirmOrderVisible } from "@/lib/projects/confirm-order";

import { CommandArea } from "./command-area";
import { ConfirmOrderButton } from "./confirm-order-button";
import { OutputsArea } from "./outputs-area";
import { StartGenerationCard, StartSystemButton } from "./start-generation";
import { WorkbenchRunStatus, WorkbenchShell } from "./workbench-shell";
import { usePlaceOrder } from "@/hooks/use-order";
import { lockRowOf } from "@/lib/orders/lock";

/**
 * 项目页装配（issue #17 单门户两槽位壳 + #19/#20 需求环 + #22 生成环①）：左指令区
 * （常开对话区，BA 访谈接通）+ 右成果区（文件 / 系统 / 项目三模式，PRD 产出后长出
 * ——判据 = prdProducedAt，document-updated 失效重拉即时切换）。闲聊期
 * （prdProducedAt 未落 = 尚无产物）指令区占满全宽、成果区不渲染。
 *
 * <p>生成环（#22）：「开始做系统」eligibility 单点在此判定（PRD 已产出 && 未生成 &&
 * 不在生成中——纯动作无门，待定项未清也可点）；对话流内卡片与文件模式操作条同一
 * 动作；编码 run 起跑自动切系统模式（用户手动切换优先至下一自动事件）。「确认下单」
 * 可见性同在此单点判定（#26：首次生成完成即常驻、零迭代可点）。交易环（#28）：
 * 订单事实（detail.activeOrder）接出——确认下单 mutation 挂输入条按钮、锁定式
 * 矩阵行在此判定（lockRowOf 单点）注入指令区与订单卡、下单成功自动切项目模式看
 * 订单卡。本组件是 agent 流通道首个挂载方（ADR 0003「工作台 mount 建连、unmount
 * 即断」）；断流超 ~10s 发一次 toast（呈现最小化约定：恢复不刷屏）。顶栏 LIVE 真
 * 绑定：项目建立即自动跑 BA，进行中亮灯。mobile 页签受控：「去看看」胶囊与发起
 * 生成/下单跳成果区。</p>
 */
export function WorkbenchView({ projectId }: { projectId: string }) {
  const { data: detail, isPending, isError, error, refetch } = useProject(projectId);
  const [mobileTab, setMobileTab] = useState("chat");
  const [outputsTab, setOutputsTab] = useState("files");
  const placeOrder = usePlaceOrder(projectId);

  useAgentStreamChannel(projectId);
  const agentStatus = useSseStatus("agent");
  const coderStatus = useGenerationStore((s) => coderStatusOf(s, projectId));

  // 编码 run 起跑（含生成中回页/重连）自动切系统模式——渲染期派生态调整
  //（同 command-area 勾选重置先例，不用 effect）；用户手动切换保留至下一自动事件
  const generating = coderStatus === "running" || coderStatus === "retrying";
  const [seenGenerating, setSeenGenerating] = useState(generating);
  if (generating !== seenGenerating) {
    setSeenGenerating(generating);
    if (generating) setOutputsTab("system");
  }

  /** 发起生成成功即看系统模式（空白浏览器窗 + 一句提示；mobile 跳成果区）。 */
  function handleGenerated() {
    setOutputsTab("system");
    setMobileTab("outputs");
  }

  /** 下单成功即看项目模式的订单卡（等待文案 + 取消入口；mobile 跳成果区）。 */
  function handleOrdered() {
    setOutputsTab("project");
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
      toast.warning("直播连接已断开，正在自动重连", { id: "agent-channel-offline" });
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

  // 闲聊期（尚无产物）：指令区占满全宽；PRD 产出后长出成果区（右槽 + 双页签）
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

  // 锁定式矩阵（#28 单点）：订单存在即冻结迭代——指令区禁用+提示、成果区只读
  const lock = lockRowOf({ archived: detail?.archived, activeOrder: detail?.activeOrder });

  // 订单卡挂的单（#30）：未终结单优先；归档终态挂最近单出完整记录（支付归档后
  // activeOrder 归空，不挂最近单会掉回「还没有订单」占位）
  const orderCardId =
    detail?.activeOrder?.id ?? (detail?.archived ? (detail?.latestOrder?.id ?? null) : null);

  return (
    <WorkbenchShell
      header={
        isPending ? (
          <Skeleton className="h-5 w-32" />
        ) : (
          <span className="truncate text-sm font-semibold">
            {detail?.name || "未命名项目"}
          </span>
        )
      }
      running={<WorkbenchRunStatus projectId={projectId} />}
      left={
        <CommandArea
          projectId={projectId}
          lock={lock}
          onSeePrd={() => setMobileTab("outputs")}
          generationCard={
            <StartGenerationCard
              projectId={projectId}
              eligible={generationEligible}
              onGenerated={handleGenerated}
            />
          }
          confirmOrder={
            showConfirmOrder ? (
              <ConfirmOrderButton
                onConfirm={() => placeOrder.mutate(undefined, { onSuccess: handleOrdered })}
              />
            ) : null
          }
        />
      }
      outputs={
        chatOnly ? undefined : (
          <OutputsArea
            projectId={projectId}
            generatedAt={detail?.generatedAt}
            coderStatus={coderStatus}
            orderCardId={orderCardId}
            projectArchived={!!detail?.archived}
            tab={outputsTab}
            onTabChange={setOutputsTab}
            onGenerated={handleGenerated}
            generationAction={
              generationEligible ? (
                <StartSystemButton projectId={projectId} onGenerated={handleGenerated} />
              ) : null
            }
          />
        )
      }
      mobileTabs={chatOnly ? ["指令区"] : ["指令区", "成果区"]}
      mobileTab={chatOnly ? undefined : mobileTab}
      onMobileTabChange={setMobileTab}
    />
  );
}
