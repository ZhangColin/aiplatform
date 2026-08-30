// PROTOTYPE（throwaway）—— T5 变体 D：需求端门户（定稿候选 · 融合壳）
// 主 Layout 与工作台框架来自 ../shared/shell（与 /prototype/workbench
// 同一套，本文件只给需求端的配置与面板内容）：
//   ① 新建项目（CC 首页那颗框的复刻：渐变光晕 + 大输入 + 最近项目）
//   ② 我的项目列表（B 式卡片网格，独立菜单页）
//   ③ 项目详情 = 用户自己的工作台（左与顾问对话 / 中 tabs 预览·文档 /
//      右呼出面板放项目信息与想法池）
"use client"

import * as React from "react"
import {
  ArrowRight,
  Check,
  ChevronRight,
  CircleCheck,
  ExternalLink,
  Flag,
  FolderKanban,
  LayoutGrid,
  Lightbulb,
  Plus,
  RefreshCw,
} from "lucide-react"
import { toast } from "sonner"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { ScrollArea } from "@/components/ui/scroll-area"
import { SidebarTrigger } from "@/components/ui/sidebar"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { cn } from "@/lib/utils"
import {
  PortalPage,
  PortalSidebarProps,
  PortalWorkbench,
  RightPanelToggle,
} from "../shared/shell"
import { GateBody, QuestionCardBody } from "./bits"
import {
  REQUIREMENT_ITEMS,
  statusOf,
  USER_STEPS,
  useDemoProjects,
  type DemoProject,
} from "./canned"

type View = { kind: "create" } | { kind: "list" } | { kind: "project"; id: string }

export function VariantD() {
  const demo = useDemoProjects()
  const [view, setView] = React.useState<View>({ kind: "create" })
  const project =
    view.kind === "project"
      ? (demo.projects.find((p) => p.id === view.id) ?? null)
      : null

  // 需求端 sidebar 配置（Layout 来自 shared/shell；菜单契约与开发平台
  // 一致：项目组 + 平台组[项目列表·新建项目·门户特有]，图标同款）
  const sidebar: PortalSidebarProps = {
    portal: "需求端",
    user: { name: "王女士", initial: "王" },
    groups: [
      {
        label: "项目",
        items: demo.projects.map((p) => {
          const st = statusOf(p)
          return {
            key: p.id,
            label: p.name,
            icon: <FolderKanban />,
            badge: st.tone === "wait" ? "等你" : USER_STEPS[Math.min(p.step, 5)].label,
            badgeTone: st.tone === "wait" ? ("amber" as const) : undefined,
            active: view.kind === "project" && view.id === p.id,
            onClick: () => setView({ kind: "project", id: p.id }),
          }
        }),
      },
      {
        label: "平台",
        items: [
          {
            key: "all",
            label: "项目列表",
            icon: <LayoutGrid />,
            active: view.kind === "list",
            onClick: () => setView({ kind: "list" }),
          },
          {
            key: "new",
            label: "新建项目",
            icon: <Plus />,
            active: view.kind === "create",
            onClick: () => setView({ kind: "create" }),
          },
        ],
      },
    ],
  }

  if (project) {
    return (
      <PortalWorkbench
        sidebar={sidebar}
        header={<WorkbenchHeader project={project} />}
        left={<AgentColumn demo={demo} project={project} />}
        center={({ rightOpen, toggleRight }) => (
          <WorkspaceColumn project={project} rightOpen={rightOpen} onToggleRight={toggleRight} />
        )}
        right={<InfoColumn demo={demo} project={project} />}
        mobileTabs={["对话", "预览·文档", "项目信息"]}
      />
    )
  }

  return (
    <PortalPage sidebar={sidebar}>
      {view.kind === "list" ? (
        <ProjectListPage demo={demo} onOpen={(id) => setView({ kind: "project", id })} />
      ) : (
        <CreatePage demo={demo} onOpen={(id) => setView({ kind: "project", id })} onAll={() => setView({ kind: "list" })} />
      )}
    </PortalPage>
  )
}

