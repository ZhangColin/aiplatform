// PROTOTYPE（throwaway）—— Variant C · 决策收件箱（待办中心）
// 第一屏 =「等你拍板」的决策队列（问答 / 审批 / 任务确认 / 门，大卡纵排，
// 每张卡自带来源上下文）；agent 直播退为右侧只读细流；项目上下文在左栏。
// 队列空 → 「一切自动运行中」。<1024px：单列，队列优先。
"use client"

import * as React from "react"
import { toast } from "sonner"
import {
  Check,
  ChevronRight,
  CircleAlert,
  DoorOpen,
  Eye,
  FlaskConical,
  Inbox,
  Lock,
  Send,
  SquareTerminal,
  X,
} from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group"
import { ScrollArea } from "@/components/ui/scroll-area"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Separator } from "@/components/ui/separator"
import { Sheet, SheetContent, SheetTitle, SheetTrigger } from "@/components/ui/sheet"
import { Spinner } from "@/components/ui/spinner"
import { cn } from "@/lib/utils"
import {
  APPROVAL,
  FEED,
  GATE,
  PREVIEW_IFRAME_PROPS,
  PREVIEW_SRCDOC,
  PROJECT,
  QUESTIONS,
  RUN,
  TEST_TASK,
  formatElapsed,
} from "./canned"

// —— 队列卡通用壳：类型 + 来源 + 时间，处理后收成一行 ——
// children 是 render prop：拿到 resolve()，动作按钮自行决定何时收卡。

function QueueCard({
  type,
  source,
  time,
  onDone,
  children,
}: {
  type: string
  source: string
  time: string
  onDone?: () => void
  children: (resolve: () => void) => React.ReactNode
}) {
  const [done, setDone] = React.useState(false)
  if (done) {
    return (
      <div className="flex items-center gap-2 rounded-lg border border-dashed px-4 py-3 text-sm text-muted-foreground">
        <Check className="size-4 text-emerald-600" /> {type}已处理
      </div>
    )
  }
  return (
    <Card className="py-0">
      <CardContent className="p-0">
        <div className="flex items-center gap-2 border-b px-4 py-2.5 text-xs text-muted-foreground">
          <Badge variant="secondary" className="h-5">{type}</Badge>
          <span>{source}</span>
          <span className="ml-auto">{time}</span>
        </div>
        <div className="p-4">
          {children(() => {
            setDone(true)
            onDone?.()
          })}
        </div>
      </CardContent>
    </Card>
  )
}

// —— ① 问答卡 ——

export function QueueQuestion({ onDone }: { onDone?: () => void }) {
  const [singles, setSingles] = React.useState<Record<number, string>>({})
  const [multis, setMultis] = React.useState<Record<number, string[]>>({ 1: ["品种"] })
  return (
    <QueueCard type="提问" source={`${RUN.taskId} · ${PROJECT.role}`} time="5 分钟前" onDone={onDone}>
      {(resolve) => (
        <div className="space-y-4">
          <p className="text-sm font-medium">
            智能体在实现预约表单前，需要你确认两件事：
          </p>
          {QUESTIONS.map((q, qi) => (
            <div key={qi} className="space-y-2">
              <div className="flex items-baseline gap-2">
                <Label className="text-sm font-medium">{q.header}</Label>
                <span className="text-xs text-muted-foreground">{q.question}</span>
              </div>
              {q.multiple ? (
                <div className="flex flex-wrap gap-1.5">
                  {q.options.map((opt) => {
                    const checked = (multis[qi] ?? []).includes(opt.label)
                    return (
                      <label
                        key={opt.label}
                        title={opt.description}
                        className="flex cursor-pointer items-center gap-1.5 rounded-full border px-3 py-1.5 text-sm hover:bg-muted/50 has-[button[data-state=checked]]:border-primary/50 has-[button[data-state=checked]]:bg-primary/5"
                      >
                        <Checkbox
                          checked={checked}
                          onCheckedChange={() =>
                            setMultis((m) => ({
                              ...m,
                              [qi]: checked ? m[qi].filter((x) => x !== opt.label) : [...(m[qi] ?? []), opt.label],
                            }))
                          }
                        />
                        {opt.label}
                      </label>
                    )
                  })}
                </div>
              ) : (
                <RadioGroup
                  value={singles[qi] ?? ""}
                  onValueChange={(v) => setSingles((s) => ({ ...s, [qi]: v }))}
                  className="flex flex-wrap gap-1.5"
                >
                  {q.options.map((opt) => (
                    <label
                      key={opt.label}
                      title={opt.description}
                      className="flex cursor-pointer items-center gap-1.5 rounded-full border px-3 py-1.5 text-sm hover:bg-muted/50 has-[button[data-state=checked]]:border-primary/50 has-[button[data-state=checked]]:bg-primary/5"
                    >
                      <RadioGroupItem value={opt.label} className="size-3" />
                      {opt.label}
                    </label>
                  ))}
                </RadioGroup>
              )}
            </div>
          ))}
          <div className="flex justify-end">
            <Button
              size="sm"
              onClick={() => {
                toast.success("回答已提交（罐头）")
                resolve()
              }}
            >
              提交回答
            </Button>
          </div>
        </div>
      )}
    </QueueCard>
  )
}

