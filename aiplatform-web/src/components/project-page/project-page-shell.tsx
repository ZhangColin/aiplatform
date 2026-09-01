"use client";

import * as React from "react";

import {
  ResizableHandle,
  ResizablePanel,
  ResizablePanelGroup,
} from "@/components/ui/resizable";
import { SidebarInset } from "@/components/ui/sidebar";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { latestProjectRun, useAgentStreamsStore } from "@/lib/store/agent-streams";
import { formatElapsed } from "@/lib/utils/time";

import { isRunInFlight, useRunElapsed } from "./run-elapsed";

/**
 * 项目页框架（issue #17 单站壳）：顶栏 + resizable 双槽（左指令区 / 右成果区）
 * + <lg 双页签退化。outputs 缺省 = 闲聊期单槽（指令区占满全宽，成果区未长——
 * 判据 = PRD 产出，issue #19）。槽位内容全由场景层注入——成果区内部再分主区域 /
 * 可收展侧栏（直播，生成环落位）时由槽内容自管嵌套，壳不预设。
 */
export type ProjectPageShellProps = {
  /** 顶栏内容：项目名等。 */
  header: React.ReactNode;
  /** 顶栏运行状态（ml-auto 处）：LIVE 脉冲 + 计时（进行中才渲染）。 */
  running: React.ReactNode;
  /** 左：指令区。 */
  left: React.ReactNode;
  /** 右：成果区（缺省 = 闲聊期，指令区占满全宽）。 */
  outputs?: React.ReactNode;
  /** <lg 页签（双槽 = 两枚；闲聊期单槽 = 单枚）。 */
  mobileTabs: [string] | [string, string];
  /**
   * <lg 页签受控值 + 切换回调（可选）：不传 = 非受控缺省指令区；场景层需要
   * 程序化切页签时传（#20「去看看」胶囊 → 成果区页）。
   */
  mobileTab?: string;
  onMobileTabChange?: (value: string) => void;
  leftDefaultSize?: number;
  rightDefaultSize?: number;
  leftMinSize?: number;
  rightMinSize?: number;
};

export function ProjectPageShell({
  header,
  running,
  left,
  outputs,
  mobileTabs,
  mobileTab,
  onMobileTabChange,
  leftDefaultSize = 380,
  rightDefaultSize = 480,
  leftMinSize = 260,
  rightMinSize = 320,
}: ProjectPageShellProps) {
  return (
    <SidebarInset className="h-svh min-h-0 flex-col">
      <header className="flex h-12 shrink-0 items-center gap-2 border-b px-3">
        {header}
        <div className="ml-auto flex shrink-0 items-center gap-1">{running}</div>
      </header>

      {outputs === undefined ? (
        // 闲聊期（尚无产物）：指令区占满全宽
        <div className="min-h-0 flex-1">{left}</div>
      ) : (
        <>
          {/* resizable 双槽（lg+）；窄屏退化为双页签（<1024px） */}
          <div className="hidden min-h-0 flex-1 lg:block">
            <ResizablePanelGroup orientation="horizontal" className="h-full">
              <ResizablePanel defaultSize={leftDefaultSize} minSize={leftMinSize} collapsible>
                <div className="h-full border-r">{left}</div>
              </ResizablePanel>
              <ResizableHandle />
              <ResizablePanel defaultSize={rightDefaultSize} minSize={rightMinSize}>
                {outputs}
              </ResizablePanel>
            </ResizablePanelGroup>
          </div>

          <Tabs
            {...(mobileTab !== undefined
              ? { value: mobileTab, onValueChange: onMobileTabChange }
              : { defaultValue: "chat" })}
            className="flex min-h-0 flex-1 flex-col lg:hidden"
          >
            <TabsList className="m-2 grid grid-cols-2">
              <TabsTrigger value="chat">{mobileTabs[0]}</TabsTrigger>
              <TabsTrigger value="outputs">{mobileTabs[1]}</TabsTrigger>
            </TabsList>
            <TabsContent value="chat" className="min-h-0 flex-1">
              {left}
            </TabsContent>
            <TabsContent value="outputs" className="min-h-0 flex-1">
              {outputs}
            </TabsContent>
          </Tabs>
        </>
      )}
    </SidebarInset>
  );
}

/**
 * 顶栏运行状态（「关了浏览器也在跑」的锚点，LIVE 真绑定·本会话口径）：
 * 直读 streams store 当前项目最近 run（latestProjectRun）。进行中（running /
 * waiting）才渲染 LIVE 脉冲 + 计时（锚 run.startedAt，tick 归 useRunElapsed
 * 局部）；无 run / 已终态（finished / error）整块不渲染——信号保守但不撒谎。
 */
export function ProjectPageRunStatus({ projectId }: { projectId: string }) {
  const run = useAgentStreamsStore((s) => latestProjectRun(s, projectId));
  const inFlight = isRunInFlight(run);
  const elapsed = useRunElapsed(inFlight ? run : undefined);
  if (!inFlight || !run) return null;

  return (
    <span className="flex items-center gap-2 rounded-full border border-red-500/40 bg-red-500/10 px-2.5 py-1">
      <span className="relative flex size-2">
        <span className="absolute inline-flex size-full animate-ping rounded-full bg-red-500 opacity-60" />
        <span className="relative inline-flex size-2 rounded-full bg-red-500" />
      </span>
      <span className="text-xs font-semibold text-red-600 dark:text-red-400">LIVE</span>
      <span className="font-mono text-xs tabular-nums text-red-600 dark:text-red-400">
        {formatElapsed(elapsed)}
      </span>
    </span>
  );
}
