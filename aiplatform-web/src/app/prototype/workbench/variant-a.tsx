// PROTOTYPE（throwaway）—— Variant A · 三栏对话中心（Code-Canvas 基准形态）
// 左对话流（含直播画面与 HITL 卡）/ 中工作区页签 / 右阶段·任务面板；
// <1024px 折叠为「对话 / 工作区 / 阶段」三页签。
"use client"

import * as React from "react"
import { toast } from "sonner"
import {
  BookMarked,
  Brain,
  Check,
  ChevronRight,
  CircleAlert,
  ExternalLink,
  FilePen,
  FileSearch,
  FlaskConical,
  Lock,
  Send,
  ShieldQuestion,
  SquareTerminal,
  Terminal,
  X,
} from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible"
import { Input } from "@/components/ui/input"
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group"
import { ScrollArea } from "@/components/ui/scroll-area"
import { Separator } from "@/components/ui/separator"
import { Spinner } from "@/components/ui/spinner"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { Textarea } from "@/components/ui/textarea"
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

// ─── 工具 chip（进行中 spinner / 已执行 ✓）──────────────────────

function ToolChip({ name, arg, status }: { name: string; arg: string; status: "running" | "done" }) {
  const Icon = name === "bash" ? Terminal : name === "edit" ? FilePen : FileSearch
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1.5 rounded-md border px-2 py-1 font-mono text-xs",
        status === "running"
          ? "border-primary/40 bg-primary/5 text-primary"
          : "border-border bg-muted/50 text-muted-foreground"
      )}
    >
      <Icon className="size-3.5 shrink-0" />
      <span className="font-sans font-medium">{name}</span>
      <span className="max-w-56 truncate opacity-80">{arg}</span>
      {status === "running" ? <Spinner className="size-3" /> : <Check className="size-3.5" />}
    </span>
  )
}

// ─── 问答卡（一卡多题：单选 / 多选 / 自定义）─────────────────────

function QuestionCard() {
  const [answered, setAnswered] = React.useState(false)
  const [singles, setSingles] = React.useState<Record<number, string>>({ 0: "暂不验证" })
  const [multis, setMultis] = React.useState<Record<number, string[]>>({ 1: ["品种", "年龄"] })
  const [customs, setCustoms] = React.useState<Record<number, string>>({})

  if (answered) {
    return (
      <div className="flex justify-center py-1">
        <Badge variant="secondary" className="gap-1">
          <Check className="size-3" /> 已回答 · 智能体继续执行中
        </Badge>
      </div>
    )
  }

  return (
    <Card className="border-amber-500/40 bg-amber-500/5">
      <CardHeader className="pb-3">
        <CardTitle className="flex items-center gap-2 text-sm font-medium">
          <ShieldQuestion className="size-4 text-amber-600" />
          {PROJECT.role}向你提问
          <span className="text-xs font-normal text-muted-foreground">回答后它继续干活</span>
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        {QUESTIONS.map((q, qi) => (
          <div key={qi} className="space-y-2">
            <p className="text-sm">
              <strong className="text-foreground">{q.header}</strong>
              <span className="text-muted-foreground"> · {q.question}</span>
              {q.multiple && <Badge variant="outline" className="ml-2 text-[10px]">多选</Badge>}
            </p>
            {q.multiple ? (
              <div className="space-y-1.5">
                {q.options.map((opt) => {
                  const checked = (multis[qi] ?? []).includes(opt.label)
                  return (
                    <label
                      key={opt.label}
                      className="flex cursor-pointer items-start gap-2 rounded-md border px-2.5 py-2 text-sm hover:bg-muted/50 has-[button[data-state=checked]]:border-primary/40"
                    >
                      <Checkbox
                        checked={checked}
                        onCheckedChange={() =>
                          setMultis((m) => ({
                            ...m,
                            [qi]: checked
                              ? m[qi].filter((x) => x !== opt.label)
                              : [...(m[qi] ?? []), opt.label],
                          }))
                        }
                      />
                      <span>
                        {opt.label}
                        {opt.description && (
                          <span className="block text-xs text-muted-foreground">{opt.description}</span>
                        )}
                      </span>
                    </label>
                  )
                })}
              </div>
            ) : (
              <RadioGroup
                value={singles[qi] ?? ""}
                onValueChange={(v) => setSingles((s) => ({ ...s, [qi]: v }))}
                className="gap-1.5"
              >
                {q.options.map((opt) => (
                  <label
                    key={opt.label}
                    className="flex cursor-pointer items-start gap-2 rounded-md border px-2.5 py-2 text-sm hover:bg-muted/50 has-[button[data-state=checked]]:border-primary/40"
                  >
                    <RadioGroupItem value={opt.label} />
                    <span>
                      {opt.label}
                      {opt.description && (
                        <span className="block text-xs text-muted-foreground">{opt.description}</span>
                      )}
                    </span>
                  </label>
                ))}
              </RadioGroup>
            )}
            {q.custom && (
              <Input
                placeholder="或输入你自己的答案…"
                value={customs[qi] ?? ""}
                onChange={(e) => setCustoms((c) => ({ ...c, [qi]: e.target.value }))}
                className="h-8"
              />
            )}
          </div>
        ))}
        <Button
          size="sm"
          onClick={() => {
            setAnswered(true)
            toast.success("回答已提交（罐头），智能体继续执行")
          }}
        >
          提交回答
        </Button>
      </CardContent>
    </Card>
  )
}