// —— ② 审批卡 ——

export function QueueApproval({ onDone }: { onDone?: () => void }) {
  return (
    <QueueCard type="审批" source={`${RUN.taskId} · 工具执行`} time="2 分钟前" onDone={onDone}>
      {(resolve) => (
        <div className="space-y-3">
          <div className="flex flex-wrap items-center gap-2 text-sm">
            <SquareTerminal className="size-4 text-violet-600" />
            <span className="font-medium">{APPROVAL.tool}</span>
            <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-xs">{APPROVAL.args}</code>
            <span className="text-xs text-muted-foreground">
              {APPROVAL.expiresInMin} 分钟内未处理自动过期 · {APPROVAL.reason}
            </span>
          </div>
          <div className="flex justify-end gap-2">
            <Button
              size="sm"
              variant="outline"
              onClick={() => {
                toast("已拒绝（罐头）")
                resolve()
              }}
            >
              拒绝
            </Button>
            <Button
              size="sm"
              onClick={() => {
                toast.success("已允许（罐头）")
                resolve()
              }}
            >
              允许
            </Button>
            <Button
              size="sm"
              variant="ghost"
              className="text-destructive hover:bg-destructive/10 hover:text-destructive"
              onClick={() => toast.error("任务已终止（罐头）——逃生口")}
            >
              <X className="size-3.5" /> 终止任务
            </Button>
          </div>
        </div>
      )}
    </QueueCard>
  )
}

// —— ③ 测试任务确认卡（Bug 清单内嵌）——

export function QueueTestConfirm({ onDone }: { onDone?: () => void }) {
  return (
    <QueueCard type="任务确认" source={`${TEST_TASK.id} · ${TEST_TASK.assignee} 已提交`} time="昨天 16:30" onDone={onDone}>
      {(resolve) => (
        <div className="space-y-3">
          <p className="text-sm font-medium">{TEST_TASK.title}</p>
          <div className="rounded-lg border">
            {TEST_TASK.bugs.map((b, i) => (
              <div key={b.id} className={cn("flex items-center gap-2 px-3 py-2 text-sm", i > 0 && "border-t")}>
                <span className="font-mono text-xs text-muted-foreground">{b.id}</span>
                <span className="min-w-0 flex-1 truncate">{b.title}</span>
                <Badge
                  variant="outline"
                  className={cn(
                    "h-5 shrink-0 text-[10px]",
                    b.status === "待修复" && "border-amber-500/40 text-amber-700 dark:text-amber-400",
                    b.status === "已修复 · 待复测" && "border-sky-500/40 text-sky-700 dark:text-sky-400"
                  )}
                >
                  {b.status}
                </Badge>
              </div>
            ))}
          </div>
          <div className="flex flex-wrap justify-end gap-2">
            <Button
              size="sm"
              variant="outline"
              onClick={() => {
                toast("已驳回（罐头）· 退回返工（需附说明）")
                resolve()
              }}
            >
              驳返回工
            </Button>
            <Button
              size="sm"
              onClick={() => {
                toast.success("已确认（罐头）· Bug 入库并派智能体逐条修复")
                resolve()
              }}
            >
              确认 · 入库修复
            </Button>
          </div>
        </div>
      )}
    </QueueCard>
  )
}

// —— ④ 开发完成确认门（locked）——

export function QueueGate() {
  return (
    <div className="rounded-lg border px-4 py-3 opacity-80">
      <div className="flex items-center gap-2 text-sm">
        <Lock className="size-4 text-muted-foreground" />
        <DoorOpen className="size-4 text-muted-foreground" />
        <span className="font-medium">{GATE.title}</span>
        <Badge variant="secondary" className="h-5 text-[10px]">未解锁</Badge>
      </div>
      <p className="mt-1 text-xs text-muted-foreground">{GATE.lockedReason}</p>
    </div>
  )
}

// —— 空态 ——

function EmptyQueue() {
  return (
    <div className="rounded-lg border border-dashed py-16 text-center">
      <Inbox className="mx-auto size-8 text-muted-foreground/50" />
      <p className="mt-3 text-sm font-medium">一切自动运行中</p>
      <p className="mx-auto mt-1 max-w-sm text-xs text-muted-foreground">
        {RUN.taskId} · {RUN.title} —— 智能体执行中，无需你处理；右侧可围观直播
      </p>
    </div>
  )
}

