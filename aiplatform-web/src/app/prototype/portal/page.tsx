// PROTOTYPE（throwaway）—— 非工作台页示意：待办中心
// 证明主 Layout 的通用性：SidebarProvider + Sidebar + SidebarInset 同壳，
// 内容区换成标准页面（页头 + 内容卡），不再有工作台三栏。
"use client"

import * as React from "react"
import { Inbox } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import {
  SidebarInset,
  SidebarProvider,
  SidebarTrigger,
} from "@/components/ui/sidebar"
import { PlatformSidebar } from "../workbench/variant-d"
import { QueueApproval, QueueGate, QueueQuestion, QueueTestConfirm } from "../workbench/variant-c"

export default function PrototypePortalPage() {
  if (process.env.NODE_ENV === "production") return null

  return <PortalBody />
}

function PortalBody() {
  const [pending, setPending] = React.useState(3)
  const mark = React.useCallback(() => setPending((p) => Math.max(0, p - 1)), [])

  return (
    <SidebarProvider>
      <PlatformSidebar />
      <SidebarInset className="h-svh min-h-0 flex-col">
        <header className="flex h-12 shrink-0 items-center gap-2 border-b px-3">
          <SidebarTrigger className="size-7" />
          <span className="flex items-center gap-1.5 text-sm font-semibold">
            <Inbox className="size-4 text-primary" /> 待办中心
          </span>
          <span className="text-xs text-muted-foreground">跨项目聚合 · 处理后对应智能体续跑</span>
          {pending > 0 ? (
            <Badge className="ml-auto h-5 gap-1 bg-amber-500 text-amber-950 hover:bg-amber-500">
              {pending}
            </Badge>
          ) : (
            <Badge variant="secondary" className="ml-auto h-5">清空</Badge>
          )}
        </header>

        <main className="min-h-0 flex-1 overflow-y-auto">
          <div className="mx-auto max-w-3xl space-y-3 p-6">
            <p className="text-sm text-muted-foreground">
              平台级页面与工作台共用主 Layout（左侧导航 + 内容区）；主题切换在导航 footer，两处同享。
              每张卡标注来源项目，点开直达该项目工作台对应面板。
            </p>
            {pending > 0 && (
              <>
                <QueueQuestion onDone={mark} />
                <QueueApproval onDone={mark} />
                <QueueTestConfirm onDone={mark} />
              </>
            )}
            <QueueGate />
          </div>
        </main>
      </SidebarInset>
    </SidebarProvider>
  )
}
