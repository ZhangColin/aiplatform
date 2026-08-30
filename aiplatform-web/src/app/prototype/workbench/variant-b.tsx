// PROTOTYPE（throwaway）—— Variant B · 直播舞台（agent 中心）
// agent 干活画面是主舞台（全宽时间线 + 运行 HUD），对话输入收进左侧
// 窄栏，HITL 以底部 dock 侵入式弹出（一次一张、队列计数）。
// <1024px：左栏收为 Sheet。
"use client"

import * as React from "react"
import { toast } from "sonner"
import {
  BookMarked,
  Brain,
  Check,
  ChevronRight,
  CircleAlert,
  Eye,
  FilePen,
  FileSearch,
  History,
  Lock,
  PanelLeft,
  Send,
  ShieldQuestion,
  SquareTerminal,
  Terminal,
  X,
} from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group"
import { ScrollArea } from "@/components/ui/scroll-area"
import { Sheet, SheetContent, SheetTitle, SheetTrigger } from "@/components/ui/sheet"
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
  formatElapsed,
} from "./canned"

const DIFF_LINES = [
  { t: " ", s: "export function BookingForm() {" },
  { t: " ", s: "  const [phone, setPhone] = useState(\"\")" },
  { t: "-", s: "  // TODO: 校验" },
  { t: "+", s: "  const phoneOk = /^1\\d{10}$/.test(phone)" },
  { t: "+", s: "  const slots = useSlots(date, { disablePast: true })" },
  { t: " ", s: "  return <form onSubmit={submit}>" },
]

// ─── 舞台：大事件时间线 ────────────────────────────────────────

export function StageStream() {
  const [thinkOpen, setThinkOpen] = React.useState(false)
  return (
    <div className="mx-auto max-w-3xl space-y-5 p-6 pb-40">
      {FEED.map((e, i) => {
        switch (e.kind) {
          case "task-start":
            return (
              <div key={i} className="flex items-center gap-3">
                <span className="bg-primary/15 text-primary rounded-md px-2 py-1 text-xs font-medium">
                  任务开始
                </span>
                <span className="text-sm text-muted-foreground">{e.text}</span>
              </div>
            )
          case "text":
            // 字幕式：agent 的话直接成段，无气泡边框
            return (
              <p key={i} className="text-base leading-relaxed">
                {e.text}
              </p>
            )
          case "reasoning":
            return (
              <Collapsible key={i} open={thinkOpen} onOpenChange={setThinkOpen}>
                <CollapsibleTrigger className="flex items-center gap-2 text-sm text-muted-foreground hover:text-foreground">
                  <Brain className="size-4" /> 思考过程（12s）
                  <ChevronRight className={cn("size-3.5 transition-transform", thinkOpen && "rotate-90")} />
                </CollapsibleTrigger>
                <CollapsibleContent>
                  <p className="mt-2 border-l-2 pl-4 text-sm leading-relaxed text-muted-foreground">
                    {e.reasoning}
                  </p>
                </CollapsibleContent>
              </Collapsible>
            )
          case "tool":
            return (
              <div
                key={i}
                className={cn(
                  "flex max-w-lg items-center gap-3 rounded-lg border px-3.5 py-2.5",
                  e.tool!.status === "running"
                    ? "border-primary/50 bg-primary/5"
                    : "border-border bg-muted/40"
                )}
              >
                {e.tool!.name === "bash" ? (
                  <Terminal className="size-4 text-muted-foreground" />
                ) : e.tool!.name === "edit" ? (
                  <FilePen className="size-4 text-muted-foreground" />
                ) : (
                  <FileSearch className="size-4 text-muted-foreground" />
                )}
                <div className="min-w-0">
                  <p className="text-sm font-medium">{e.tool!.name}</p>
                  <p className="truncate font-mono text-xs text-muted-foreground">{e.tool!.arg}</p>
                </div>
                {e.tool!.status === "running" ? (
                  <span className="ml-auto flex items-center gap-1.5 text-xs text-primary">
                    <Spinner className="size-3.5" /> 执行中
                  </span>
                ) : (
                  <Check className="ml-auto size-4 text-muted-foreground" />
                )}
              </div>
            )
          case "knowledge":
            return (
              <div key={i} className="rounded-lg border border-indigo-500/30 bg-indigo-500/10 px-4 py-3">
                <p className="flex items-center gap-2 text-sm font-medium text-indigo-700 dark:text-indigo-300">
                  <BookMarked className="size-4" />
                  知识命中 · {e.knowledge!.count} 条历史知识被检索注入
                </p>
                <div className="mt-2 grid gap-2 sm:grid-cols-2">
                  {e.knowledge!.items.map((k, ki) => (
                    <div key={ki} className="rounded-md bg-background/60 p-2.5 text-xs">
                      <p className="font-medium text-indigo-700 dark:text-indigo-300">
                        {k.source} <span className="font-normal text-muted-foreground">· {k.project}</span>
                      </p>
                      <p className="mt-1 line-clamp-3 text-muted-foreground">{k.chunk}</p>
                    </div>
                  ))}
                </div>
              </div>
            )
          case "patch":
            return (
              <div key={i} className="overflow-hidden rounded-lg border">
                <div className="flex items-center gap-2 border-b bg-muted/50 px-3.5 py-2 text-xs">
                  <FilePen className="size-3.5" />
                  <span className="font-medium">{e.patch!.file}</span>
                  <span className="ml-auto text-emerald-600 dark:text-emerald-400">+{e.patch!.added}</span>
                  <span className="text-red-600 dark:text-red-400">−{e.patch!.removed}</span>
                </div>
                <div className="overflow-x-auto bg-muted/20 py-1 font-mono text-xs leading-6">
                  {DIFF_LINES.map((l, li) => (
                    <div
                      key={li}
                      className={cn(
                        "px-3.5",
                        l.t === "+" && "bg-emerald-500/10 text-emerald-700 dark:text-emerald-400",
                        l.t === "-" && "bg-red-500/10 text-red-700 dark:text-red-400"
                      )}
                    >
                      <span className="mr-2 inline-block w-2 select-none opacity-60">{l.t}</span>
                      {l.s}
                    </div>
                  ))}
                </div>
                <p className="border-t bg-muted/30 px-3.5 py-1.5 text-xs text-muted-foreground">
                  {e.patch!.summary}
                </p>
              </div>
            )
          case "hitl":
            return (
              <div key={i} className="flex items-center gap-2 text-sm text-amber-600 dark:text-amber-400">
                <CircleAlert className="size-4" /> {e.hitl}
              </div>
            )
          default:
            return null
        }
      })}
    </div>
  )
}

