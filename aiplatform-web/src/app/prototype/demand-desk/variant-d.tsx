// PROTOTYPE（throwaway）—— wayfinder #6 第三轮：指令区 + 成果区
// 结构主张（2026-08-30 用户反馈）：
//   左 = 指令区（对话，无标题）；右 = 成果区，内分主区域 + 可收展侧栏，
//   三种模式：文件（主=文件内容+操作，侧=文件树缩进展开）/ 系统（主=预览，
//   生成时自动切来且侧栏自动展开播直播，结束自动收起）/ 项目（订单·用量排版）。
//   闲聊期无产物 → 成果区整个不出现，指令区占满。
//   状态徽章全清（等你回答/旅程/待定等）；门户下拉出局；三栏 resizable +
//   窄屏三页签退化保留。大展示布局就三类：指令+成果 / 纯信息展示（列表页、
//   后台，前端重组票覆盖）/ 本页。
"use client"

import * as React from "react"
import { Home, LayoutGrid, PanelLeftIcon, PanelRightClose, PanelRightOpen } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
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
  SidebarHeader,
  SidebarInset,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarProvider,
  useSidebar,
} from "@/components/ui/sidebar"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { cn } from "@/lib/utils"

import { systemReady, useDesk, visibleFiles } from "./canned"
import {
  ChatFlow,
  CodeView,
  Composer,
  FileTree,
  KnowledgeBadge,
  LiveSide,
  MutedBanner,
  OrderCard,
  PreviewBlank,
  PreviewFrame,
  PrdDoc,
  UsageCard,
} from "./bits"

const IDEAS = ["提交按钮改成绿色", "加个会员积分功能"]

type Mode = "files" | "system" | "project"

const MODE_TABS: { key: Mode; label: string }[] = [
  { key: "files", label: "文件" },
  { key: "system", label: "系统" },
  { key: "project", label: "项目" },
]