// ─── ① 新建项目：CC 首页那颗框的复刻 ───────────────────────────
function CreatePage({
  demo,
  onOpen,
  onAll,
}: {
  demo: ReturnType<typeof useDemoProjects>
  onOpen: (id: string) => void
  onAll: () => void
}) {
  const [text, setText] = React.useState("")
  const recent = demo.projects.slice(0, 4)

  const start = (wish: string) => {
    onOpen(demo.create(wish, wish))
  }

  return (
    <div className="mx-auto flex w-full max-w-2xl flex-col items-center px-4 py-16">
      <div className="w-full space-y-7 text-center">
        <div className="space-y-3">
          <h1 className="text-3xl font-bold tracking-tight sm:text-4xl">
            一句话，开始做出你想要的东西
          </h1>
          <p className="text-base text-muted-foreground">
            顾问陪你聊清楚需求，原型你确认，交付你验收——
            <br className="hidden sm:block" />
            关键节点你拍板，其余交给团队。
          </p>
        </div>

        {/* CC 的框：渐变光晕 + 卡式大输入 + 发送钮 */}
        <form
          className="group relative mx-auto w-full max-w-xl"
          onSubmit={(e) => {
            e.preventDefault()
            if (text.trim()) start(text.trim())
          }}
        >
          <div className="absolute -inset-0.5 rounded-2xl bg-gradient-to-r from-primary to-accent opacity-30 blur transition duration-500 group-focus-within:opacity-60" />
          <div className="relative flex items-center rounded-xl border bg-card transition-all focus-within:ring-2 focus-within:ring-primary">
            <input
              value={text}
              onChange={(e) => setText(e.target.value)}
              placeholder="描述你想做的东西，例如：给宠物医院做个在线预约的网站"
              className="flex-1 border-none bg-transparent px-6 py-5 text-base outline-none placeholder:text-muted-foreground"
            />
            <div className="pr-3">
              <Button
                type="submit"
                size="icon"
                className="size-10 rounded-lg shadow-md"
                disabled={!text.trim()}
                aria-label="开始"
              >
                <ArrowRight className="size-5" />
              </Button>
            </div>
          </div>
        </form>
      </div>

      {/* 最近的项目：少量露出，多了去列表页 */}
      <div className="mt-14 w-full max-w-xl">
        <div className="mb-2 flex items-center justify-between">
          <h2 className="text-sm font-semibold text-muted-foreground">最近的项目</h2>
          <Button size="xs" variant="ghost" onClick={onAll}>
            查看全部 <ChevronRight className="size-3" />
          </Button>
        </div>
        <ul className="divide-y rounded-xl border">
          {recent.map((p) => {
            const st = statusOf(p)
            return (
              <li key={p.id}>
                <button
                  onClick={() => onOpen(p.id)}
                  className="flex w-full items-center gap-3 px-4 py-3 text-left transition-colors hover:bg-muted/40"
                >
                  <span className="text-lg">{p.emoji}</span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-sm font-medium">{p.name}</span>
                    <span className="block truncate text-xs text-muted-foreground">
                      {st.label}
                    </span>
                  </span>
                  {st.tone === "wait" && (
                    <Badge className="bg-amber-500 text-amber-950 hover:bg-amber-500">
                      等你
                    </Badge>
                  )}
                </button>
              </li>
            )
          })}
        </ul>
      </div>
    </div>
  )
}