// ─── 审批卡（允许 / 拒绝 / 终止逃生口）──────────────────────────

function ApprovalCard() {
  const [done, setDone] = React.useState<null | "allow" | "deny">(null)

  if (done) {
    return (
      <div className="flex justify-center py-1">
        <Badge variant="secondary" className="gap-1">
          <Check className="size-3" /> 审批已处理（{done === "allow" ? "允许" : "拒绝"}）
        </Badge>
      </div>
    )
  }

  return (
    <Card className="border-violet-500/40 bg-violet-500/5">
      <CardHeader className="pb-3">
        <CardTitle className="flex items-center gap-2 text-sm font-medium">
          <SquareTerminal className="size-4 text-violet-600" />
          工具执行审批
          <span className="text-xs font-normal text-muted-foreground">
            {APPROVAL.expiresInMin} 分钟内未处理将自动过期
          </span>
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <pre className="overflow-x-auto rounded-md bg-muted/70 p-2.5 font-mono text-xs">
          <span className="text-violet-600 dark:text-violet-400">$</span> {APPROVAL.tool}{" "}
          {APPROVAL.args}
        </pre>
        <p className="text-xs text-muted-foreground">{APPROVAL.reason}</p>
        <div className="flex flex-wrap items-center gap-2">
          <Button
            size="sm"
            onClick={() => {
              setDone("allow")
              toast.success("已允许（罐头），命令开始执行")
            }}
          >
            允许
          </Button>
          <Button
            size="sm"
            variant="outline"
            onClick={() => {
              setDone("deny")
              toast("已拒绝（罐头），智能体会收到拒绝原因")
            }}
          >
            拒绝
          </Button>
          <Button
            size="sm"
            variant="ghost"
            className="ml-auto text-destructive hover:bg-destructive/10 hover:text-destructive"
            onClick={() => toast.error("任务已终止（罐头）——拒绝≠停止时的逃生口")}
          >
            <X className="size-3.5" /> 终止任务
          </Button>
        </div>
      </CardContent>
    </Card>
  )
}

// ─── 对话流（agent 直播画面 + HITL 卡在流底部）──────────────────

