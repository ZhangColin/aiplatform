"use client";

import { Database, FileText, Folder, Monitor, ReceiptText, Settings, SquareTerminal } from "lucide-react";
import type { ReactNode } from "react";

import type { CoderRunStatus } from "@/lib/store/generation";

import { PrdDoc } from "./prd-doc";
import { FilesPanel } from "./files-panel";
import { OrderPanel } from "./order-panel";
import { PanelPlaceholder } from "./panel-placeholder";
import { SystemPanel } from "./system-panel";

/**
 * 成果区范式注册表（#72 决议 / #79 落地）：每种面（系统/文档/文件/数据/订单/
 * 设置/终端）是一个自包含范式，注册进 PARADIGMS 即可被成果区 tab 簇挂载——
 * 「+ 新标签页」按需加挂，「系统」居首默认主舞台、「文档」默认挂载，其余按需。
 * 新面 = 注册表加一项，平台后续能不断填入。
 *
 * 文件的 M/U 角标与改动徽章：文件清单与改动状态由收口扩载服务端权威化
 * （#77/#88），供数前不演假状态。
 */

/** 范式渲染上下文（装配层注入的项目事实与回调）。 */
export type ParadigmCtx = {
  projectId: string;
  /** 首次生成时点（REST 事实；null = 未生成过）。 */
  generatedAt?: string | null;
  /** 本会话编码 run 状态（undefined = 未见）。 */
  coderStatus?: CoderRunStatus;
  /** 订单卡挂的单（未终结单优先；归档终态挂最近单，null = 无单 → 占位）。 */
  orderCardId?: string | null;
  /** 项目归档终态。 */
  projectArchived?: boolean;
  /** 发起生成成功回调（切系统范式呈现等待态），归装配层。 */
  onGenerated: () => void;
};

export type Paradigm = {
  id: string;
  label: string;
  icon: ReactNode;
  /** 「+ 新标签页」菜单里的一句话说明。 */
  blurb: string;
  /** 默认挂载？false = 仅在「+ 新标签页」里可加。 */
  defaultOn: boolean;
  render: (ctx: ParadigmCtx) => ReactNode;
};

export const PARADIGMS: Paradigm[] = [
  {
    id: "system",
    label: "系统",
    icon: <Monitor className="size-3.5" />,
    defaultOn: true,
    blurb: "做出来的系统，边看边用",
    render: (ctx) => (
      <SystemPanel
        projectId={ctx.projectId}
        generatedAt={ctx.generatedAt}
        coderStatus={ctx.coderStatus}
        onGenerated={ctx.onGenerated}
      />
    ),
  },
  {
    id: "docs",
    label: "文档",
    icon: <FileText className="size-3.5" />,
    defaultOn: true,
    blurb: "需求文档，随每轮修改更新",
    render: (ctx) => <PrdDoc projectId={ctx.projectId} />,
  },
  {
    id: "files",
    label: "文件",
    icon: <Folder className="size-3.5" />,
    defaultOn: false,
    blurb: "交付文件树，点开看内容",
    render: (ctx) => <FilesPanel projectId={ctx.projectId} />,
  },
  {
    id: "data",
    label: "数据",
    icon: <Database className="size-3.5" />,
    defaultOn: false,
    blurb: "做出来的系统里产生的业务数据（建设中）",
    render: () => (
      <PanelPlaceholder icon={<Database />} title="业务数据">
        系统跑起来后产生的数据（订单、预约记录等）会在这里呈现，查看能力建设中
      </PanelPlaceholder>
    ),
  },
  {
    id: "order",
    label: "订单",
    icon: <ReceiptText className="size-3.5" />,
    defaultOn: false,
    blurb: "下单与发布的记录",
    render: (ctx) => <OrderPanel orderId={ctx.orderCardId} projectArchived={ctx.projectArchived} />,
  },
  {
    id: "terminal",
    label: "终端",
    icon: <SquareTerminal className="size-3.5" />,
    defaultOn: false,
    blurb: "运行命令与日志（技术面示例）",
    render: () => (
      <PanelPlaceholder icon={<SquareTerminal />} title="终端">
        面向技术同学的命令行界面，会经同一注册表挂载（当前为扩展位占位）
      </PanelPlaceholder>
    ),
  },
  {
    id: "settings",
    label: "设置",
    icon: <Settings className="size-3.5" />,
    defaultOn: false,
    blurb: "项目名、通知等设置项",
    render: () => (
      <PanelPlaceholder icon={<Settings />} title="项目设置">
        设置项将在这里开放；眼下要改什么，直接在对话里说
      </PanelPlaceholder>
    ),
  },
];

/** 按 id 取范式；装配层自动切换（生成→系统、下单→订单）用。 */
export function paradigmOf(id: string): Paradigm | undefined {
  return PARADIGMS.find((p) => p.id === id);
}
