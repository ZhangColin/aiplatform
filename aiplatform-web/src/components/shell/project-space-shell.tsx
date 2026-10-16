"use client";

import type { ReactNode } from "react";

import { useProject } from "@/hooks/use-project";
import {
  currentStageOf,
  deliveryStageNav,
  stageGateFactsOf,
} from "@/lib/projects/delivery-stages";

import { GlobalTopbar } from "./global-topbar";
import { StageSidebar } from "./stage-sidebar";

/**
 * 项目空间壳装配（#343，spec #320 ⑥）：阶段侧栏＋全局顶栏＋阶段页内容位。
 * 可达性判定的单点推导在此——项目详情（真实 REST 事实）→ 门事实
 * （stageGateFactsOf）→ 导航行（deliveryStageNav）→ 侧栏/顶栏同源消费；
 * 阶段状态推导的完整语义随确认门/Demo 版本化/订单重对位落地（#330/#333/
 * #335）在投影处扩展。内容位自带滚动（各阶段页自管粘性二级 header）。
 */
export function ProjectSpaceShell({
  projectId,
  children,
}: {
  projectId: string;
  children: ReactNode;
}) {
  const { data: detail } = useProject(projectId);
  const facts = stageGateFactsOf(detail);
  const nav = deliveryStageNav(projectId, facts);

  return (
    <div className="min-h-svh bg-canvas-base">
      <StageSidebar projectId={projectId} nav={nav} currentStage={currentStageOf(facts)} />
      <GlobalTopbar projectId={projectId} nav={nav} />
      <main className="pt-16 lg:pl-64">{children}</main>
    </div>
  );
}