export function ChatPanel() {
  const [thinkOpen, setThinkOpen] = React.useState(false)
  const [draft, setDraft] = React.useState("")
  const [seconds, setSeconds] = React.useState(RUN.startedSecondsAgo)
  React.useEffect(() => {
    const t = setInterval(() => setSeconds((s) => s + 1), 1000)
    return () => clearInterval(t)
  }, [])

  return (
    <div className="flex h-full min-h-0 flex-col">
      <ScrollArea className="min-h-0 flex-1">
        <div className="space-y-3 p-4 pb-6">
          {/* 系统胶囊 */}
          <div className="flex justify-center">
            <Badge variant="secondary" className="font-normal">
              08-14 已通过「需求确认门」 · 08-15 已通过「Demo 确认门」 · 进入开发阶段
            </Badge>
          </div>

          {/* 用户消息右泡 */}
          <div className="flex justify-end">
            <div className="max-w-[85%] rounded-xl bg-primary px-3 py-2 text-sm text-primary-foreground">
              {RUN.title}
            </div>
          </div>

          {FEED.map((e, i) => {
            switch (e.kind) {
              case "text":
                return (
                  <div key={i} className="max-w-[92%] whitespace-pre-wrap rounded-xl bg-muted px-3 py-2 text-sm leading-relaxed">
                    {e.text}
                  </div>
                )
              case "reasoning":
                return (
                  <Collapsible key={i} open={thinkOpen} onOpenChange={setThinkOpen}>
                    <CollapsibleTrigger className="flex items-center gap-1.5 rounded-md px-1 py-0.5 text-xs text-muted-foreground hover:text-foreground">
                      <Brain className="size-3.5" />
                      思考过程（12s）
                      <ChevronRight className={cn("size-3 transition-transform", thinkOpen && "rotate-90")} />
                    </CollapsibleTrigger>
                    <CollapsibleContent>
                      <p className="mt-1 border-l-2 pl-3 text-xs leading-relaxed text-muted-foreground">
                        {e.reasoning}
                      </p>
                    </CollapsibleContent>
                  </Collapsible>
                )
              case "tool":
                return (
                  <div key={i} className="pl-1">
                    <ToolChip {...e.tool!} />
                  </div>
                )
              case "knowledge":
                return (
                  <Card key={i} className="border-indigo-500/30 bg-indigo-500/5 py-2">
                    <CardContent className="px-3">
                      <p className="flex items-center gap-1.5 text-xs font-medium text-indigo-700 dark:text-indigo-300">
                        <BookMarked className="size-3.5" />
                        平台知识命中（{e.knowledge!.count} 条 · 检索注入）
                      </p>
                      <div className="mt-1.5 space-y-1.5">
                        {e.knowledge!.items.map((k, ki) => (
                          <div key={ki} className="text-xs">
                            <Badge variant="outline" className="mb-0.5 border-indigo-500/30 bg-indigo-500/10 text-indigo-700 dark:text-indigo-300">
                              {k.source} @ {k.project}
                            </Badge>
                            <p className="line-clamp-2 text-muted-foreground">{k.chunk}</p>
                          </div>
                        ))}
                      </div>
                    </CardContent>
                  </Card>
                )
              case "patch":
                return (
                  <div key={i} className="rounded-lg border bg-muted/30 px-3 py-2 font-mono text-xs">
                    <div className="flex items-center gap-2">
                      <FilePen className="size-3.5 text-muted-foreground" />
                      <span className="font-sans">{e.patch!.file}</span>
                      <span className="ml-auto font-sans text-emerald-600 dark:text-emerald-400">
                        +{e.patch!.added}
                      </span>
                      <span className="font-sans text-red-600 dark:text-red-400">
                        −{e.patch!.removed}
                      </span>
                    </div>
                    <p className="mt-1 font-sans text-muted-foreground">{e.patch!.summary}</p>
                  </div>
                )
              case "hitl":
                return (
                  <div key={i} className="flex justify-center py-1">
                    <Badge className="gap-1 bg-amber-500 text-amber-950 hover:bg-amber-500">
                      <CircleAlert className="size-3" /> {e.hitl}
                    </Badge>
                  </div>
                )
              case "task-start":
                return (
                  <div key={i} className="flex justify-center">
                    <Badge variant="secondary" className="font-normal">
                      任务开始 · {e.text}
                    </Badge>
                  </div>
                )
              default:
                return null
            }
          })}

          {/* HITL 卡并列渲染在消息流底部（CC 模式：双表统一「待我处理」） */}
          <QuestionCard />
          <ApprovalCard />
        </div>
      </ScrollArea>

      {/* 运行状态 + 下任务输入 */}
      <div className="border-t bg-background p-3">
        <div className="mb-2 flex items-center gap-2 text-xs text-muted-foreground">
          <Spinner className="size-3.5 text-primary" />
          <span className="font-medium text-foreground">{RUN.taskId}</span>
          <span className="truncate">{RUN.title}</span>
          <span className="ml-auto font-mono tabular-nums">{formatElapsed(seconds)}</span>
          <Button
            size="xs"
            variant="outline"
            className="h-6 text-destructive hover:bg-destructive/10 hover:text-destructive"
            onClick={() => toast.error("任务已终止（罐头）")}
          >
            <X className="size-3" /> 终止
          </Button>
        </div>
        <div className="flex gap-2">
          <Textarea
            placeholder="给智能体下任务…（Enter 发送）"
            className="min-h-9 resize-none"
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
          />
          <Button
            size="icon"
            onClick={() => {
              toast.success(`任务已下达（罐头）：${draft || "（空）"}`)
              setDraft("")
            }}
          >
            <Send />
          </Button>
        </div>
      </div>
    </div>
  )
}

