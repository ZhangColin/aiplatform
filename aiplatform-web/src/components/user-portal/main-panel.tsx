"use client";

import { BugPanel } from "@/components/dev-portal/bug-panel";
import { LivePanel } from "@/components/dev-portal/live-panel";
import { TaskPanel } from "@/components/dev-portal/task-panel";
import { PreviewPanel } from "@/components/main-chain/preview-panel";
import { TerminalPanel } from "@/components/workbench/terminal-panel";
import { RightPanelToggle } from "@/components/workbench/workbench-shell";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";

import { DocPanel } from "./doc-panel";

/**
 * 工作台主面板（spec 0001 §2 主面板 tabs，issue #22）：tab 条 = 文档 / 任务 /
 * Bug / 直播（#23：agent 流知识命中的最小呈现面）/ 预览 / 终端（#42 主面板补齐）
 * + 右栏显式开关。文档归需求端旅程（spec 0002 §4）；任务 / Bug 面板是 dev 侧 A4
 * 对接面（建任务 / 裁决 / Bug 三态，spec 0003 §2.7）——v1 单账号不分角色，同页呈现。
 */
export function WorkbenchMainPanel({
  projectId,
  rightOpen,
  onToggleRight,
  initialTab = "docs",
}: {
  projectId: string;
  rightOpen: boolean;
  onToggleRight: () => void;
  /** 深链初始 tab（issue #44：TASK_SUBMITTED / RETEST_READY → "tasks"）。 */
  initialTab?: string;
}) {
  return (
    <Tabs defaultValue={initialTab} className="h-full min-h-0 gap-0">
      <div className="flex h-10 shrink-0 items-center gap-1 border-b px-2">
        <TabsList variant="line" className="h-8">
          <TabsTrigger value="docs">文档</TabsTrigger>
          <TabsTrigger value="tasks">任务</TabsTrigger>
          <TabsTrigger value="bugs">Bug</TabsTrigger>
          <TabsTrigger value="live">直播</TabsTrigger>
          <TabsTrigger value="preview">预览</TabsTrigger>
          <TabsTrigger value="terminal">终端</TabsTrigger>
        </TabsList>
        <div className="ml-auto flex items-center">
          <RightPanelToggle
            open={rightOpen}
            onClick={onToggleRight}
            label="项目面板"
            className="hidden lg:inline-flex"
          />
        </div>
      </div>
      <TabsContent value="docs" className="min-h-0 flex-1">
        <DocPanel projectId={projectId} />
      </TabsContent>
      <TabsContent value="tasks" className="min-h-0 flex-1 overflow-y-auto">
        <TaskPanel projectId={projectId} />
      </TabsContent>
      <TabsContent value="bugs" className="min-h-0 flex-1 overflow-y-auto">
        <BugPanel projectId={projectId} />
      </TabsContent>
      <TabsContent value="live" className="min-h-0 flex-1">
        <LivePanel projectId={projectId} />
      </TabsContent>
      <TabsContent value="preview" className="min-h-0 flex-1">
        <PreviewPanel projectId={projectId} />
      </TabsContent>
      <TabsContent value="terminal" className="min-h-0 flex-1">
        <TerminalPanel projectId={projectId} />
      </TabsContent>
    </Tabs>
  );
}
