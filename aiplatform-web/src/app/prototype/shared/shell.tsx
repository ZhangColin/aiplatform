// PROTOTYPE（throwaway）—— 主 Layout 与工作台框架的共享实现
// T4（开发平台工作台）与 T5（需求端门户）从这里拿同一套壳：
//   PortalSidebar   = spec 0001 主 Layout 的 sidebar，菜单配置化
//                     （门户/角色只给配置，不给第二套 Layout）
//   WorkbenchFrame  = D 融合壳框架：顶栏 + resizable 三栏（左 Agent 区 /
//                     中主面板 / 右呼出面板）+ 右栏显式开关 + <md 三页签
// 真实现时这两件对应 app 级 layout 与工作台 layout 组件，同构。
"use client"

import * as React from "react"
import Link from "next/link"
import { PanelRightClose, PanelRightOpen } from "lucide-react"

import { ModeToggle } from "@/components/mode-toggle"
import { Button } from "@/components/ui/button"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import {
  ResizableHandle,
  ResizablePanel,
  ResizablePanelGroup,
} from "@/components/ui/resizable"
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupContent,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarInset,
  SidebarMenu,
  SidebarMenuBadge,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarProvider,
  SidebarTrigger,
} from "@/components/ui/sidebar"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { cn } from "@/lib/utils"

// ─── 配置化平台导航 ────────────────────────────────────────────

export interface PortalNavItem {
  key: string
  label: string
  icon?: React.ReactNode
  badge?: string
  badgeTone?: "amber" | "primary"
  active?: boolean
  onClick?: () => void
  href?: string
}

export interface PortalSidebarProps {
  portal: string // 当前门户（品牌处下拉标注；v1 单账号三门户切换）
  groups: { label: string; items: PortalNavItem[] }[]
  user: { name: string; initial: string }
}

export function PortalSidebar({ portal, groups, user }: PortalSidebarProps) {
  return (
    <Sidebar collapsible="icon">
      <SidebarHeader>
        <SidebarMenu>
          <SidebarMenuItem>
            <DropdownMenu>
              <DropdownMenuTrigger render={<SidebarMenuButton size="lg" className="gap-2" />}>
                <span className="flex size-6 shrink-0 items-center justify-center rounded-md bg-primary text-xs font-bold text-primary-foreground">
                  AI
                </span>
                <span className="text-sm font-semibold">AI 开发平台</span>
                <span className="ml-auto text-[10px] text-muted-foreground">{portal} ▾</span>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="start">
                {["需求端", "开发平台", "任务平台"].map((name) => (
                  <DropdownMenuItem key={name} disabled={name === portal}>
                    {name}
                    {name === portal ? "（当前）" : ""}
                  </DropdownMenuItem>
                ))}
              </DropdownMenuContent>
            </DropdownMenu>
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarHeader>
      <SidebarContent>
        {groups.map((group) => (
          <SidebarGroup key={group.label}>
            <SidebarGroupLabel>{group.label}</SidebarGroupLabel>
            <SidebarGroupContent>
              <SidebarMenu>
                {group.items.map((item) => (
                  <SidebarMenuItem key={item.key}>
                    <SidebarMenuButton
                      isActive={item.active}
                      tooltip={item.label}
                      onClick={item.onClick}
                      {...(item.href
                        ? { render: <Link href={item.href} /> }
                        : {})}
                    >
                      {item.icon}
                      <span className="truncate">{item.label}</span>
                      {item.badge && (
                        <SidebarMenuBadge
                          className={cn(
                            item.badgeTone === "amber" && "bg-amber-500 text-amber-950",
                            item.badgeTone === "primary" && "bg-primary/15 text-primary"
                          )}
                        >
                          {item.badge}
                        </SidebarMenuBadge>
                      )}
                    </SidebarMenuButton>
                  </SidebarMenuItem>
                ))}
              </SidebarMenu>
            </SidebarGroupContent>
          </SidebarGroup>
        ))}
      </SidebarContent>
      <SidebarFooter>
        <SidebarMenu>
          <SidebarMenuItem>
            <SidebarMenuButton size="sm" className="text-muted-foreground">
              <span className="grid size-5 shrink-0 place-items-center rounded-full bg-primary/15 text-[10px] font-semibold text-primary">
                {user.initial}
              </span>
              <span>{user.name}</span>
              <ModeToggle />
            </SidebarMenuButton>
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarFooter>
    </Sidebar>
  )
}