export function VariantD() {
  const desk = useDesk()
  const { s } = desk

  // 模式默认跟随状态（渲染期派生）：run 起动看系统+侧栏直播、PRD 就绪看文档；
  // 用户手切则让位，直到下一个自动事件再接管
  const autoMode = s.run.active ? "system" : s.phase === "prdReady" ? "files" : null
  const [manualMode, setManualMode] = React.useState<Mode | null>(null)
  const [manualRight, setManualRight] = React.useState<boolean | null>(null)
  const [seenAuto, setSeenAuto] = React.useState<string | null>(null)
  const autoKey = `${autoMode ?? ""}|${s.run.active}`
  if (autoKey !== seenAuto) {
    setSeenAuto(autoKey)
    setManualMode(null)
    setManualRight(null)
  }
  const mode: Mode = manualMode ?? autoMode ?? (systemReady(s.phase) ? "system" : "files")
  // 侧栏：文件=文件树 / 项目=明细 / 系统=直播（定盘：直播在右侧栏，
  // run 开始自动展开、结束自动收起；对话流不流式不留痕）
  const sideOf = (m: Mode): string | null =>
    m === "files" ? "文件树" : m === "project" ? "明细" : "直播"
  const hasSide = sideOf(mode) !== null
  const rightOpen = hasSide && (manualRight ?? (mode === "system" && s.run.active))

  // 文件模式当前查看的文件（默认需求文档）
  const [activeFile, setActiveFile] = React.useState("docs/需求文档.md")

  // 闲聊期无任何产物 → 成果区不出现，指令区占满
  const hasArtifacts = s.phase !== "chat"
  const files = visibleFiles(s.systemVersion, hasArtifacts)
  const sideLabel = sideOf(mode) ?? ""

  // PRD 更新胶囊的「已读」标记（需求变更后提示一次）
  const [ackRev, setAckRev] = React.useState(s.prd.rev)
  const prdUpdated = s.prd.rev > ackRev && s.phase !== "chat"

  const commandArea = (
    <ChatColumn
      desk={desk}
      full={!hasArtifacts}
      prdUpdated={prdUpdated}
      onAckPrd={() => {
        setAckRev(s.prd.rev)
        setManualMode("files")
      }}
    />
  )

  const rightPane = <RightPane mode={mode} desk={desk} files={files} activeFile={activeFile} onSelectFile={setActiveFile} />
  const mainPane = <MainPane desk={desk} mode={mode} activeFile={activeFile} />
  const artifactArea = (
    <Tabs
      value={mode}
      onValueChange={(v) => setManualMode(v as Mode)}
      className="flex h-full min-h-0 flex-col gap-0"
    >
      <div className="flex h-10 shrink-0 items-center gap-1 border-b px-2">
        <TabsList variant="line" className="h-8">
          {MODE_TABS.map((t) => (
            <TabsTrigger key={t.key} value={t.key}>
              {t.label}
              {t.key === "system" && s.run.active ? (
                <span className="ml-1 size-1.5 animate-pulse rounded-full bg-red-500" />
              ) : null}
            </TabsTrigger>
          ))}
        </TabsList>
        <div className="ml-auto flex items-center">
          {hasSide ? (
            <Button
              size="xs"
              variant="ghost"
              aria-label={rightOpen ? `收起${sideLabel}` : `展开${sideLabel}`}
              onClick={() => setManualRight(!rightOpen)}
              className="hidden lg:inline-flex"
            >
              {rightOpen ? <PanelRightClose className="size-3.5" /> : <PanelRightOpen className="size-3.5" />}
            </Button>
          ) : null}
        </div>
      </div>
      {/* 文件/项目（及偏好「右侧」时的系统）：主区域 + 可收展侧栏；系统（对话内直播）主区域即预览 */}
      {MODE_TABS.map((t) => {
        const tSide = sideOf(t.key)
        return (
        <TabsContent key={t.key} value={t.key} className="mt-0 min-h-0 flex-1">
          {t.key === mode ? (
            tSide === null ? (
              <div className="h-full min-h-0">{mainPane}</div>
            ) : (
              <>
                <div className="hidden h-full min-h-0 lg:block">
                  <ResizablePanelGroup id={`artifacts-${t.key}`} orientation="horizontal" className="h-full">
                    <ResizablePanel id="main" minSize={280}>{mainPane}</ResizablePanel>
                    {rightOpen ? (
                      <>
                        <ResizableHandle />
                        <ResizablePanel id="side" defaultSize={340} minSize={240}>
                          <div className="h-full border-l">{rightPane}</div>
                        </ResizablePanel>
                      </>
                    ) : null}
                  </ResizablePanelGroup>
                </div>
                {/* 窄屏：主区域 / 侧栏 由内层页签切 */}
                <div className="flex h-full min-h-0 lg:hidden">
                  <MobilePane main={mainPane} side={rightPane} sideLabel={tSide ?? ""} paneKey={t.key} />
                </div>
              </>
            )
          ) : null}
        </TabsContent>
        )
      })}
    </Tabs>
  )

  return (
    <SidebarProvider>
      <ProtoSidebar />
      {/* SidebarInset 提供 flex-1 w-full——普通 div 在 sidebar wrapper 的 flex
          布局里按内容收缩（「只占一半」的根因），不可省 */}
      <SidebarInset className="flex h-svh min-h-0 flex-col">
        {/* 顶栏：项目名 + LIVE（run 进行中）；直播已定盘在右侧栏，无偏好开关 */}
        <header className="flex h-12 shrink-0 items-center gap-2 border-b bg-background px-3">
          <span className="truncate text-sm font-semibold">宠物医院预约官网</span>
          <div className="ml-auto flex shrink-0 items-center gap-2">
            {s.run.active ? <LivePill /> : null}
          </div>
        </header>

        {hasArtifacts ? (
          <>
            {/* lg：指令区 | 成果区 两栏 resizable（v4 数字=像素，对齐真实壳 380/320） */}
            <div className="hidden min-h-0 flex-1 lg:block">
              <ResizablePanelGroup orientation="horizontal" className="h-full">
                <ResizablePanel defaultSize={400} minSize={320}>
                  <div className="h-full border-r">{commandArea}</div>
                </ResizablePanel>
                <ResizableHandle />
                <ResizablePanel minSize={360}>{artifactArea}</ResizablePanel>
              </ResizablePanelGroup>
            </div>
            {/* 窄屏：对话 / 成果 两页签 */}
            <Tabs defaultValue="chat" className="flex min-h-0 flex-1 flex-col lg:hidden">
              <TabsList className="m-2 grid grid-cols-2">
                <TabsTrigger value="chat">对话</TabsTrigger>
                <TabsTrigger value="artifacts">成果</TabsTrigger>
              </TabsList>
              <TabsContent value="chat" className="mt-0 min-h-0 flex-1">
                {commandArea}
              </TabsContent>
              <TabsContent value="artifacts" className="mt-0 min-h-0 flex-1">
                {artifactArea}
              </TabsContent>
            </Tabs>
          </>
        ) : (
          // 闲聊期：指令区占满（无成果区）
          <div className="flex min-h-0 flex-1">{commandArea}</div>
        )}
      </SidebarInset>
    </SidebarProvider>
  )
}

