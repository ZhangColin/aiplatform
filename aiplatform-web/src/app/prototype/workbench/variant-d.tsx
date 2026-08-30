// PROTOTYPE（throwaway）—— Variant D · 融合壳（用户反馈固化版）
// 主 Layout 与工作台框架抽至 ../shared/shell（与 T5 需求端门户共用同一
// 套，本文件只给开发平台的配置与面板内容）：
//   结构：左侧可收起展开的平台导航（角色相关菜单）→ resizable 三栏
//   （栏间拖宽窄、左右栏可收起）→ Agent 区按场景切模式（对话 A / 直播
//   B / 待处理 C）→ 主面板 tab 化（预览可独立浏览器打开）。
// <1024px：退化为「对话 / 工作区 / 阶段」三页签（同 A）。
"use client"

import * as React from "react"
import { toast } from "sonner"
import {
  ChevronRight,
  FlaskConical,
  FolderKanban,
  Inbox,
  LayoutGrid,
  Plus,
  ShieldQuestion,
  SquareTerminal,
  Users,
  X,
} from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { ScrollArea } from "@/components/ui/scroll-area"
import { SidebarTrigger } from "@/components/ui/sidebar"
import { Spinner } from "@/components/ui/spinner"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { cn } from "@/lib/utils"
import {
  PortalPage,
  PortalSidebar,
  PortalSidebarProps,
  PortalWorkbench,
  RightPanelToggle,
} from "../shared/shell"
import { PROJECT, RUN, formatElapsed } from "./canned"
import { ChatPanel, StagePanel, WorkspacePanel } from "./variant-a"
import { StageStream } from "./variant-b"
import { QueueApproval, QueueGate, QueueQuestion, QueueTestConfirm } from "./variant-c"

// ─── 平台导航：开发平台配置（Layout 来自 ../shared/shell；菜单契约
//     与需求端一致：项目组 + 平台组[项目列表·新建项目·门户特有]；
//     非工作台页 /prototype/portal 复用同一壳）─────────────────────

type DevView = { kind: "workbench" } | { kind: "list" }

function devSidebar(go: (v: DevView) => void, view: DevView): PortalSidebarProps {
  return {
    portal: "开发平台",
    user: { name: "张同学", initial: "张" },
    groups: [
      {
        label: "项目",
        items: [
          {
            key: "p1",
            label: "宠物医院预约官网",
            icon: <FolderKanban />,
            badge: "开发",
            badgeTone: "primary" as const,
            active: view.kind === "workbench",
            onClick: () => go({ kind: "workbench" }),
          },
          { key: "p2", label: "鲜花电商小程序", icon: <FolderKanban />, badge: "测试" },
          { key: "p3", label: "律所官网", icon: <FolderKanban />, badge: "已交付" },
        ],
      },
      {
        label: "平台",
        items: [
          {
            key: "list",
            label: "项目列表",
            icon: <LayoutGrid />,
            active: view.kind === "list",
            onClick: () => go({ kind: "list" }),
          },
          {
            key: "new",
            label: "新建项目",
            icon: <Plus />,
            // 新建=全平台通用能力：同一颗「一句话创建」框（需求端同款）
            href: "/prototype/user-portal?variant=D",
          },
          {
            key: "inbox",
            label: "待办中心",
            icon: <Inbox />,
            badge: "3",
            badgeTone: "amber" as const,
            href: "/prototype/portal",
          },
          { key: "members", label: "成员", icon: <Users /> },
        ],
      },
    ],
  }
}

export function PlatformSidebar() {
  return <PortalSidebar {...devSidebar(() => {}, { kind: "workbench" })} />
}

// ─── Agent 区：场景化模式（对话 / 直播 / 待处理）────────────────

function AgentPanel() {
  const [pending, setPending] = React.useState(3)
  const mark = React.useCallback(() => setPending((p) => Math.max(0, p - 1)), [])

  return (
    <Tabs defaultValue="chat" className="flex h-full min-h-0 flex-col">
      <div className="flex items-center gap-1 border-b px-2 py-1.5">
        <TabsList className="h-7">
          <TabsTrigger value="chat" className="text-xs">对话</TabsTrigger>
          <TabsTrigger value="live" className="gap-1 text-xs">
            <span className="relative flex size-1.5">
              <span className="absolute inline-flex size-full animate-ping rounded-full bg-red-500 opacity-60" />
              <span className="relative inline-flex size-1.5 rounded-full bg-red-500" />
            </span>
            直播
          </TabsTrigger>
          <TabsTrigger value="todo" className="gap-1 text-xs">
            <Inbox className="size-3" />
            待处理
            {pending > 0 && (
              <Badge className="h-4 rounded-full bg-amber-500 px-1.5 text-[10px] text-amber-950 hover:bg-amber-500">
                {pending}
              </Badge>
            )}
          </TabsTrigger>
        </TabsList>
      </div>
      <TabsContent value="chat" className="min-h-0 flex-1">
        <ChatPanel />
      </TabsContent>
      <TabsContent value="live" className="min-h-0 flex-1">
        <ScrollArea className="h-full">
          <StageStream />
        </ScrollArea>
      </TabsContent>
      <TabsContent value="todo" className="min-h-0 flex-1">
        <ScrollArea className="h-full">
          <div className="mx-auto max-w-xl space-y-3 p-4">
            <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
              <ShieldQuestion className="size-3.5" />
              人不在时攒下的决策项；处理后智能体立即续跑（多角色协作同此聚合）
            </p>
            {pending === 0 ? (
              <div className="rounded-lg border border-dashed py-12 text-center text-sm text-muted-foreground">
                <SquareTerminal className="mx-auto mb-2 size-6 opacity-50" />
                已全部处理 · 智能体执行中
              </div>
            ) : (
              <>
                <QueueQuestion onDone={mark} />
                <QueueApproval onDone={mark} />
                <QueueTestConfirm onDone={mark} />
              </>
            )}
            <QueueGate />
          </div>
        </ScrollArea>
      </TabsContent>
    </Tabs>
  )
}