// ─── 工作区（预览 / 文档 / 时间线）──────────────────────────────

export function WorkspacePanel({
  onOpenStandalone,
  headerExtra,
}: {
  onOpenStandalone?: () => void
  headerExtra?: React.ReactNode
} = {}) {
  return (
    <Tabs defaultValue="preview" className="flex h-full min-h-0 flex-col">
      <div className="flex items-center gap-2 border-b px-3 py-2">
        <TabsList className="h-7">
          <TabsTrigger value="preview" className="text-xs">预览</TabsTrigger>
          <TabsTrigger value="docs" className="text-xs">文档</TabsTrigger>
          <TabsTrigger value="timeline" className="text-xs">时间线</TabsTrigger>
        </TabsList>
        <Button size="xs" variant="outline" className="ml-auto" onClick={() => toast("已重新加载预览（罐头）—— 有更新点击刷新，不自动刷新")}>
          有更新 · 点击刷新
        </Button>
        {onOpenStandalone && (
          <Button size="xs" variant="ghost" onClick={onOpenStandalone}>
            <ExternalLink className="size-3" /> 浏览器打开
          </Button>
        )}
        {headerExtra}
      </div>
      <TabsContent value="preview" className="min-h-0 flex-1">
        <iframe
          {...PREVIEW_IFRAME_PROPS}
          srcDoc={PREVIEW_SRCDOC}
          className="size-full border-0 bg-white"
        />
      </TabsContent>
      <TabsContent value="docs" className="min-h-0 flex-1 p-4">
        <div className="space-y-1.5 text-sm">
          {[
            ["PRD-0712.md", "需求梳理 · 产物"],
            ["PRD · 宠物医院预约官网.pdf", "需求梳理 · 产物"],
            ["demo-原型链接.txt", "Demo · 产物"],
            ["Bug-清单-T03.csv", "测试 · 交付物"],
            ["DELIVERY.md", "交付 · 产物（未生成）"],
          ].map(([name, meta]) => (
            <div key={name} className="flex items-center gap-2 rounded-md border px-3 py-2">
              <FileSearch className="size-4 text-muted-foreground" />
              {name}
              <span className="ml-auto text-xs text-muted-foreground">{meta}</span>
            </div>
          ))}
        </div>
      </TabsContent>
      <TabsContent value="timeline" className="min-h-0 flex-1 p-4">
        <div className="space-y-0 text-sm">
          {[
            ["08-12 14:02", "项目创建（对话）"],
            ["08-13 11:20", "需求梳理 → 需求确认门通过"],
            ["08-14 09:45", "Demo → Demo 确认门通过"],
            ["08-15 10:03", "进入开发 · 下发首个任务"],
            ["08-19 16:30", "测试任务 TASK-T03 提交（Bug × 3）"],
          ].map(([ts, label], i, arr) => (
            <div key={i} className="flex gap-3">
              <div className="flex flex-col items-center">
                <span className={cn("size-2 rounded-full", i === arr.length - 1 ? "bg-primary" : "bg-muted-foreground/40")} />
                {i < arr.length - 1 && <span className="w-px flex-1 bg-border" />}
              </div>
              <div className="pb-4">
                <p>{label}</p>
                <p className="text-xs text-muted-foreground">{ts}</p>
              </div>
            </div>
          ))}
        </div>
      </TabsContent>
    </Tabs>
  )
}