// ─── ② 项目列表页：B 式卡片网格（spec 0001 非工作台页规范页头）───
function ProjectListPage({
  demo,
  onOpen,
}: {
  demo: ReturnType<typeof useDemoProjects>
  onOpen: (id: string) => void
}) {
  return (
    <div className="mx-auto max-w-5xl p-6">
      <header className="mb-5 flex items-center gap-2">
        {/* 非工作台页页头（spec 0001 §2）：Trigger + 标题 + 说明 */}
        <SidebarTrigger className="size-7" />
        <div>
          <h1 className="text-lg font-semibold">我的项目</h1>
          <p className="text-xs text-muted-foreground">
            每个项目从聊需求到交付共六步，需要你拍板时会明确告诉你
          </p>
        </div>
      </header>
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {demo.projects.map((p) => {
          const st = statusOf(p)
          return (
            <Card
              key={p.id}
              onClick={() => onOpen(p.id)}
              className="cursor-pointer gap-3 py-5 transition-shadow hover:shadow-md"
            >
              <CardHeader className="gap-1.5">
                <CardTitle className="flex items-center gap-2 text-base">
                  <span className="text-xl">{p.emoji}</span>
                  <span className="truncate">{p.name}</span>
                  {st.tone === "done" && (
                    <Badge variant="secondary" className="ml-auto shrink-0">
                      已交付
                    </Badge>
                  )}
                </CardTitle>
                <CardDescription className="line-clamp-1">{p.wish}</CardDescription>
              </CardHeader>
              <CardContent className="space-y-3">
                <MiniStepper step={p.step} />
                {st.tone === "wait" ? (
                  <p className="flex items-center gap-1.5 text-sm font-medium text-amber-600 dark:text-amber-400">
                    <Flag className="size-3.5" /> 需要你：{st.label}
                  </p>
                ) : (
                  <p className="text-sm text-muted-foreground">
                    {st.label}
                    {p.eta ? <span className="text-xs"> · {p.eta}</span> : null}
                  </p>
                )}
                <p className="text-xs text-muted-foreground/70">
                  更新于 {p.log[p.log.length - 1]?.t}
                </p>
              </CardContent>
            </Card>
          )
        })}
      </div>
    </div>
  )
}

function MiniStepper({ step }: { step: number }) {
  return (
    <div className="flex items-center gap-0.5" aria-hidden>
      {USER_STEPS.map((s, i) => (
        <React.Fragment key={s.key}>
          {i > 0 && <span className="h-px flex-1 bg-border" />}
          <span
            className={cn(
              "size-2 rounded-full",
              i < step || step >= 5 ? "bg-emerald-500" : i === step ? "bg-primary" : "bg-border"
            )}
          />
        </React.Fragment>
      ))}
      <span className="ml-2 text-xs text-muted-foreground">
        {USER_STEPS[Math.min(step, 5)].label}
      </span>
    </div>
  )
}

// ─── ③ 项目详情 = 用户的工作台（框架来自 shared/shell）─────────

function WorkbenchHeader({ project }: { project: DemoProject }) {
  const st = statusOf(project)
  return (
    <>
      <span className="truncate text-sm font-semibold">
        {project.emoji} {project.name}
      </span>
      {st.tone === "wait" ? (
        <Badge className="h-5 bg-amber-500 text-amber-950 hover:bg-amber-500">
          {USER_STEPS[Math.min(project.step, 5)].label} · 等你
        </Badge>
      ) : (
        <Badge variant="secondary" className="h-5">
          {USER_STEPS[Math.min(project.step, 5)].label}
        </Badge>
      )}
      <div className="mx-2 hidden items-center gap-0.5 text-xs text-muted-foreground lg:flex">
        {USER_STEPS.map((s, i) => (
          <React.Fragment key={s.key}>
            {i > 0 && <ChevronRight className="size-3" />}
            <span
              className={cn(
                i === Math.min(project.step, 5) && "font-medium text-foreground"
              )}
            >
              {s.label}
            </span>
          </React.Fragment>
        ))}
      </div>
      <Button
        size="xs"
        variant="ghost"
        className="ml-auto"
        onClick={() => window.open("/prototype/preview", "_blank")}
      >
        <ExternalLink className="size-3" /> 浏览器打开
      </Button>
    </>
  )
}