// ─── HITL dock：一次一张、队列计数 ─────────────────────────────

function DockQuestion({ onNext }: { onNext: () => void }) {
  const [singles, setSingles] = React.useState<Record<number, string>>({ 0: "暂不验证" })
  const [multis, setMultis] = React.useState<Record<number, string[]>>({ 1: ["品种"] })
  const [customs, setCustoms] = React.useState<Record<number, string>>({})
  return (
    <div className="space-y-4">
      {QUESTIONS.map((q, qi) => (
        <div key={qi} className="space-y-2">
          <div className="flex items-baseline gap-2 text-sm">
            <Badge variant="outline" className="h-5 shrink-0">{q.header}</Badge>
            <span className="text-muted-foreground">{q.question}</span>
          </div>
          {q.multiple ? (
            <div className="flex flex-wrap gap-1.5">
              {q.options.map((opt) => {
                const checked = (multis[qi] ?? []).includes(opt.label)
                return (
                  <label
                    key={opt.label}
                    title={opt.description}
                    className="flex cursor-pointer items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs hover:bg-muted/50 has-[button[data-state=checked]]:border-primary/50 has-[button[data-state=checked]]:bg-primary/5"
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
                  className="flex cursor-pointer items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs hover:bg-muted/50 has-[button[data-state=checked]]:border-primary/50 has-[button[data-state=checked]]:bg-primary/5"
                >
                  <RadioGroupItem value={opt.label} className="size-3" />
                  {opt.label}
                </label>
              ))}
            </RadioGroup>
          )}
          {q.custom && (
            <Input
              placeholder="自定义答案…"
              className="h-8"
              value={customs[qi] ?? ""}
              onChange={(e) => setCustoms((c) => ({ ...c, [qi]: e.target.value }))}
            />
          )}
        </div>
      ))}
      <div className="flex justify-end">
        <Button
          size="sm"
          onClick={() => {
            toast.success("回答已提交（罐头）")
            onNext()
          }}
        >
          提交回答
        </Button>
      </div>
    </div>
  )
}

function DockApproval({ onNext }: { onNext: () => void }) {
  return (
    <div className="space-y-3">
      <div className="flex items-center gap-2">
        <SquareTerminal className="size-4 text-violet-600" />
        <span className="text-sm font-medium">审批：{APPROVAL.tool}</span>
        <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-xs">{APPROVAL.args}</code>
        <span className="ml-auto text-xs text-muted-foreground">{APPROVAL.expiresInMin} 分钟后过期</span>
      </div>
      <div className="flex justify-end gap-2">
        <Button
          size="sm"
          variant="outline"
          onClick={() => {
            toast("已拒绝（罐头）")
            onNext()
          }}
        >
          拒绝
        </Button>
        <Button
          size="sm"
          onClick={() => {
            toast.success("已允许（罐头）")
            onNext()
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
  )
}

function HitlDock() {
  const [resolved, setResolved] = React.useState(0)
  const [active, setActive] = React.useState(0)
  const cards = [
    { id: "q", title: "智能体提问 · 2 个问题", icon: ShieldQuestion, node: <DockQuestion onNext={() => setResolved((r) => r + 1)} /> },
    { id: "a", title: "工具执行审批", icon: SquareTerminal, node: <DockApproval onNext={() => setResolved((r) => r + 1)} /> },
  ]
  const pending = cards.length - Math.min(resolved, cards.length)

  return (
    <div className="pointer-events-none absolute inset-x-0 bottom-0 z-10 flex justify-center p-4">
      <div className="pointer-events-auto w-full max-w-2xl rounded-2xl border bg-background/95 shadow-2xl backdrop-blur">
        <div className="flex items-center gap-2 border-b px-4 py-2">
          <ShieldQuestion className="size-4 text-amber-600" />
          <span className="text-sm font-medium">待你处理</span>
          <Badge className="h-5 bg-amber-500 text-amber-950 hover:bg-amber-500">{pending}</Badge>
          <div className="ml-auto flex items-center gap-1">
            {cards.map((c, i) => (
              <button
                key={c.id}
                onClick={() => setActive(i)}
                className={cn(
                  "rounded-md px-2 py-0.5 text-xs",
                  i === active
                    ? "bg-primary/10 font-medium text-primary"
                    : "text-muted-foreground hover:text-foreground"
                )}
              >
                {c.title.split(" · ")[0]}
              </button>
            ))}
          </div>
        </div>
        <div className="max-h-72 overflow-y-auto p-4">
          {pending === 0 ? (
            <p className="py-4 text-center text-sm text-muted-foreground">
              已全部处理 · 智能体继续执行（罐头）
            </p>
          ) : (
            cards[active]?.node
          )}
        </div>
      </div>
    </div>
  )
}

// ─── 左栏（任务输入 + 历史 + 平台动作）─────────────────────────

function SidePanelContent() {
  const [draft, setDraft] = React.useState("")
  return (
    <div className="flex h-full flex-col gap-4 p-3">
      <div className="space-y-2">
        <Label className="text-xs text-muted-foreground">给智能体下任务</Label>
        <Textarea
          placeholder="描述要它做的事…"
          className="min-h-16 resize-none text-sm"
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
        />
        <Button
          size="sm"
          className="w-full"
          onClick={() => {
            toast.success(`任务已下达（罐头）：${draft || "（空）"}`)
            setDraft("")
          }}
        >
          <Send className="size-3.5" /> 下任务
        </Button>
      </div>

      <div className="min-h-0 flex-1">
        <Label className="mb-2 flex items-center gap-1 text-xs text-muted-foreground">
          <History className="size-3" /> 本阶段任务
        </Label>
        <div className="space-y-1.5 text-xs">
          {[
            ["TASK-0041", "搭建官网首页骨架", "已完成"],
            ["TASK-0042", "实现预约表单页", "执行中"],
            ["TASK-T03", "首页与预约流程手工测试", "已提交"],
          ].map(([id, title, st]) => (
            <div key={id} className="rounded-md border px-2.5 py-2">
              <p className="font-medium">{title}</p>
              <p className="mt-0.5 flex items-center gap-2 text-muted-foreground">
                <span className="font-mono">{id}</span>
                <span
                  className={cn(
                    st === "执行中" && "text-primary",
                    st === "已提交" && "text-amber-600 dark:text-amber-400"
                  )}
                >
                  {st}
                </span>
              </p>
            </div>
          ))}
        </div>
      </div>

      <div className="space-y-1.5 border-t pt-3">
        <Button size="sm" variant="outline" className="w-full" onClick={() => toast.success("已创建测试任务草稿（罐头）")}>
          发测试任务
        </Button>
        <div className="flex items-center gap-2 rounded-md border px-2.5 py-2 text-xs text-muted-foreground">
          <Lock className="size-3.5 shrink-0" /> {GATE.title} · {GATE.lockedReason}
        </div>
      </div>
    </div>
  )
}

// ─── 装配 ──────────────────────────────────────────────────────

export function VariantB() {
  const [seconds, setSeconds] = React.useState(RUN.startedSecondsAgo)
  React.useEffect(() => {
    const t = setInterval(() => setSeconds((s) => s + 1), 1000)
    return () => clearInterval(t)
  }, [])

  return (
    <div className="flex h-svh flex-col">
      {/* 运行 HUD */}
      <header className="flex h-14 shrink-0 items-center gap-4 border-b px-4">
        <Sheet>
          <SheetTrigger render={<Button size="icon-sm" variant="ghost" className="xl:hidden" />}>
            <PanelLeft />
          </SheetTrigger>
          <SheetContent side="left" className="w-72 p-0">
            <SheetTitle className="sr-only">任务面板</SheetTitle>
            <SidePanelContent />
          </SheetContent>
        </Sheet>

        <div className="flex items-baseline gap-2">
          <span className="text-sm font-semibold">{PROJECT.name}</span>
          <Badge variant="secondary" className="h-5">{PROJECT.engine}</Badge>
          <Badge variant="secondary" className="h-5">{PROJECT.role}</Badge>
        </div>

        <div className="mx-auto hidden items-center gap-1 text-xs text-muted-foreground md:flex">
          {PROJECT.steps.map((s, i) => (
            <React.Fragment key={s}>
              {i > 0 && <ChevronRight className="size-3" />}
              <span className={cn(i === PROJECT.currentStepIndex && "font-medium text-foreground")}>{s}</span>
            </React.Fragment>
          ))}
        </div>

        <div className="ml-auto flex items-center gap-4">
          <span className="hidden items-center gap-1.5 text-xs text-muted-foreground sm:flex">
            {RUN.tokens} tokens
          </span>
          <span className="flex items-center gap-2 rounded-full border border-red-500/40 bg-red-500/10 px-3 py-1">
            <span className="relative flex size-2">
              <span className="absolute inline-flex size-full animate-ping rounded-full bg-red-500 opacity-60" />
              <span className="relative inline-flex size-2 rounded-full bg-red-500" />
            </span>
            <span className="font-mono text-sm font-medium tabular-nums text-red-600 dark:text-red-400">
              {formatElapsed(seconds)}
            </span>
          </span>
          <Button
            size="sm"
            variant="destructive"
            onClick={() => toast.error("任务已终止（罐头）")}
          >
            <X className="size-3.5" /> 终止
          </Button>
        </div>
      </header>

      {/* 左栏 + 舞台 */}
      <div className="flex min-h-0 flex-1">
        <aside className="hidden w-64 shrink-0 border-r xl:block">
          <SidePanelContent />
        </aside>

        <div className="relative min-h-0 flex-1">
          <Tabs defaultValue="live" className="flex h-full min-h-0 flex-col">
            <div className="border-b px-3 py-2">
              <TabsList className="h-7">
                <TabsTrigger value="live" className="gap-1.5 text-xs">
                  <Eye className="size-3.5" /> 直播
                </TabsTrigger>
                <TabsTrigger value="preview" className="text-xs">预览</TabsTrigger>
              </TabsList>
            </div>
            <TabsContent value="live" className="min-h-0 flex-1">
              <ScrollArea className="h-full">
                <StageStream />
              </ScrollArea>
            </TabsContent>
            <TabsContent value="preview" className="min-h-0 flex-1">
              <div className="flex h-full flex-col">
                <div className="flex items-center justify-end border-b bg-muted/30 px-3 py-1.5">
                  <Button size="xs" variant="outline" onClick={() => toast("已刷新预览（罐头）")}>
                    有更新 · 点击刷新
                  </Button>
                </div>
                <iframe {...PREVIEW_IFRAME_PROPS} srcDoc={PREVIEW_SRCDOC} className="size-full flex-1 border-0 bg-white" />
              </div>
            </TabsContent>
          </Tabs>
          <HitlDock />
        </div>
      </div>
    </div>
  )
}