// ─── 阶段 · 任务面板（期步骤 / 门 / 测试循环）───────────────────

const GATES = [
  { name: "需求确认门", state: "passed" },
  { name: "Demo 确认门", state: "passed" },
  { name: "开发完成确认门", state: "locked" },
  { name: "验收门", state: "future" },
] as const

export function StagePanel() {
  return (
    <ScrollArea className="h-full">
      <div className="space-y-5 p-4">
        {/* 期步骤（v1 单期不显示期号） */}
        <section>
          <h3 className="mb-2 text-xs font-medium text-muted-foreground">阶段（七步四门主链）</h3>
          <div className="space-y-1">
            {PROJECT.steps.map((s, i) => (
              <div
                key={s}
                className={cn(
                  "flex items-center gap-2 rounded-md px-2.5 py-2 text-sm",
                  i < PROJECT.currentStepIndex && "text-muted-foreground",
                  i === PROJECT.currentStepIndex &&
                    "border border-primary/40 bg-primary/5 font-medium text-foreground",
                  i > PROJECT.currentStepIndex && "text-muted-foreground/60"
                )}
              >
                {i < PROJECT.currentStepIndex ? (
                  <Check className="size-3.5" />
                ) : (
                  <span className={cn("size-3.5 rounded-full border", i === PROJECT.currentStepIndex && "border-primary bg-primary")} />
                )}
                {s}
                {i === PROJECT.currentStepIndex && (
                  <Badge className="ml-auto h-5 text-[10px]">当前</Badge>
                )}
              </div>
            ))}
          </div>
        </section>

        <Separator />

        {/* 门 */}
        <section>
          <h3 className="mb-2 text-xs font-medium text-muted-foreground">决策门</h3>
          <div className="space-y-1 text-sm">
            {GATES.map((g) => (
              <div key={g.name} className="flex items-center gap-2 rounded-md border px-2.5 py-2">
                {g.state === "passed" ? (
                  <Check className="size-3.5 text-emerald-600" />
                ) : g.state === "locked" ? (
                  <Lock className="size-3.5 text-muted-foreground" />
                ) : (
                  <span className="size-3.5 rounded-full border border-dashed" />
                )}
                <span className={cn(g.state === "future" && "text-muted-foreground/60")}>{g.name}</span>
                {g.state === "passed" && <Badge variant="secondary" className="ml-auto h-5 text-[10px]">已通过</Badge>}
              </div>
            ))}
          </div>
          <p className="mt-1.5 flex items-start gap-1 text-xs text-muted-foreground">
            <Lock className="mt-0.5 size-3 shrink-0" />
            {GATE.lockedReason}
          </p>
        </section>

        <Separator />

        {/* 测试外包循环 */}
        <section>
          <h3 className="mb-2 flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
            <FlaskConical className="size-3.5" /> 测试任务
          </h3>
          <Card className="py-3">
            <CardContent className="space-y-2.5 px-3">
              <div className="flex items-center gap-2 text-sm">
                <span className="font-medium">{TEST_TASK.title}</span>
                <Badge variant="secondary" className="ml-auto h-5 text-[10px]">{TEST_TASK.state}</Badge>
              </div>
              <p className="text-xs text-muted-foreground">
                {TEST_TASK.id} · 执行方 {TEST_TASK.assignee}
              </p>
              <div className="space-y-1">
                {TEST_TASK.bugs.map((b) => (
                  <div key={b.id} className="flex items-center gap-2 text-xs">
                    <span className="font-mono text-muted-foreground">{b.id}</span>
                    <span className="truncate">{b.title}</span>
                    <Badge
                      variant="outline"
                      className={cn(
                        "ml-auto h-5 shrink-0 text-[10px]",
                        b.status === "待修复" && "border-amber-500/40 text-amber-700 dark:text-amber-400",
                        b.status === "已修复 · 待复测" && "border-sky-500/40 text-sky-700 dark:text-sky-400"
                      )}
                    >
                      {b.status}
                    </Badge>
                  </div>
                ))}
              </div>
              <div className="flex flex-wrap gap-2 pt-1">
                <Button size="xs" onClick={() => toast.success("已确认测试（罐头）· Bug 入库并派智能体修复")}>
                  确认测试
                </Button>
                <Button size="xs" variant="outline" onClick={() => toast("已驳回（罐头）· 退回 OPC 返工（需附说明）")}>
                  驳回
                </Button>
                <Button size="xs" variant="secondary" disabled title="还有 1 条待修复 Bug，全部修复后可发复测">
                  发复测任务
                </Button>
              </div>
            </CardContent>
          </Card>
        </section>
      </div>
    </ScrollArea>
  )
}