// 左栏：与顾问的对话（问答卡 / 门卡嵌流底）
function AgentColumn({
  demo,
  project,
}: {
  demo: ReturnType<typeof useDemoProjects>
  project: DemoProject
}) {
  const [msgs, setMsgs] = React.useState<{ role: "user" | "advisor"; text: string }[]>([])
  const [input, setInput] = React.useState("")
  const st = statusOf(project)

  const send = () => {
    const text = input.trim()
    if (!text) return
    setMsgs((m) => [
      ...m,
      { role: "user", text },
      { role: "advisor", text: "收到！我记下了，会整理进需求；有疑问随时问我。" },
    ])
    setInput("")
  }

  return (
    <div className="flex h-full min-h-0 flex-col">
      <ScrollArea className="min-h-0 flex-1">
        <div className="mx-auto max-w-xl space-y-3.5 p-4">
          <Pill>项目「{project.name}」创建 · {project.wish}</Pill>
          <Bubble>
            你好，我是你的项目顾问小艾 👋 有什么想做的直接在下面说；
            需要你拍板的事，我会把卡片直接放在这里。
          </Bubble>
          {project.log.map((e, i) => (
            <Pill key={i}>{e.t} · {e.text}</Pill>
          ))}
          {msgs.map((m, i) => (
            <Bubble key={i} user={m.role === "user"}>
              {m.text}
            </Bubble>
          ))}
          {project.question && (
            <QuestionCardBody
              data={project.question}
              onDone={() => demo.answer(project.id)}
              compact
            />
          )}
          {project.gate && (
            <GateBody
              gate={project.gate}
              projectName={project.name}
              onApprove={() => demo.approve(project.id)}
              onReject={(r) => demo.reject(project.id, r)}
              onIdea={(t) => demo.addIdea(project.id, t)}
            />
          )}
          {!project.question && !project.gate && project.step < 5 && (
            <div className="flex items-center gap-2.5 rounded-lg border bg-muted/30 px-3 py-2.5 text-sm text-muted-foreground">
              <span className="relative flex size-2.5">
                <span className="absolute inline-flex size-full animate-ping rounded-full bg-emerald-400 opacity-60" />
                <span className="relative inline-flex size-2.5 rounded-full bg-emerald-500" />
              </span>
              {st.label}，不用你操作
              {project.eta ? <span>（{project.eta}）</span> : null}
            </div>
          )}
          {project.step >= 5 && (
            <Card className="border-emerald-500/40">
              <CardContent className="space-y-3 py-4">
                <p className="text-sm font-semibold text-emerald-600 dark:text-emerald-400">
                  🎉 项目已交付，源码包和使用说明书在中间「文档」页随时可取
                </p>
              </CardContent>
            </Card>
          )}
        </div>
      </ScrollArea>
      <div className="border-t p-3">
        <div className="flex gap-2">
          <Input
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && send()}
            placeholder={
              project.step >= 5
                ? "新想法会记到下一期，想做了随时说…"
                : "补充需求或提问（改动请说明原因，顾问会跟你确认）…"
            }
            className="h-10"
          />
          <Button size="sm" disabled={!input.trim()} onClick={send}>
            发送
          </Button>
        </div>
      </div>
    </div>
  )
}

function Pill({ children }: { children: React.ReactNode }) {
  return (
    <p className="mx-auto w-fit max-w-full rounded-full bg-muted px-3 py-1 text-center text-xs text-muted-foreground">
      {children}
    </p>
  )
}

function Bubble({
  user,
  children,
}: {
  user?: boolean
  children: React.ReactNode
}) {
  return (
    <div
      className={cn(
        "max-w-[88%] rounded-2xl px-4 py-2.5 text-sm leading-relaxed",
        user
          ? "ml-auto rounded-br-md bg-primary text-primary-foreground"
          : "rounded-bl-md border bg-background"
      )}
    >
      {!user && <p className="mb-1 text-xs font-medium text-primary">顾问小艾</p>}
      {children}
    </div>
  )
}