// ─── 工作台框架（插槽式；三门户的工作台共用）──────────────────

export interface WorkbenchFrameProps {
  /** 顶栏内容（SidebarTrigger 之后：项目名/徽章/面包屑 + ml-auto 动作） */
  header: React.ReactNode
  /** 左：Agent 区 */
  left: React.ReactNode
  /** 中：主面板；按钮 api 供工具条放右栏开关 */
  center: (api: { rightOpen: boolean; toggleRight: () => void }) => React.ReactNode
  /** 右：呼出面板内容 */
  right: React.ReactNode
  /** <md 三页签：label ×3（对话 / 工作区 / 右栏名） */
  mobileTabs: [string, string, string]
  leftDefaultSize?: number
  rightDefaultSize?: number
}

export function WorkbenchFrame({
  header,
  left,
  center,
  right,
  mobileTabs,
  leftDefaultSize = 380,
  rightDefaultSize = 320,
}: WorkbenchFrameProps) {
  const [rightOpen, setRightOpen] = React.useState(true)
  const toggleRight = React.useCallback(() => setRightOpen((v) => !v), [])

  return (
    <SidebarInset className="h-svh min-h-0 flex-col">
      <header className="flex h-12 shrink-0 items-center gap-2 border-b px-3">
        <SidebarTrigger className="size-7" />
        {header}
      </header>

      {/* resizable 三栏（md+）；窄屏退化为三页签 */}
      <div className="hidden min-h-0 flex-1 md:block">
        <ResizablePanelGroup orientation="horizontal" className="h-full">
          <ResizablePanel defaultSize={leftDefaultSize} minSize={260} collapsible>
            <div className="h-full border-r">{left}</div>
          </ResizablePanel>
          <ResizableHandle />
          <ResizablePanel minSize={320}>
            {center({ rightOpen, toggleRight })}
          </ResizablePanel>
          {rightOpen && (
            <>
              <ResizableHandle />
              <ResizablePanel defaultSize={rightDefaultSize} minSize={220} collapsible>
                <div className="h-full border-l">{right}</div>
              </ResizablePanel>
            </>
          )}
        </ResizablePanelGroup>
      </div>

      <Tabs defaultValue="chat" className="flex min-h-0 flex-1 flex-col md:hidden">
        <TabsList className="m-2 grid grid-cols-3">
          <TabsTrigger value="chat">{mobileTabs[0]}</TabsTrigger>
          <TabsTrigger value="ws">{mobileTabs[1]}</TabsTrigger>
          <TabsTrigger value="right">{mobileTabs[2]}</TabsTrigger>
        </TabsList>
        <TabsContent value="chat" className="min-h-0 flex-1">{left}</TabsContent>
        <TabsContent value="ws" className="min-h-0 flex-1">
          {center({ rightOpen: false, toggleRight: () => {} })}
        </TabsContent>
        <TabsContent value="right" className="min-h-0 flex-1">{right}</TabsContent>
      </Tabs>
    </SidebarInset>
  )
}

/** 右栏开关钮（各门户主面板工具条共用；spec 0001：显式图标开关） */
export function RightPanelToggle({
  open,
  onClick,
  label = "面板",
}: {
  open: boolean
  onClick: () => void
  label?: string
}) {
  return (
    <Button
      size="xs"
      variant="ghost"
      aria-label={open ? `收起${label}` : `展开${label}`}
      onClick={onClick}
    >
      {open ? <PanelRightClose className="size-3.5" /> : <PanelRightOpen className="size-3.5" />}
    </Button>
  )
}

/** 组装：PortalSidebar + WorkbenchFrame 一次挂好（两门户同构入口） */
export function PortalWorkbench({
  sidebar,
  ...frame
}: { sidebar: PortalSidebarProps } & WorkbenchFrameProps) {
  return (
    <SidebarProvider>
      <PortalSidebar {...sidebar} />
      <WorkbenchFrame {...frame} />
    </SidebarProvider>
  )
}

/** 组装：非工作台页同壳（spec 0001 §2；页头由各页自带） */
export function PortalPage({
  sidebar,
  children,
}: { sidebar: PortalSidebarProps } & { children: React.ReactNode }) {
  return (
    <SidebarProvider>
      <PortalSidebar {...sidebar} />
      <SidebarInset className="h-svh min-h-0 flex-col">
        <main className="min-h-0 flex-1 overflow-y-auto">{children}</main>
      </SidebarInset>
    </SidebarProvider>
  )
}
