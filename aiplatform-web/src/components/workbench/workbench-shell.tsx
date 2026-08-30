"use client";

import * as React from "react";
import { PanelRightClose, PanelRightOpen } from "lucide-react";

import { Button } from "@/components/ui/button";
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
 * 工作台框架（通用层，spec 0001 §3「D 融合壳」）——同构参考 prototype/shared/
 * shell.tsx 的 WorkbenchFrame：顶栏 + resizable 三栏（左 Agent 区 / 中主面板 /
 * 右呼出面板）+ 右栏显式开关 + <lg 三页签退化。栏宽 / 面板内容 / 顶栏文案全部
 * 由场景层插槽决定，本组件只给结构与右栏开关状态。
 */
export type WorkbenchShellProps = {
  /** 顶栏内容：项目名 / 阶段徽章 / 面包屑（收起归品牌行，issue #50，无 Trigger）。 */
  header: React.ReactNode;
  /** 顶栏运行状态（ml-auto 处）：LIVE 脉冲 + 计时（进行中才渲染，#59）。 */
  running: React.ReactNode;
  /** 左：Agent 区。 */
  left: React.ReactNode;
  /** 中：主面板；按钮 api 供主面板工具条放右栏开关（spec 0001 §3 开关在工具条上）。 */
  center: (api: { rightOpen: boolean; toggleRight: () => void }) => React.ReactNode;
  /** 右：呼出面板内容。 */
  right: React.ReactNode;
  /** <lg 三页签：label ×3（对话 / 工作区 / 右栏名）。 */
  mobileTabs: [string, string, string];
  leftDefaultSize?: number;
  rightDefaultSize?: number;
  leftMinSize?: number;
  rightMinSize?: number;
};

export function WorkbenchShell({
  header,
  running,
  left,
  center,
  right,
  mobileTabs,
  leftDefaultSize = 380,
  rightDefaultSize = 320,
  leftMinSize = 260,
  rightMinSize = 220,
}: WorkbenchShellProps) {
  const [rightOpen, setRightOpen] = React.useState(true);
  const toggleRight = React.useCallback(() => setRightOpen((v) => !v), []);

  return (
    <SidebarInset className="h-svh min-h-0 flex-col">
      <header className="flex h-12 shrink-0 items-center gap-2 border-b px-3">
        {header}
        <div className="ml-auto flex shrink-0 items-center gap-1">{running}</div>
      </header>

      {/* resizable 三栏（lg+）；窄屏退化为三页签（spec 0001 §3 <1024px） */}
      <div className="hidden min-h-0 flex-1 lg:block">
        <ResizablePanelGroup orientation="horizontal" className="h-full">
          <ResizablePanel defaultSize={leftDefaultSize} minSize={leftMinSize} collapsible>
            <div className="h-full border-r">{left}</div>
          </ResizablePanel>
          <ResizableHandle />
          <ResizablePanel minSize={320}>
            {center({ rightOpen, toggleRight })}
          </ResizablePanel>
          {rightOpen && (
            <>
              <ResizableHandle />
              <ResizablePanel
                defaultSize={rightDefaultSize}
                minSize={rightMinSize}
                collapsible
              >
                <div className="h-full border-l">{right}</div>
              </ResizablePanel>
            </>
          )}
        </ResizablePanelGroup>
      </div>

      <Tabs defaultValue="chat" className="flex min-h-0 flex-1 flex-col lg:hidden">
        <TabsList className="m-2 grid grid-cols-3">
          <TabsTrigger value="chat">{mobileTabs[0]}</TabsTrigger>
          <TabsTrigger value="ws">{mobileTabs[1]}</TabsTrigger>
          <TabsTrigger value="right">{mobileTabs[2]}</TabsTrigger>
        </TabsList>
        <TabsContent value="chat" className="min-h-0 flex-1">
          {left}
        </TabsContent>
        <TabsContent value="ws" className="min-h-0 flex-1">
          {center({ rightOpen: false, toggleRight: () => {} })}
        </TabsContent>
        <TabsContent value="right" className="min-h-0 flex-1">
          {right}
        </TabsContent>
      </Tabs>
    </SidebarInset>
  );
}

/** 右栏显式开关钮（各门户主面板工具条共用；spec 0001 §3 显式图标开关）。 */
export function RightPanelToggle({
  open,
  onClick,
  label = "面板",
  className,
}: {
  open: boolean;
  onClick: () => void;
  label?: string;
  className?: string;
}) {
  return (
    <Button
      size="xs"
      variant="ghost"
      aria-label={open ? `收起${label}` : `展开${label}`}
      onClick={onClick}
      className={className}
    >
      {open ? <PanelRightClose className="size-3.5" /> : <PanelRightOpen className="size-3.5" />}
    </Button>
  );
}

/**
 * 顶栏运行状态（spec 0001 §3「关了浏览器也在跑」的锚点，#59 真绑定·本会话口径）：
 * 直读 streams store 当前项目最近 run（latestProjectRun，与聊天区运行条同一读口）。
 * 进行中（running / waiting）才渲染 LIVE 脉冲 + 计时（锚 run.startedAt，tick 归
 * useRunElapsed 局部）；无 run / 已终态（finished / error）整块不渲染——信号保守
 * 但不撒谎。终止占位按钮已随占位 toast 下架，终止交互等后端终止端点
 * （aiplatform-server#38）就绪另票回归。
 */
export function WorkbenchRunStatus({ projectId }: { projectId: string }) {
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