// 中栏：预览 / 文档 tabs（工具条放共享的右栏开关）
function WorkspaceColumn({
  project,
  rightOpen,
  onToggleRight,
}: {
  project: DemoProject
  rightOpen: boolean
  onToggleRight: () => void
}) {
  const [refreshed, setRefreshed] = React.useState(false)
  const demo = project.step <= 1 ? "原型" : "系统"

  return (
    <Tabs defaultValue="preview" className="flex h-full min-h-0 flex-col">
      <div className="flex h-10 shrink-0 items-center gap-1 border-b px-2">
        <TabsList className="h-7">
          <TabsTrigger value="preview" className="text-xs">预览</TabsTrigger>
          <TabsTrigger value="docs" className="text-xs">文档</TabsTrigger>
        </TabsList>
        <div className="ml-auto flex items-center">
          <RightPanelToggle open={rightOpen} onClick={onToggleRight} label="项目面板" />
        </div>
      </div>

      <TabsContent value="preview" className="min-h-0 flex-1">
        <div className="flex h-full flex-col">
          {!refreshed ? (
            <button
              onClick={() => {
                setRefreshed(true)
                toast.success("已刷新到最新版本")
              }}
              className="flex shrink-0 items-center justify-center gap-1.5 border-b bg-primary/5 py-1.5 text-xs text-primary"
            >
              <RefreshCw className="size-3" /> 有更新 · 点击刷新
            </button>
          ) : null}
          <div className="flex shrink-0 items-center gap-2 border-b bg-muted/50 px-3 py-1.5">
            <span className="flex gap-1">
              <i className="size-2 rounded-full bg-red-400" />
              <i className="size-2 rounded-full bg-amber-400" />
              <i className="size-2 rounded-full bg-emerald-400" />
            </span>
            <span className="truncate text-xs text-muted-foreground">
              preview.local/{project.name}
            </span>
            <Badge variant="secondary" className="ml-auto h-4 px-1.5 text-[10px]">
              {demo}演示
            </Badge>
          </div>
          <div className="flex-1 overflow-y-auto bg-background p-8">
            <div className="mx-auto max-w-md space-y-4 text-center">
              <p className="text-4xl">{project.emoji}</p>
              <h3 className="text-xl font-bold">{project.name}</h3>
              <p className="text-sm text-muted-foreground">
                这里是{demo}的真实页面位置——看长相、点按钮，都在这里。
              </p>
              <div className="rounded-lg border p-4 text-left text-sm">
                <p className="mb-2 font-medium">在线预约</p>
                <div className="space-y-2">
                  <div className="h-8 rounded border bg-muted/40" />
                  <div className="h-8 rounded border bg-muted/40" />
                  <div className="h-8 w-24 rounded bg-primary/80" />
                </div>
              </div>
            </div>
          </div>
        </div>
      </TabsContent>

      <TabsContent value="docs" className="min-h-0 flex-1">
        <ScrollArea className="h-full">
          <article className="mx-auto max-w-2xl space-y-5 p-6 text-sm leading-relaxed">
            <header className="space-y-1 border-b pb-3">
              <h2 className="text-lg font-semibold">
                {project.step >= 5 ? "交付说明" : "PRD"}
              </h2>
              <p className="text-xs text-muted-foreground">
                {project.step >= 5
                  ? "交付物清单与使用说明（DELIVERY.md）"
                  : "顾问整理 · 随对话更新，你确认过的都会记在这里"}
              </p>
            </header>
            {project.step >= 5 ? (
              <div className="space-y-3">
                <p className="font-medium">你拿到的东西</p>
                <ul className="list-disc space-y-1 pl-5 text-muted-foreground">
                  <li>源码包（zip）：系统的全部代码，可交给任何团队继续维护</li>
                  <li>使用说明书：怎么跑起来、各功能在哪、常见问题</li>
                </ul>
                <div className="flex gap-2 pt-2">
                  <Button size="sm" variant="secondary" disabled>
                    下载源码包
                  </Button>
                  <Button size="sm" variant="secondary" disabled>
                    查看说明书
                  </Button>
                </div>
                <p className="pt-2 text-xs text-muted-foreground">
                  （演示原型：按钮置灰示意，真实交付物由后端提供下载）
                </p>
              </div>
            ) : (
              <div className="space-y-3">
                <p className="font-medium">要做的东西</p>
                <p className="text-muted-foreground">「{project.wish}」</p>
                <p className="pt-2 font-medium">功能清单</p>
                <ul className="space-y-1.5">
                  {REQUIREMENT_ITEMS.map((item) => (
                    <li key={item} className="flex gap-2 text-muted-foreground">
                      <CircleCheck className="mt-0.5 size-4 shrink-0 text-emerald-500" />
                      {item}
                    </li>
                  ))}
                </ul>
                <p className="pt-2 text-xs text-muted-foreground">
                  PRD 会在「对话」里等你确认后才生效；改动随时说，会记入 PRD。
                </p>
              </div>
            )}
          </article>
        </ScrollArea>
      </TabsContent>
    </Tabs>
  )
}

