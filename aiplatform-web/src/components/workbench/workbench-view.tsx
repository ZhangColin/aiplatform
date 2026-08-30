"use client";

import Link from "next/link";
import { useEffect, useRef } from "react";
import { toast } from "sonner";

import { JourneyBreadcrumb } from "@/components/main-chain/journey-views";
import { GateCard } from "@/components/main-chain/gate-card";
import { StageTimeline } from "@/components/main-chain/stage-views";
import { WorkbenchMainPanel } from "@/components/user-portal/main-panel";
import { WorkbenchRightPanel } from "@/components/user-portal/right-panel";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { ScrollArea } from "@/components/ui/scroll-area";
import { SidebarInset } from "@/components/ui/sidebar";
import { Skeleton } from "@/components/ui/skeleton";
import { useProjectJourney } from "@/hooks/use-project";
import { errorText } from "@/lib/api/api-error";
import { isGateReady } from "@/lib/main-chain/project";
import { useAgentStreamChannel } from "@/lib/sse/agent-channel";
import { useSseStatus } from "@/lib/sse/provider";

import { AgentArea, type AgentAreaVariant } from "./agent-area";
import type { ChatFocus } from "./chat-mode";
import { WorkbenchRunStatus, WorkbenchShell } from "./workbench-shell";
import type { WorkbenchDeepLink } from "@/lib/todos/deep-link";

/**
 * 工作台装配（通用层 D 壳，spec 0001 §3 / issue #37）：左 Agent 区 + 中主面板
 * tabs（文档 / 任务 / Bug / 直播）+ 右呼出面板，三栏 resizable、右栏显式开关、
 * <lg 三页签退化、顶栏 LIVE 真绑定（#59：进行中才亮，无 run 整块不渲染）。
 * 场景只决定 Agent 区形态（`variant`）与右栏内容：需求端 = 顾问单对话 + 项目
 * 信息；开发平台 = 三模式 + 阶段·任务面板。
 *
 * #23：本页是 agent 流通道首个挂载方（ADR 0003「工作台 mount 建连、unmount 即断」）；
 * 断流超 ~10s 发一次 toast（呈现最小化约定：恢复不刷屏）。
 */
export type WorkbenchVariant = AgentAreaVariant;

export function WorkbenchView({
  projectId,
  variant = "advisor",
  deepLink,
}: {
  projectId: string;
  variant?: WorkbenchVariant;
  /** 待办深链（issue #44）：wait/gate → 对话模式聚焦，tasks → 主面板任务 tab。 */
  deepLink?: WorkbenchDeepLink | null;
}) {
  const { data: detail, isPending, isError, error, refetch, steps, current } =
    useProjectJourney(projectId);

  // 深链 → 对话模式聚焦 / 主面板初始 tab（映射在此，不散落子组件）
  const chatFocus: ChatFocus | undefined =
    deepLink?.kind === "wait"
      ? { kind: "wait", waitId: deepLink.waitId }
      : deepLink?.kind === "gate"
        ? { kind: "gate" }
        : undefined;
  const mainTab = deepLink?.kind === "tasks" ? "tasks" : undefined;

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

  // 「等你」徽章 = 门就绪（#58 收口谓词，与挂卡点同源；缺失 = 未就绪）
  const waiting = isGateReady(detail?.gate);

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
              render={<Link href={variant === "dev" ? "/dev" : "/projects"} />}
            >
              返回项目列表
            </Button>
          </div>
        </div>
      </SidebarInset>
    );
  }

  return (
    <WorkbenchShell
      header={
        <>
          {isPending ? (
            <Skeleton className="h-5 w-32" />
          ) : (
            <span className="truncate text-sm font-semibold">
              {detail?.name || "未命名项目"}
            </span>
          )}
          {waiting ? (
            <Badge className="h-5 bg-amber-500 text-amber-950 hover:bg-amber-500">
              {current?.label ?? detail?.stageLabel} · 等你
            </Badge>
          ) : (
            current && <Badge variant="secondary" className="h-5">{current.label}</Badge>
          )}
          <JourneyBreadcrumb steps={steps} className="mx-2 hidden lg:flex" />
        </>
      }
      running={<WorkbenchRunStatus projectId={projectId} />}
      left={<AgentArea variant={variant} projectId={projectId} focus={chatFocus} />}
      center={({ rightOpen, toggleRight }) => (
        <WorkbenchMainPanel
          projectId={projectId}
          rightOpen={rightOpen}
          onToggleRight={toggleRight}
          initialTab={mainTab}
        />
      )}
      right={
        variant === "dev" ? (
          <DevStageTaskPanel projectId={projectId} />
        ) : (
          <WorkbenchRightPanel projectId={projectId} />
        )
      }
      mobileTabs={variant === "dev" ? ["对话", "工作区", "阶段"] : ["对话", "工作区", "项目信息"]}
    />
  );
}

/**
 * 开发平台右栏（spec 0001 §3「阶段·任务面板」）：期步骤（StageTimeline）+ 决策门
 * （GateCard，#43 接线）+ 测试任务（占位，#45 HITL 队列后续填）。决策门就绪才
 * 挂（#58 收口）、当场可拍板，与对话流底的门卡同源（同一 `gate` + hooks）。
 */
function DevStageTaskPanel({ projectId }: { projectId: string }) {
  const { data: detail, progress } = useProjectJourney(projectId);
  const gate = detail?.gate ?? null;

  return (
    <ScrollArea className="h-full">
      <div className="space-y-4 p-4">
        <section className="space-y-2">
          <h3 className="text-xs font-medium text-muted-foreground">期步骤</h3>
          {progress ? (
            <StageTimeline progress={progress} />
          ) : (
            <Skeleton className="h-20 w-full" />
          )}
        </section>

        {isGateReady(gate) && (
          <GateCard projectId={projectId} stageLabel={detail?.stageLabel ?? ""} gate={gate} />
        )}

        <section className="space-y-2">
          <h3 className="text-xs font-medium text-muted-foreground">测试任务</h3>
          <p className="rounded-lg border border-dashed p-4 text-center text-xs text-muted-foreground">
            测试任务卡（执行方 / Bug 清单 / 确认·驳回）随 HITL 队列落
          </p>
        </section>
      </div>
    </ScrollArea>
  );
}