// —— 左栏：项目上下文 ——

function ContextPanel() {
  return (
    <div className="space-y-4 p-4">
      <div>
        <h3 className="text-xs font-medium text-muted-foreground">项目</h3>
        <p className="mt-1 text-sm font-medium">{PROJECT.name}</p>
        <p className="text-xs text-muted-foreground">
          需求方 {PROJECT.owner} · {PROJECT.id} · 阶段「{PROJECT.stage}」
        </p>
        <p className="mt-2 line-clamp-3 rounded-md bg-muted/50 p-2.5 text-xs leading-relaxed text-muted-foreground">
          面向宠物医院的预约官网：首页介绍科室与医生，核心是预约挂号表单（手机号验证、按营业时段选时段），后台暂不做……
        </p>
      </div>
      <Separator />
      <div>
        <h3 className="text-xs font-medium text-muted-foreground">主链</h3>
        <div className="mt-2 flex flex-wrap items-center gap-1 text-xs">
          {PROJECT.steps.map((s, i) => (
            <React.Fragment key={s}>
              {i > 0 && <ChevronRight className="size-3 text-muted-foreground/50" />}
              <span
                className={cn(
                  "rounded px-1.5 py-0.5",
                  i < PROJECT.currentStepIndex && "text-muted-foreground line-through decoration-muted-foreground/40",
                  i === PROJECT.currentStepIndex && "bg-primary/10 font-medium text-primary"
                )}
              >
                {s}
              </span>
            </React.Fragment>
          ))}
        </div>
      </div>
      <Separator />
      <div className="space-y-1.5">
        <h3 className="text-xs font-medium text-muted-foreground">快捷</h3>
        <PreviewSheet />
        <Button size="sm" variant="outline" className="w-full justify-start" onClick={() => toast.success("已创建测试任务草稿（罐头）")}>
          <FlaskConical className="size-3.5" /> 发测试任务
        </Button>
      </div>
    </div>
  )
}

function PreviewSheet() {
  return (
    <Sheet>
      <SheetTrigger render={<Button size="sm" variant="outline" className="w-full justify-start" />}>
        <Eye className="size-3.5" /> 打开预览
      </SheetTrigger>
      <SheetContent className="w-[560px] p-0 sm:max-w-[560px]">
        <SheetTitle className="border-b px-4 py-3 text-sm">预览 · {PROJECT.name}</SheetTitle>
        <iframe {...PREVIEW_IFRAME_PROPS} srcDoc={PREVIEW_SRCDOC} className="size-full border-0 bg-white" />
      </SheetContent>
    </Sheet>
  )
}

// —— 右栏：只读活动细流 ——

function ActivityStream() {
  const [seconds, setSeconds] = React.useState(RUN.startedSecondsAgo)
  React.useEffect(() => {
    const t = setInterval(() => setSeconds((s) => s + 1), 1000)
    return () => clearInterval(t)
  }, [])

  interface StreamRow {
    tag: string
    text: string
    hot?: boolean
    status?: "running" | "done"
  }
  const rows: StreamRow[] = FEED.flatMap((e): StreamRow[] => {
    switch (e.kind) {
      case "task-start": return [{ tag: "start", text: e.text! }]
      case "text": return [{ tag: "text", text: e.text!.slice(0, 28) + "…" }]
      case "reasoning": return [{ tag: "think", text: "思考（12s）" }]
      case "tool": return [{ tag: e.tool!.name, text: e.tool!.arg, status: e.tool!.status }]
      case "knowledge": return [{ tag: "知识", text: `命中 ${e.knowledge!.count} 条`, hot: true }]
      case "patch": return [{ tag: "patch", text: `${e.patch!.file} +${e.patch!.added}/−${e.patch!.removed}` }]
      case "hitl": return [{ tag: "等待", text: "HITL 2 项", hot: true }]
      default: return []
    }
  })

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="space-y-1.5 border-b p-3">
        <div className="flex items-center gap-2 text-xs">
          <Spinner className="size-3.5 text-primary" />
          <span className="font-medium">{RUN.taskId}</span>
          <span className="truncate text-muted-foreground">{RUN.title}</span>
        </div>
        <div className="flex items-center gap-2 text-xs text-muted-foreground">
          <span className="font-mono tabular-nums">{formatElapsed(seconds)}</span>
          <span>{RUN.tokens}</span>
          <Button
            size="xs"
            variant="ghost"
            className="ml-auto h-6 px-1.5 text-destructive hover:bg-destructive/10 hover:text-destructive"
            onClick={() => toast.error("任务已终止（罐头）")}
          >
            <X className="size-3" /> 终止
          </Button>
        </div>
      </div>
      <ScrollArea className="min-h-0 flex-1">
        <div className="divide-y font-mono text-xs">
          {rows.map((r, i) => (
            <div key={i} className={cn("flex items-center gap-2 px-3 py-2", r.hot && "bg-indigo-500/10")}>
              <span
                className={cn(
                  "shrink-0 rounded px-1.5 py-0.5 text-[10px] font-sans",
                  r.hot
                    ? "bg-indigo-500/15 text-indigo-700 dark:text-indigo-300"
                    : "bg-muted text-muted-foreground"
                )}
              >
                {r.tag}
              </span>
              <span className="min-w-0 flex-1 truncate text-muted-foreground">{r.text}</span>
              {r.status === "running" ? (
                <Spinner className="size-3 shrink-0 text-primary" />
              ) : r.status === "done" ? (
                <Check className="size-3 shrink-0 text-muted-foreground" />
              ) : null}
            </div>
          ))}
        </div>
      </ScrollArea>
    </div>
  )
}