// ── 左：门户菜单（原 PortalSidebar 形态：缩进 + 图标收起条；门户下拉已出局）──

function ProtoSidebar() {
  const { toggleSidebar } = useSidebar()
  return (
    <Sidebar collapsible="icon">
      <SidebarHeader className="relative z-10">
        <SidebarMenu>
          <SidebarMenuItem>
            <div className="flex w-full items-center gap-1">
              <SidebarMenuButton size="lg" className="min-w-0 flex-1 gap-2">
                <span className="flex size-6 shrink-0 items-center justify-center rounded-md bg-primary text-xs font-bold text-primary-foreground">
                  AI
                </span>
                <span className="truncate text-sm font-semibold">AI 开发平台</span>
              </SidebarMenuButton>
              <Button
                variant="ghost"
                size="icon-sm"
                aria-label="收起菜单"
                onClick={toggleSidebar}
                className="shrink-0 text-muted-foreground"
              >
                <PanelLeftIcon />
              </Button>
            </div>
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarHeader>
      <SidebarContent>
        <SidebarGroup>
          <SidebarGroupContent>
            <SidebarMenu>
              <SidebarMenuItem>
                <SidebarMenuButton size="sm">
                  <Home className="size-4" />
                  <span className="truncate">首页</span>
                </SidebarMenuButton>
              </SidebarMenuItem>
              <SidebarMenuItem>
                <SidebarMenuButton size="sm" isActive>
                  <LayoutGrid className="size-4" />
                  <span className="truncate">我的项目</span>
                </SidebarMenuButton>
                {/* 当前项目（缩进展开：菜单下的项目行） */}
                <div className="mt-0.5 ml-6 flex items-center gap-1.5 rounded-md px-2 py-1.5 text-xs text-muted-foreground">
                  <span className="text-sm">🐶</span>
                  <span className="truncate">宠物医院预约官网</span>
                  <span className="ml-auto size-1.5 shrink-0 rounded-full bg-emerald-500" />
                </div>
              </SidebarMenuItem>
            </SidebarMenu>
          </SidebarGroupContent>
        </SidebarGroup>
      </SidebarContent>
      <SidebarFooter className="relative z-10">
        <div className="flex items-center gap-2 px-2 py-1 text-xs text-muted-foreground">
          <span className="flex size-6 items-center justify-center rounded-full bg-muted font-medium">原</span>
          原型用户
        </div>
      </SidebarFooter>
    </Sidebar>
  )
}