// ─── 装配（Layout 与框架来自 ../shared/shell）──────────────────

export function VariantD() {
  const [view, setView] = React.useState<DevView>({ kind: "workbench" })
  const [seconds, setSeconds] = React.useState(RUN.startedSecondsAgo)
  React.useEffect(() => {
    const t = setInterval(() => setSeconds((s) => s + 1), 1000)
    return () => clearInterval(t)
  }, [])

  if (view.kind === "list") {
    return (
      <PortalPage sidebar={devSidebar(setView, view)}>
        <DevProjectListPage onOpen={() => setView({ kind: "workbench" })} />
      </PortalPage>
    )
  }

  return (
    <PortalWorkbench
      sidebar={devSidebar(setView, view)}
      header={
        <>
          <span className="text-sm font-semibold">{PROJECT.name}</span>
          <Badge variant="secondary" className="h-5">
            {PROJECT.stage} · {PROJECT.role}
          </Badge>
          <div className="mx-2 hidden items-center gap-0.5 text-xs text-muted-foreground lg:flex">
            {PROJECT.steps.map((s, i) => (
              <React.Fragment key={s}>
                {i > 0 && <ChevronRight className="size-3" />}
                <span className={cn(i === PROJECT.currentStepIndex && "font-medium text-foreground")}>
                  {s}
                </span>
              </React.Fragment>
            ))}
          </div>
          {/* 运行状态（关了浏览器它也在跑，回来看直播） */}
          <span className="ml-auto hidden items-center gap-1.5 rounded-full border border-red-500/40 bg-red-500/10 px-2.5 py-0.5 sm:flex">
            <Spinner className="size-3 text-red-500" />
            <span className="font-mono text-xs tabular-nums text-red-600 dark:text-red-400">
              {formatElapsed(seconds)}
            </span>
          </span>
          <Button
            size="xs"
            variant="ghost"
            className="text-destructive hover:bg-destructive/10 hover:text-destructive"
            onClick={() => toast.error("任务已终止（罐头）")}
          >
            <X className="size-3" /> 终止
          </Button>
          <Button size="sm" variant="outline" onClick={() => toast.success("已创建测试任务草稿（罐头）")}>
            <FlaskConical className="size-3.5" /> 发测试任务
          </Button>
        </>
      }
      left={<AgentPanel />}
      center={({ rightOpen, toggleRight }) => (
        <WorkspacePanel
          onOpenStandalone={() => window.open("/prototype/preview", "_blank")}
          headerExtra={
            <RightPanelToggle open={rightOpen} onClick={toggleRight} label="阶段面板" />
          }
        />
      )}
      right={<StagePanel />}
      mobileTabs={["对话", "工作区", "阶段"]}
    />
  )
}

// ─── 开发平台 · 项目列表页（非工作台页同壳；菜单契约与需求端一致）──

const DEV_PROJECTS = [
  {
    id: "p1",
    name: "宠物医院预约官网",
    stage: "开发",
    engine: "OpenCode",
    status: "任务执行中（LIVE · TASK-0042）· 智能体提问 1 条待答",
    tone: "primary" as const,
    updated: "刚刚",
  },
  {
    id: "p2",
    name: "鲜花电商小程序",
    stage: "测试",
    engine: "DSH",
    status: "测试任务已提交待确认 · Bug 3 条（1 待修复 / 2 待复测）",
    tone: undefined,
    updated: "昨天 17:40",
  },
  {
    id: "p3",
    name: "律所官网",
    stage: "已交付",
    engine: "OpenCode",
    status: "8 月 6 日验收交付，源码包 + DELIVERY.md 已出",
    tone: undefined,
    updated: "8 月 6 日",
  },
]

function DevProjectListPage({ onOpen }: { onOpen: () => void }) {
  return (
    <div className="mx-auto max-w-5xl p-6">
      <header className="mb-5 flex items-center gap-2">
        <SidebarTrigger className="size-7" />
        <div>
          <h1 className="text-lg font-semibold">项目列表</h1>
          <p className="text-xs text-muted-foreground">
            开发平台视角：待处理 / 进行中 / 已交付；点项目进工作台
          </p>
        </div>
      </header>
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {DEV_PROJECTS.map((p) => (
          <Card
            key={p.id}
            onClick={onOpen}
            className="cursor-pointer gap-3 py-5 transition-shadow hover:shadow-md"
          >
            <CardHeader className="gap-1.5">
              <CardTitle className="flex items-center gap-2 text-base">
                <FolderKanban className="size-4 text-muted-foreground" />
                <span className="truncate">{p.name}</span>
                <Badge
                  variant="secondary"
                  className={cn(
                    "ml-auto shrink-0",
                    p.tone === "primary" && "bg-primary/15 text-primary"
                  )}
                >
                  {p.stage}
                </Badge>
              </CardTitle>
              <CardDescription>引擎 {p.engine}</CardDescription>
            </CardHeader>
            <CardContent className="space-y-3">
              <p className="line-clamp-2 text-sm text-muted-foreground">{p.status}</p>
              <p className="text-xs text-muted-foreground/70">更新于 {p.updated}</p>
            </CardContent>
          </Card>
        ))}
      </div>
    </div>
  )
}