// —— 装配 ——

export function VariantC() {
  const [resolved, setResolved] = React.useState(0)
  const [draft, setDraft] = React.useState("")
  const pendingCount = Math.max(0, 3 - resolved) // 提问/审批/任务确认
  const mark = React.useCallback(() => setResolved((r) => r + 1), [])

  return (
    <div className="flex h-svh flex-col">
      <header className="flex h-12 shrink-0 items-center gap-3 border-b px-4">
        <span className="flex items-center gap-1.5 text-sm font-semibold">
          <Inbox className="size-4 text-primary" /> 开发平台 · 待办中心
        </span>
        <Select defaultValue={PROJECT.id}>
          <SelectTrigger size="sm" className="w-56">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={PROJECT.id}>
              {PROJECT.name}（{PROJECT.stage}）
            </SelectItem>
            <SelectItem value="PRJ-0009">鲜花电商小程序（测试）</SelectItem>
            <SelectItem value="PRJ-0007">律所官网（已交付）</SelectItem>
          </SelectContent>
        </Select>
        <Badge variant="secondary" className="hidden h-5 md:inline-flex">进行中 2 · 已交付 1</Badge>
        <div className="ml-auto flex items-center gap-2">
          {pendingCount > 0 ? (
            <Badge className="h-5 gap-1 bg-amber-500 text-amber-950 hover:bg-amber-500">
              <CircleAlert className="size-3" /> 待你处理 {pendingCount}
            </Badge>
          ) : (
            <Badge variant="secondary" className="h-5 gap-1">
              <Check className="size-3" /> 无待办
            </Badge>
          )}
        </div>
      </header>

      <div className="grid min-h-0 flex-1 lg:grid-cols-[260px_1fr_300px]">
        {/* 左：项目上下文（窄屏藏，信息并入队列页头） */}
        <aside className="hidden min-h-0 border-r lg:block">
          <ScrollArea className="h-full">
            <ContextPanel />
          </ScrollArea>
        </aside>

        {/* 中：决策队列 */}
        <main className="min-h-0 overflow-y-auto">
          <div className="mx-auto max-w-2xl space-y-3 p-4 pb-24">
            {/* 窄屏项目摘要条 */}
            <div className="flex items-center gap-2 rounded-md bg-muted/50 px-3 py-2 text-xs text-muted-foreground lg:hidden">
              <span className="font-medium text-foreground">{PROJECT.name}</span>
              <span>阶段「{PROJECT.stage}」</span>
              <PreviewSheet />
            </div>

            {pendingCount === 0 ? (
              <>
                <EmptyQueue />
                <QueueGate />
              </>
            ) : (
              <>
                <QueueQuestion onDone={mark} />
                <QueueApproval onDone={mark} />
                <QueueTestConfirm onDone={mark} />
                <QueueGate />
              </>
            )}

            {/* 下任务入口（队列尾部） */}
            <div className="rounded-lg border p-3">
              <Label className="text-xs text-muted-foreground">给智能体下新任务</Label>
              <div className="mt-2 flex gap-2">
                <Input
                  placeholder="例：把预约成功页加上地图导航…"
                  value={draft}
                  onChange={(e) => setDraft(e.target.value)}
                />
                <Button
                  size="sm"
                  onClick={() => {
                    toast.success(`任务已下达（罐头）：${draft || "（空）"}`)
                    setDraft("")
                  }}
                >
                  <Send className="size-3.5" /> 下任务
                </Button>
              </div>
            </div>
          </div>
        </main>

        {/* 右：只读活动流 */}
        <aside className="hidden min-h-0 border-l lg:block">
          <ActivityStream />
        </aside>
      </div>
    </div>
  )
}