// ─── 变体装配 ──────────────────────────────────────────────────

export function VariantA() {
  return (
    <div className="flex h-svh flex-col">
      {/* 顶栏 */}
      <header className="flex h-12 shrink-0 items-center gap-3 border-b px-4">
        <span className="text-sm font-semibold">{PROJECT.name}</span>
        <Badge variant="secondary" className="h-5">{PROJECT.id}</Badge>
        <Badge className="h-5 gap-1">
          {PROJECT.stage} · {PROJECT.role}
        </Badge>
        <div className="mx-4 hidden items-center gap-0.5 text-xs text-muted-foreground md:flex">
          {PROJECT.steps.map((s, i) => (
            <React.Fragment key={s}>
              {i > 0 && <ChevronRight className="size-3" />}
              <span className={cn(i === PROJECT.currentStepIndex && "font-medium text-foreground")}>
                {s}
              </span>
            </React.Fragment>
          ))}
        </div>
        <Button size="sm" variant="outline" className="ml-auto" onClick={() => toast.success("已创建测试任务草稿（罐头）· 请选择 OPC 并指派")}>
          <FlaskConical className="size-3.5" /> 发测试任务
        </Button>
      </header>

      {/* 三栏（<1024 折叠三页签） */}
      <div className="hidden min-h-0 flex-1 lg:grid lg:grid-cols-[380px_1fr_320px]">
        <div className="min-h-0 border-r">
          <ChatPanel />
        </div>
        <div className="min-h-0">
          <WorkspacePanel />
        </div>
        <div className="min-h-0 border-l">
          <StagePanel />
        </div>
      </div>

      <Tabs defaultValue="chat" className="flex min-h-0 flex-1 flex-col lg:hidden">
        <TabsList className="m-2 grid grid-cols-3">
          <TabsTrigger value="chat">对话</TabsTrigger>
          <TabsTrigger value="ws">工作区</TabsTrigger>
          <TabsTrigger value="stage">阶段</TabsTrigger>
        </TabsList>
        <TabsContent value="chat" className="min-h-0 flex-1">
          <ChatPanel />
        </TabsContent>
        <TabsContent value="ws" className="min-h-0 flex-1">
          <WorkspacePanel />
        </TabsContent>
        <TabsContent value="stage" className="min-h-0 flex-1">
          <StagePanel />
        </TabsContent>
      </Tabs>
    </div>
  )
}