/** 顶栏 LIVE 脉冲 + 计时（run 进行中才渲染；纯 tick 计数） */
function LivePill() {
  const [sec, tick] = React.useReducer((x: number) => x + 1, 0)
  React.useEffect(() => {
    const t = setInterval(tick, 1000)
    return () => clearInterval(t)
  }, [])
  const text = `${Math.floor(sec / 60)}:${String(sec % 60).padStart(2, "0")}`
  return (
    <span className="flex items-center gap-2 rounded-full border border-red-500/40 bg-red-500/10 px-2.5 py-1">
      <span className="relative flex size-2">
        <span className="absolute inline-flex size-full animate-ping rounded-full bg-red-500 opacity-60" />
        <span className="relative inline-flex size-2 rounded-full bg-red-500" />
      </span>
      <span className="text-xs font-semibold text-red-600 dark:text-red-400">LIVE</span>
      <span className="font-mono text-xs tabular-nums text-red-600 dark:text-red-400">{text}</span>
    </span>
  )
}

// ── 左栏：指令区（对话；无标题）──────────────────────────────────

function ChatColumn({
  desk,
  full,
  prdUpdated,
  onAckPrd,
}: {
  desk: ReturnType<typeof useDesk>
  /** 闲聊期指令区占满全宽 */
  full?: boolean
  prdUpdated: boolean
  onAckPrd: () => void
}) {
  return (
    <div className={cn("flex h-full min-h-0 flex-col", full && "w-full")}>
      <ChatFlow desk={desk} />
      {prdUpdated ? (
        <div className="flex shrink-0 justify-center border-t bg-background px-3 pt-2">
          <Button size="sm" variant="secondary" className="text-amber-600" onClick={onAckPrd}>
            需求文档有更新 · 去看看
          </Button>
        </div>
      ) : null}
      {systemReady(desk.s.phase) && desk.s.order.state === "none" ? (
        // 确认下单常驻（#3 决议「指令区」位；完整流程在「项目」模式）
        <div className="flex shrink-0 justify-end border-t bg-background px-3 py-1.5">
          <Button size="sm" variant="ghost" onClick={desk.confirmOrder}>
            确认下单
          </Button>
        </div>
      ) : null}
      <Composer desk={desk} onOpinion={desk.opinion} quickIdeas={IDEAS} />
    </div>
  )
}

// ── 成果区：主区域（按模式）──────────────────────────────────────

