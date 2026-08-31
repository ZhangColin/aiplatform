"use client";

import Link from "next/link";
import { useEffect, useRef } from "react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { SidebarInset } from "@/components/ui/sidebar";
import { Skeleton } from "@/components/ui/skeleton";
import { useProject } from "@/hooks/use-project";
import { errorText } from "@/lib/api/api-error";
import { useAgentStreamChannel } from "@/lib/sse/agent-channel";
import { useSseStatus } from "@/lib/sse/provider";

import { CommandArea } from "./command-area";
import { PanelPlaceholder } from "./panel-placeholder";
import { WorkbenchRunStatus, WorkbenchShell } from "./workbench-shell";

/**
 * 项目页装配（issue #17 单门户两槽位壳 + #19 需求环①）：左指令区（常开对话区，
 * BA 访谈接通）+ 右成果区（文件 / 系统 / 项目三模式，PRD 产出后长出）。闲聊期
 * （prdProducedAt 未落 = 尚无产物）指令区占满全宽、成果区不渲染。本组件是
 * agent 流通道首个挂载方（ADR 0003「工作台 mount 建连、unmount 即断」）；断流
 * 超 ~10s 发一次 toast（呈现最小化约定：恢复不刷屏）。顶栏 LIVE 真绑定：项目
 * 建立即自动跑 BA，进行中亮灯。
 */
export function WorkbenchView({ projectId }: { projectId: string }) {
  const { data: detail, isPending, isError, error, refetch } = useProject(projectId);

  useAgentStreamChannel(projectId);
  const agentStatus = useSseStatus("agent");
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
      left={<CommandArea projectId={projectId} disabled={detail?.archived ?? false} />}
      outputs={
        chatOnly ? undefined : (
          <PanelPlaceholder title="成果区">
            PRD 已产出，文件 / 系统 / 项目三模式随后续切片长出
          </PanelPlaceholder>
        )
      }
      mobileTabs={chatOnly ? ["指令区"] : ["指令区", "成果区"]}
    />
  );
}