// 右栏（呼出）：项目信息 + 旅程 + 下一期想法池
function InfoColumn({
  demo,
  project,
}: {
  demo: ReturnType<typeof useDemoProjects>
  project: DemoProject
}) {
  const [idea, setIdea] = React.useState("")

  return (
    <ScrollArea className="h-full">
      <div className="space-y-4 p-4">
        <Card className="gap-3 py-4">
          <CardHeader>
            <CardTitle className="text-sm">项目信息</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2 text-sm">
            <p className="text-muted-foreground">当初你说：「{project.wish}」</p>
            <div className="flex justify-between text-muted-foreground">
              当前环节
              <span className="text-foreground">
                {USER_STEPS[Math.min(project.step, 5)].label}
              </span>
            </div>
            <div className="flex justify-between text-muted-foreground">
              最新更新
              <span className="text-foreground">
                {project.log[project.log.length - 1]?.t}
              </span>
            </div>
          </CardContent>
        </Card>

        <Card className="gap-3 py-4">
          <CardHeader>
            <CardTitle className="text-sm">旅程</CardTitle>
            <CardDescription>
              共 6 步，第 {Math.min(project.step + 1, 6)} 步
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-1.5">
            {USER_STEPS.map((s, i) => (
              <p
                key={s.key}
                className={cn(
                  "flex items-center gap-2 text-sm",
                  i > project.step && "text-muted-foreground/50"
                )}
              >
                <span
                  className={cn(
                    "grid size-4.5 place-items-center rounded-full text-[10px]",
                    i < project.step || project.step >= 5
                      ? "bg-emerald-500 text-white"
                      : i === project.step
                        ? "bg-primary/15 font-semibold text-primary"
                        : "bg-muted text-muted-foreground"
                  )}
                >
                  {i < project.step || project.step >= 5 ? <Check className="size-3" /> : i + 1}
                </span>
                {s.label}
                {i === project.step && (
                  <span className="text-xs text-muted-foreground">← 现在</span>
                )}
              </p>
            ))}
          </CardContent>
        </Card>

        <Card className="gap-3 py-4">
          <CardHeader>
            <CardTitle className="flex items-center gap-1.5 text-sm">
              <Lightbulb className="size-4 text-amber-500" /> 下一期想法池
            </CardTitle>
            <CardDescription>随时记，不打扰当前项目</CardDescription>
          </CardHeader>
          <CardContent className="space-y-2">
            {project.ideas.length === 0 && (
              <p className="text-xs text-muted-foreground">还没有记录</p>
            )}
            {project.ideas.map((x) => (
              <p key={x} className="rounded-md bg-muted/60 px-2 py-1.5 text-sm">{x}</p>
            ))}
            <div className="flex gap-2 pt-1">
              <Input
                value={idea}
                onChange={(e) => setIdea(e.target.value)}
                placeholder="记一个新想法…"
                className="h-8 text-sm"
                onKeyDown={(e) => {
                  if (e.key === "Enter" && idea.trim()) {
                    demo.addIdea(project.id, idea.trim())
                    setIdea("")
                  }
                }}
              />
              <Button
                size="sm"
                variant="ghost"
                disabled={!idea.trim()}
                onClick={() => {
                  demo.addIdea(project.id, idea.trim())
                  setIdea("")
                }}
              >
                记下
              </Button>
            </div>
          </CardContent>
        </Card>
      </div>
    </ScrollArea>
  )
}
