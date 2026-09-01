"use client";

import type { ReactNode } from "react";

import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";

import { FilesPanel } from "./files-panel";
import { LiveRail } from "./live-panel";
import { OrderPanel } from "./order-panel";
import { SystemPanel } from "./system-panel";
import type { CoderRunStatus } from "@/lib/store/generation";

/**
 * 成果区（#20 长出 / #22 系统模式长出 / #23 直播侧栏 / #27 文件树 / #28 订单卡
 * / #30 归档终态记录）：文件 / 系统 / 项目三模式 + 右侧可收展直播栏（跨模式
 * 常驻——直播是 run 的面，不是某个模式的面；run 结束即逝归 LiveRail 自管）。
 * 文件模式 = FilesPanel（交付文件树 + 点看内容，PRD 是缺省一篇；操作条可挂
 * 「开始做系统」）；系统模式 = SystemPanel（空白浏览器窗 → run 完成自动挂
 * 预览）；项目模式 = OrderPanel（订单当前态卡，无订单 = 引导占位；归档终态
 * 挂最近订单出完整记录）。tab 受控归装配层（WorkbenchView）：编码 run 起跑
 * 自动切系统模式、下单自动切项目模式，用户手动切换优先至下一自动事件。
 */
export function OutputsArea({
  projectId,
  generatedAt,
  coderStatus,
  orderCardId,
  projectArchived,
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
  /** 订单卡挂的单（未终结单优先；归档终态挂最近单，null = 无单 → 占位，#30）。 */
  orderCardId?: string | null;
  /** 项目归档终态（无订单时的占位文案口径）。 */
  projectArchived?: boolean;
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
          <FilesPanel projectId={projectId} actions={generationAction} />
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
          <OrderPanel orderId={orderCardId} projectArchived={projectArchived} />
        </TabsContent>
      </Tabs>
      <LiveRail projectId={projectId} />
    </div>
  );
}
