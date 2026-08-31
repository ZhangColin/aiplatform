"use client";

import { PackageCheck } from "lucide-react";
import type { ReactNode } from "react";

import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";

import { LiveRail } from "./live-panel";
import { PanelPlaceholder } from "./panel-placeholder";
import { PrdPanel } from "./prd-panel";
import { SystemPanel } from "./system-panel";
import type { CoderRunStatus } from "@/lib/store/generation";

/**
 * 成果区（#20 长出 / #22 系统模式长出 / #23 直播侧栏）：文件 / 系统 / 项目三模式
 * + 右侧可收展直播栏（跨模式常驻——直播是 run 的面，不是某个模式的面；run 结束
 * 即逝归 LiveRail 自管）。文件模式 = PRD 文件呈现（操作条可挂「开始做系统」）；
 * 系统模式 = SystemPanel（空白浏览器窗 → run 完成自动挂预览）；项目模式随交易环
 * （订单卡）落位。tab 受控归装配层（WorkbenchView）：编码 run 起跑自动切系统
 * 模式，用户手动切换优先至下一自动事件。
 */
export function OutputsArea({
  projectId,
  generatedAt,
  coderStatus,
  tab,
  onTabChange,
  generationAction,
  onGenerated,
}: {
  projectId: string;
  /** 首次生成时点（REST 事实；null = 未生成过）。 */
  generatedAt?: string | null;
  /** 本会话编码 run 状态（undefined = 未见）。 */
  coderStatus?: CoderRunStatus;
  /** 受控 tab（装配层持有，自动切换与手动切换同一入口）。 */
  tab: string;
  onTabChange: (value: string) => void;
  /** 文件模式操作条动作（「开始做系统」，不 eligible 时为 null）。 */
  generationAction?: ReactNode;
  /** 发起生成成功回调（透传 SystemPanel 的重新发起；完整版归装配层）。 */
  onGenerated: () => void;
}) {
  return (
    // 主区域（三模式）+ 直播侧栏：lg+ 左右分栏、窄屏上下堆叠（直播为顶部条）
    <div className="flex h-full min-h-0 flex-col lg:flex-row">
      <Tabs value={tab} onValueChange={onTabChange} className="flex h-full min-h-0 flex-1 flex-col">
        <TabsList className="m-2 grid grid-cols-3">
          <TabsTrigger value="files">文件</TabsTrigger>
          <TabsTrigger value="system">系统</TabsTrigger>
          <TabsTrigger value="project">项目</TabsTrigger>
        </TabsList>
        <TabsContent value="files" className="min-h-0 flex-1 border-t">
          <PrdPanel projectId={projectId} actions={generationAction} />
        </TabsContent>
        <TabsContent value="system" className="min-h-0 flex-1 border-t">
          <SystemPanel
            projectId={projectId}
            generatedAt={generatedAt}
            coderStatus={coderStatus}
            onGenerated={onGenerated}
          />
        </TabsContent>
        <TabsContent value="project" className="min-h-0 flex-1 border-t">
          <PanelPlaceholder icon={<PackageCheck />} title="项目">
            项目与订单的信息会在这里呈现
          </PanelPlaceholder>
        </TabsContent>
      </Tabs>
      <LiveRail projectId={projectId} />
    </div>
  );
}