function MainPane({
  desk,
  mode,
  activeFile,
}: {
  desk: ReturnType<typeof useDesk>
  mode: Mode
  activeFile: string
}) {
  const { s } = desk
  if (mode === "files") {
    const isDoc = activeFile === "docs/需求文档.md"
    const file = visibleFiles(s.systemVersion, true).find((f) => f.path === activeFile)
    return (
      <div className="flex h-full min-h-0 flex-col">
        {/* 文档就绪时的操作条（主区域带操作：确认 → 开始做系统） */}
        {isDoc && s.phase === "prdReady" ? (
          <div className="flex shrink-0 items-center justify-between gap-3 border-b bg-amber-500/5 px-4 py-2.5">
            <span className="text-xs text-muted-foreground">文档已就绪，确认后开始做系统</span>
            <Button size="sm" onClick={desk.finalize}>
              开始做系统
            </Button>
          </div>
        ) : null}
        <div className="min-h-0 flex-1 overflow-y-auto">
          {isDoc ? (
            <article className="mx-auto max-w-2xl space-y-5 p-6">
              <PrdDoc prd={s.prd} writing={s.phase === "chat"} />
              <div className="border-t pt-3">
                <KnowledgeBadge n={2} />
              </div>
            </article>
          ) : file ? (
            <CodeView file={file} />
          ) : (
            <div className="p-6">
              <MutedBanner>这个文件还没生成；系统做好后会出现在文件树里。</MutedBanner>
            </div>
          )}
        </div>
      </div>
    )
  }
  if (mode === "system") {
    // 主区域恒为预览（2026-08-30 定调）：生成中 = 空白浏览器窗 + 一句话，
    // 不做进度状态切换——过程在指令区直播；修正中系统保持可见，轻提示。
    if (s.phase === "generating") {
      return <PreviewBlank note="正在为您生成系统，过程见左侧对话" />
    }
    return (
      <div className="relative h-full min-h-0">
        <PreviewFrame version={s.systemVersion} showChrome={false} />
        {s.run.active && s.phase === "fixing" ? (
          <div className="absolute bottom-3 left-1/2 -translate-x-1/2 rounded-full bg-neutral-900/85 px-3.5 py-1.5 text-[11px] text-neutral-200 shadow-lg">
            <span className="mr-1.5 inline-block size-1.5 animate-pulse rounded-full bg-emerald-400" />
            按您的意见修改中 · 完成后这里自动刷新
          </div>
        ) : null}
      </div>
    )
  }
  // 项目模式：项目 / 订单 / 用量排版
  return (
    <div className="h-full overflow-y-auto">
      <div className="mx-auto max-w-2xl space-y-4 p-6">
        <Card className="gap-2 py-4">
          <CardHeader className="px-4">
            <CardTitle className="flex items-center gap-2 text-sm">
              🐶 宠物医院预约官网
              <Badge variant="outline" className="text-[10px] text-muted-foreground">
                {s.phase === "paid" ? "已完成" : "进行中"}
              </Badge>
            </CardTitle>
          </CardHeader>
          <CardContent className="px-4 text-xs leading-relaxed text-muted-foreground">
            「想给宠物医院做个能在线预约的网站」——需求聊清楚后自动制作，改到满意再下单。
          </CardContent>
        </Card>
        <OrderCard desk={desk} />
        <UsageCard desk={desk} />
      </div>
    </div>
  )
}

// ── 成果区：可收展侧栏（按模式）─────────────────────────────────

function RightPane({
  mode,
  desk,
  files,
  activeFile,
  onSelectFile,
}: {
  mode: Mode
  desk: ReturnType<typeof useDesk>
  files: ReturnType<typeof visibleFiles>
  activeFile: string
  onSelectFile: (path: string) => void
}) {
  if (mode === "files") {
    return <FileTree files={files} active={activeFile} onSelect={onSelectFile} />
  }
  if (mode === "system") {
    // 仅偏好「右侧」时存在：直播条目列表（进行中 / 回看）
    return <LiveSide desk={desk} />
  }
  // 项目模式侧栏 = 用量 + 经验参考
  return (
    <div className="h-full overflow-y-auto">
      <div className="space-y-4 p-3">
        <UsageCard desk={desk} />
        <div className="rounded-xl border p-3">
          <div className="mb-2 text-xs font-medium text-muted-foreground">经验参考</div>
          <KnowledgeBadge n={2} />
        </div>
      </div>
    </div>
  )
}

/** 窄屏成果区内部：主区域 / 侧栏 二段小页签 */
function MobilePane({
  main,
  side,
  sideLabel,
  paneKey,
}: {
  main: React.ReactNode
  side: React.ReactNode
  sideLabel: string
  paneKey: string
}) {
  return (
    <Tabs defaultValue={`${paneKey}:main`} className="h-full min-h-0 w-full gap-0">
      <TabsList className="m-2 grid w-auto grid-cols-2">
        <TabsTrigger value={`${paneKey}:main`}>内容</TabsTrigger>
        <TabsTrigger value={`${paneKey}:side`}>{sideLabel}</TabsTrigger>
      </TabsList>
      <TabsContent value={`${paneKey}:main`} className="mt-0 min-h-0 flex-1">
        {main}
      </TabsContent>
      <TabsContent value={`${paneKey}:side`} className="mt-0 min-h-0 flex-1">
        {side}
      </TabsContent>
    </Tabs>
  )
}
