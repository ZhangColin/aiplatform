// PROTOTYPE（throwaway）—— wayfinder #6 共享原子件
// 内容级原子（气泡 / CC 式问答卡 / 输入条 / PRD 文档 / 直播时间线 /
// 预览窗 / 订单卡）；壳与布局直接用真实组件（WorkbenchShell 等）。
//
// 问答交互（CC / Replit 式）：单选 chip 点即答；多选才出「提交」；
// 补充或不按选项回答 → 焦点到输入条，Enter 提交即当前问题的答案。
"use client"

import * as React from "react"
import {
  Brain,
  Check,
  ChevronRight,
  CircleAlert,
  FilePen,
  FileSearch,
  FileText,
  Folder,
  Send,
  Terminal,
} from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible"
import { Spinner } from "@/components/ui/spinner"
import { Textarea } from "@/components/ui/textarea"
import { cn } from "@/lib/utils"

import {
  KNOWLEDGE_HITS,
  ORDER_TEXT,
  previewSrcdoc,
  systemReady,
  type Desk,
  type FileEntry,
  type Msg,
  type Prd,
  type QuestionCard,
  type Seg,
} from "./canned"

// ── 文件树 + 文件视图（成果区「文件」模式）───────────────────────

/** 目录缩进展开的文件树；文件随系统版本长出（visibleFiles 预过滤） */
export function FileTree({
  files,
  active,
  onSelect,
}: {
  files: FileEntry[]
  active: string | null
  onSelect: (path: string) => void
}) {
  const [closed, setClosed] = React.useState<ReadonlySet<string>>(new Set())
  const toggle = (dir: string) =>
    setClosed((prev) => {
      const next = new Set(prev)
      if (next.has(dir)) next.delete(dir)
      else next.add(dir)
      return next
    })

  // 路径 → 嵌套目录（罐头数据最多两层：docs/、src/、src/pages/）
  interface DirNode {
    name: string
    files: FileEntry[]
    dirs: Map<string, DirNode>
  }
  const root: DirNode = { name: "", files: [], dirs: new Map() }
  for (const f of files) {
    const parts = f.path.split("/")
    let node = root
    for (let i = 0; i < parts.length - 1; i++) {
      const name = parts[i]
      if (!node.dirs.has(name)) node.dirs.set(name, { name, files: [], dirs: new Map() })
      node = node.dirs.get(name)!
    }
    node.files.push(f)
  }

  const renderDir = (node: DirNode, depth: number): React.ReactNode => {
    const path = node.name
    const open = !closed.has(path)
    return (
      <div key={path}>
        {depth >= 0 && node.name ? (
          <button
            onClick={() => toggle(path)}
            className="flex w-full items-center gap-1.5 rounded-md px-2 py-1.5 text-xs text-muted-foreground hover:bg-muted"
            style={{ paddingLeft: 8 + depth * 14 }}
          >
            <ChevronRight className={cn("size-3 transition-transform", open && "rotate-90")} />
            <Folder className="size-3.5" />
            {node.name}
          </button>
        ) : null}
        {open ? (
          <>
            {[...node.dirs.values()].map((d) => renderDir(d, depth + 1))}
            {node.files.map((f) => (
              <button
                key={f.path}
                onClick={() => onSelect(f.path)}
                className={cn(
                  "flex w-full items-center gap-1.5 rounded-md px-2 py-1.5 text-xs hover:bg-muted",
                  active === f.path && "bg-muted font-medium text-foreground"
                )}
                style={{ paddingLeft: 8 + (depth + 1) * 14 }}
              >
                <FileText className="size-3.5 text-muted-foreground" />
                <span className="truncate">{f.name}</span>
              </button>
            ))}
          </>
        ) : null}
      </div>
    )
  }

  return (
    <div className="flex h-full flex-col">
      <div className="flex h-9 shrink-0 items-center border-b px-3 text-xs font-medium text-muted-foreground">
        文件
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto p-1.5">
        {files.length === 0 ? (
          <p className="p-3 text-[11px] leading-relaxed text-muted-foreground">
            需求文档整理好后会出现在这里；系统做好后，代码文件也会长出来。
          </p>
        ) : (
          renderDir(root, -1)
        )}
      </div>
    </div>
  )
}

/** 代码文件的罐头视图（文件树点击后在主区域看） */
export function CodeView({ file }: { file: FileEntry }) {
  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex h-8 shrink-0 items-center gap-2 border-b bg-muted/40 px-3 font-mono text-[11px] text-muted-foreground">
        {file.path}
      </div>
      <div className="min-h-0 flex-1 overflow-auto p-4">
        <pre className="font-mono text-xs leading-6 whitespace-pre-wrap text-foreground/90">{file.code}</pre>
      </div>
    </div>
  )
}

// ── 阶段文案 ──────────────────────────────────────────────────────

/** 非运行态的阶段短语（顶栏徽章 / 右栏用） */
export function phaseLabel(p: string): string {
  return (
    {
      chat: "聊需求中",
      prdReady: "需求文档已就绪",
      ready: "系统就绪 · 可提意见修改",
      ordered: "已下单 · 等待报价",
      quoted: "已报价 · 等您支付",
      paid: "交易完成 · 已归档",
    } as Record<string, string>
  )[p] ?? ""
}

// ── 对话气泡 ──────────────────────────────────────────────────────

export function BaAvatar({ className }: { className?: string }) {
  return (
    <div
      className={cn(
        "flex size-7 shrink-0 items-center justify-center rounded-full bg-emerald-100 text-xs font-medium text-emerald-800",
        className
      )}
    >
      艾
    </div>
  )
}

export function MsgBubble({ m }: { m: Msg }) {
  if (m.role === "event") {
    return (
      <div className="my-2 flex justify-center">
        <span className="rounded-full bg-muted px-3 py-1 text-[11px] text-muted-foreground">
          {m.text}
        </span>
      </div>
    )
  }
  if (m.role === "user") {
    return (
      <div className="flex justify-end">
        <div className="max-w-[85%] rounded-2xl rounded-br-sm bg-primary px-3.5 py-2 text-sm text-primary-foreground">
          {m.text}
        </div>
      </div>
    )
  }
  return (
    <div className="flex gap-2">
      <BaAvatar />
      <div className="max-w-[85%]">
        <div className="rounded-2xl rounded-tl-sm bg-muted px-3.5 py-2 text-sm">{m.text}</div>
        {m.knowledge ? <KnowledgeBadge n={m.knowledge} className="mt-1" /> : null}
      </div>
    </div>
  )
}

export function KnowledgeBadge({ n, className }: { n: number; className?: string }) {
  return (
    <Collapsible>
      <CollapsibleTrigger
        className={cn("text-[11px] text-muted-foreground underline-offset-2 hover:underline", className)}
      >
        💡 参考了 {n} 个相似项目的经验
      </CollapsibleTrigger>
      <CollapsibleContent>
        <div className="mt-1 space-y-1.5 rounded-xl border bg-background p-2.5">
          {KNOWLEDGE_HITS.slice(0, n).map((k) => (
            <div key={k.project}>
              <div className="text-[11px] font-medium text-muted-foreground">{k.project}</div>
              <div className="text-[11px] leading-relaxed text-muted-foreground/80">{k.chunk}</div>
            </div>
          ))}
        </div>
      </CollapsibleContent>
    </Collapsible>
  )
}

/**
 * 问答卡（CC 式）：题干是顾问消息下方的内联卡；选项 chip——单选点即答，
 * 多选勾选 + 「提交」；不按选项回答就走底部输入条（点提示行即聚焦）。
 */
export function QuestionView({
  card,
  selections,
  onPick,
  onToggle,
  onConfirm,
}: {
  card: QuestionCard
  selections: string[]
  /** 单选：点 chip 即提交 */
  onPick: (label: string) => void
  /** 多选：勾选 / 取消 */
  onToggle: (label: string) => void
  /** 多选：提交已勾选项 */
  onConfirm: () => void
}) {
  return (
    <div className="flex gap-2">
      <BaAvatar />
      <Card className="max-w-[92%] flex-1 gap-2 py-3 shadow-none">
        <CardContent className="space-y-2.5 px-3.5">
          <div className="flex items-center gap-2">
            <Badge variant="secondary" className="text-[10px]">{card.header}</Badge>
          </div>
          <div className="text-sm font-medium">{card.question}</div>
          <div className="flex flex-wrap gap-1.5">
            {card.options.map((o) => {
              const picked = card.multiple && selections.includes(o.label)
              return (
                <button
                  key={o.label}
                  onClick={() => (card.multiple ? onToggle(o.label) : onPick(o.label))}
                  className={cn(
                    "rounded-full border px-3 py-1.5 text-left text-xs transition-colors",
                    picked ? "border-primary bg-primary text-primary-foreground" : "hover:bg-muted"
                  )}
                >
                  {card.multiple ? (
                    <span
                      className={cn(
                        "mr-1.5 inline-flex size-3.5 items-center justify-center rounded border text-[9px]",
                        picked ? "border-primary-foreground bg-primary-foreground text-primary" : "border-muted-foreground/50"
                      )}
                    >
                      {picked ? "✓" : ""}
                    </span>
                  ) : null}
                  {o.label}
                  {o.desc ? (
                    <span className={cn("block text-[10px]", picked ? "text-primary-foreground/80" : "text-muted-foreground")}>
                      {o.desc}
                    </span>
                  ) : null}
                </button>
              )
            })}
          </div>
          {card.multiple ? (
            <Button size="sm" disabled={selections.length === 0} onClick={onConfirm}>
              提交{selections.length > 0 ? `（已选 ${selections.length} 项）` : ""}
            </Button>
          ) : null}
          <button
            onClick={() =>
              (document.querySelector("textarea[data-composer]") as HTMLTextAreaElement | null)?.focus()
            }
            className="block text-[11px] text-muted-foreground underline-offset-2 hover:underline"
          >
            不想选？直接在下方输入框回答 ↵
          </button>
        </CardContent>
      </Card>
    </div>
  )
}

// ── 聊天流（含末尾活问答卡 + 定稿卡）─────────────────────────────

export function useStickBottom(dep: unknown) {
  const ref = React.useRef<HTMLDivElement>(null)
  React.useEffect(() => {
    ref.current?.scrollTo({ top: ref.current.scrollHeight, behavior: "smooth" })
  }, [dep])
  return ref
}

export function ChatFlow({ desk }: { desk: Desk }) {
  const { s, answer, togglePending, finalize } = desk
  const ref = useStickBottom(s.messages.length + (s.pendingQuestion ? 1 : 0))
  return (
    <div ref={ref} className="min-h-0 flex-1 overflow-y-auto p-3">
      <div className="space-y-3">
        {s.messages.map((m) => (
          <MsgBubble key={m.id} m={m} />
        ))}
        {/* 直播定盘在右侧栏：对话流这里不再流式制作条目 */}
        {s.pendingQuestion ? (
          <QuestionView
            card={s.pendingQuestion}
            selections={s.pendingSelections}
            onPick={(label) => answer([label])}
            onToggle={togglePending}
            onConfirm={() => answer(s.pendingSelections)}
          />
        ) : null}
        {s.phase === "prdReady" ? <FinalizeCard onConfirm={finalize} /> : null}
      </div>
    </div>
  )
}

/** 直播侧栏（偏好=右侧时系统模式的侧栏）：条目列表 + 进行中/回看 */
export function LiveSide({ desk }: { desk: Desk }) {
  const { s } = desk
  const active = s.run.active
  const segs = active ? s.run.segs.slice(0, s.run.index + 1) : (s.lastRun?.segs ?? [])
  const ref = useStickBottom(segs.length)
  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex h-9 shrink-0 items-center gap-2 border-b px-3 text-xs font-medium text-muted-foreground">
        {active ? (
          <>
            <span className="size-1.5 animate-pulse rounded-full bg-red-500" />
            直播 · 制作过程
          </>
        ) : (
          <span>直播 · {s.lastRun ? "回看" : "待开始"}</span>
        )}
      </div>
      <div ref={ref} className="min-h-0 flex-1 overflow-y-auto">
        <div className="space-y-2.5 p-3">
          {segs.length === 0 ? (
            <p className="text-[11px] leading-relaxed text-muted-foreground">
              开始做系统后，制作过程在这里全程直播。
            </p>
          ) : (
            segs.map((seg, i) => (
              <SegChatEntry key={i} seg={seg} running={active && i === segs.length - 1} />
            ))
          )}
        </div>
      </div>
    </div>
  )
}

/** 定稿卡（门卡样式：需要你拍板 → 开始做系统） */
function FinalizeCard({ onConfirm }: { onConfirm: () => void }) {
  return (
    <div className="rounded-xl border border-amber-500/40 bg-amber-500/5 p-4">
      <div className="text-xs font-medium text-amber-700 dark:text-amber-400">需要你拍板</div>
      <div className="mt-1 text-sm font-medium">需求文档已就绪，确认后开始做系统</div>
      <p className="mt-1 text-xs leading-relaxed text-muted-foreground">
        定稿后自动开始制作，全程直播可看；之后想改随时提意见，不着急。
      </p>
      <Button size="lg" className="mt-3" onClick={onConfirm}>
        开始做系统
      </Button>
    </div>
  )
}

/**
 * 底部输入条（PromptComposer 形态）：问题待答时，这里的提交就是对当前
 * 问题的回答（可与已勾选的多选项合并）；平时是意见 / 自由对话输入。
 */
export function Composer({
  desk,
  onOpinion,
  quickIdeas,
}: {
  desk: Desk
  onOpinion: (t: string) => void
  quickIdeas: string[]
}) {
  const { s, answer } = desk
  const [draft, setDraft] = React.useState("")
  const ref = React.useRef<HTMLTextAreaElement>(null)

  // 问题到达即聚焦输入条：chip 与打字两条路都在手上（CC 式）
  const qid = s.pendingQuestion?.id
  React.useEffect(() => {
    if (qid) ref.current?.focus()
  }, [qid])

  const submit = () => {
    const text = draft.trim()
    if (s.pendingQuestion) {
      if (!text && s.pendingSelections.length === 0) return
      answer(s.pendingSelections, text || undefined)
      setDraft("")
      return
    }
    if (!text) return
    onOpinion(text)
    setDraft("")
  }

  const placeholder = s.pendingQuestion
    ? "也可以不选，直接在这里写你的回答…（Enter 发送）"
    : s.run.active
      ? "制作中… 想法可以先记下，这轮做完一起处理"
      : s.phase === "paid"
        ? "本项目已完成 · 有新需求请开新项目"
        : systemReady(s.phase)
          ? "想改哪里、有什么想法，直接说"
          : "想到什么直接说，不用等提问"

  return (
    <div className="shrink-0 space-y-2 border-t p-3">
      {systemReady(s.phase) && !s.run.active && s.order.state === "none" ? (
        <div className="flex flex-wrap gap-1.5">
          {quickIdeas.map((idea) => (
            <button
              key={idea}
              onClick={() => onOpinion(idea)}
              className="rounded-full border border-dashed px-2.5 py-1 text-[11px] text-muted-foreground hover:bg-muted"
            >
              {idea}
            </button>
          ))}
        </div>
      ) : null}
      <div className="flex items-end gap-2">
        <Textarea
          ref={ref}
          data-composer
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
              e.preventDefault()
              submit()
            }
          }}
          placeholder={placeholder}
          disabled={s.phase === "paid"}
          className="min-h-9 flex-1 resize-none text-sm"
        />
        <Button size="icon" onClick={submit} disabled={s.phase === "paid" || (!draft.trim() && !(s.pendingQuestion && s.pendingSelections.length > 0))}>
          <Send />
        </Button>
      </div>
      {s.pendingQuestion ? (
        <div className="text-center text-[10px] text-muted-foreground">
          Enter 发送 = 回答当前问题{"(可与已勾选项合并)"}
        </div>
      ) : null}
    </div>
  )
}

// ── PRD 文档 ──────────────────────────────────────────────────────

export function PrdDoc({ prd, writing }: { prd: Prd; writing?: boolean }) {
  return (
    <div className="space-y-4 text-sm">
      <div className="flex items-center gap-2">
        <span className="font-semibold">需求文档</span>
        <Badge variant="outline" className="text-[10px] text-muted-foreground">
          顾问小艾执笔 · 更新于 {prd.updatedAt}
        </Badge>
      </div>
      {prd.sections.map((sec, i) => (
        <section key={sec.title}>
          <h3
            className={cn(
              "mb-1.5 text-xs font-semibold text-muted-foreground",
              writing && i === prd.sections.length - 2 && "animate-pulse text-foreground"
            )}
          >
            {i + 1}. {sec.title}
            {writing && i === prd.sections.length - 2 ? " · 正在写…" : ""}
          </h3>
          <ul className="space-y-1">
            {sec.lines.map((l) => (
              <li key={l} className="leading-relaxed">
                {sec.added?.includes(l) ? (
                  <span className="rounded bg-emerald-500/10 px-1 text-emerald-700 dark:text-emerald-400">
                    {l}　本轮新增
                  </span>
                ) : (
                  l
                )}
              </li>
            ))}
          </ul>
        </section>
      ))}
      <section>
        <h3 className="mb-1.5 text-xs font-semibold text-amber-600">
          7. 待定项 · {prd.pending.length} 条
        </h3>
        <ul className="space-y-1 rounded-xl border border-amber-500/30 bg-amber-500/5 p-3 text-xs leading-relaxed">
          {prd.pending.length === 0 ? (
            <li className="text-muted-foreground">无</li>
          ) : (
            prd.pending.map((p) => <li key={p}>· {p}</li>)
          )}
        </ul>
      </section>
    </div>
  )
}

// ── 直播条目的聊天形态（指令区窄栏）：一行一事、边做边出 ─────────

/**
 * 直播条目（2026-08-30 用户定调）：做系统的过程在指令区像聊天内容一样
 * 一点点展现；系统模式主区域恒为预览。run 中由 ChatFlow 流式渲染（末条
 * running 微亮），run 结束后转为留痕消息（trailing 略淡）。
 */
export function SegChatEntry({
  seg,
  running,
  trailing,
}: {
  seg: Seg
  /** 正在进行的末条（tool 转 spinner） */
  running?: boolean
  /** 已完成留痕（略淡） */
  trailing?: boolean
}) {
  switch (seg.kind) {
    case "text":
      return (
        <div className="flex gap-2">
          <BaAvatar className="size-6 text-[10px]" />
          <div
            className={cn(
              "max-w-[85%] rounded-2xl rounded-tl-sm bg-muted px-3.5 py-2 text-sm leading-relaxed",
              trailing && "opacity-75"
            )}
          >
            {seg.text}
          </div>
        </div>
      )
    case "reasoning":
      return (
        <div className={cn(trailing && "opacity-75")}>
          <ReasoningCollapse text={seg.text} />
        </div>
      )
    case "tool": {
      const Icon = seg.name === "bash" ? Terminal : seg.name === "edit" ? FilePen : FileSearch
      return (
        <div
          className={cn(
            "flex items-center gap-2 rounded-lg border px-2.5 py-1.5 text-xs",
            running ? "border-primary/50 bg-primary/5" : "bg-muted/40",
            trailing && "opacity-75"
          )}
        >
          <Icon className="size-3.5 shrink-0 text-muted-foreground" />
          <span className="shrink-0 font-medium">{seg.name}</span>
          {seg.arg ? (
            <span className="min-w-0 truncate font-mono text-[11px] text-muted-foreground">{seg.arg}</span>
          ) : null}
          {running ? (
            <Spinner className="ml-auto size-3 shrink-0 text-primary" />
          ) : (
            <Check className="ml-auto size-3.5 shrink-0 text-muted-foreground" />
          )}
        </div>
      )
    }
    case "patch":
      return (
        <div
          className={cn(
            "flex items-center gap-2 rounded-lg border bg-muted/30 px-2.5 py-1.5 text-xs",
            trailing && "opacity-75"
          )}
        >
          <FilePen className="size-3.5 shrink-0 text-muted-foreground" />
          <span className="min-w-0 shrink-0 font-medium">{seg.path}</span>
          {seg.summary ? (
            <span className="min-w-0 truncate text-[11px] text-muted-foreground">· {seg.summary}</span>
          ) : null}
          <span className="ml-auto shrink-0 text-emerald-600 dark:text-emerald-400">+{seg.added}</span>
          <span className="shrink-0 text-red-600 dark:text-red-400">−{seg.removed}</span>
        </div>
      )
    case "step":
      return (
        <div className={cn("flex items-center gap-2 py-0.5 text-[11px] text-muted-foreground", trailing && "opacity-75")}>
          <span className="h-px w-4 bg-border" />
          {seg.phase === "start" ? `开始 · ${seg.name}` : `完成 · ${seg.name} ✓`}
        </div>
      )
    case "knowledge":
      return (
        <div className={cn("text-[11px] text-indigo-600 dark:text-indigo-400", trailing && "opacity-75")}>
          💡 参考了 {KNOWLEDGE_HITS.length} 个相似项目的经验
        </div>
      )
    case "finish":
      return (
        <div
          className={cn(
            "flex items-center gap-1.5 text-xs text-emerald-700 dark:text-emerald-400",
            trailing && "opacity-75"
          )}
        >
          <Check className="size-3.5 shrink-0" />
          {seg.text}
        </div>
      )
  }
}

/** reasoning 折叠：思考过程收进可展开块（默认收起；缩进展开） */
function ReasoningCollapse({ text }: { text: string }) {
  const [open, setOpen] = React.useState(false)
  return (
    <Collapsible open={open} onOpenChange={setOpen}>
      <CollapsibleTrigger className="flex items-center gap-1.5 rounded-md px-1 py-0.5 text-xs text-muted-foreground hover:text-foreground">
        <Brain className="size-3.5" />
        思考过程
        <ChevronRight className={cn("size-3 transition-transform", open && "rotate-90")} />
      </CollapsibleTrigger>
      <CollapsibleContent>
        <p className="mt-1 border-l-2 pl-3 text-xs leading-relaxed text-muted-foreground">{text}</p>
      </CollapsibleContent>
    </Collapsible>
  )
}

/** 空态 / 出错态的横幅块（直播页占位等） */
export function MutedBanner({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex items-start gap-2 rounded-md border bg-muted/40 px-3 py-2 text-sm text-muted-foreground">
      <CircleAlert className="mt-0.5 size-4 shrink-0" />
      <span>{children}</span>
    </div>
  )
}

// ── 系统预览 ──────────────────────────────────────────────────────

export function PreviewFrame({
  version,
  showChrome = true,
  className,
}: {
  version: number
  showChrome?: boolean
  className?: string
}) {
  if (version === 0) {
    return (
      <div className={cn("flex h-full flex-col items-center justify-center gap-2 bg-muted/40 text-center", className)}>
        <div className="text-3xl">🐾</div>
        <div className="text-sm text-muted-foreground">聊完需求、点「开始做系统」后，</div>
        <div className="text-sm text-muted-foreground">您的系统会出现在这里</div>
      </div>
    )
  }
  return (
    <div key={version} className={cn("flex h-full min-h-0 flex-col animate-in fade-in duration-700", className)}>
      {showChrome ? (
        <div className="flex h-8 shrink-0 items-center gap-2 border-b bg-muted/50 px-3">
          <span className="size-2.5 rounded-full bg-red-400" />
          <span className="size-2.5 rounded-full bg-amber-400" />
          <span className="size-2.5 rounded-full bg-emerald-400" />
          <span className="ml-2 flex-1 truncate rounded-md bg-background px-2 py-0.5 text-[11px] text-muted-foreground">
            chongai-youjia.app
          </span>
          <Badge variant="outline" className="text-[10px] text-muted-foreground">第 {version} 版</Badge>
        </div>
      ) : null}
      <iframe
        title="系统预览（罐头）"
        srcDoc={previewSrcdoc(version)}
        className="min-h-0 w-full flex-1 border-0 bg-white"
      />
    </div>
  )
}

/** 生成中的预览空窗：不再是进度剧场——浏览器窗 + 一句话（过程在指令区直播） */
export function PreviewBlank({ note }: { note: string }) {
  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex h-8 shrink-0 items-center gap-2 border-b bg-muted/50 px-3">
        <span className="size-2.5 rounded-full bg-red-400" />
        <span className="size-2.5 rounded-full bg-amber-400" />
        <span className="size-2.5 rounded-full bg-emerald-400" />
        <span className="ml-2 flex-1 truncate rounded-md bg-background px-2 py-0.5 text-[11px] text-muted-foreground">
          chongai-youjia.app
        </span>
      </div>
      <div className="flex min-h-0 flex-1 items-center justify-center bg-white">
        <span className="text-xs text-muted-foreground">{note}</span>
      </div>
    </div>
  )
}

// ── 订单 / 用量 ───────────────────────────────────────────────────

export function OrderCard({ desk, compact }: { desk: Desk; compact?: boolean }) {
  const { s, backofficeQuote, pay, cancelOrder } = desk
  const o = s.order
  return (
    <Card className={cn("gap-3 py-4", compact && "py-3")}>
      <CardHeader className="px-4">
        <CardTitle className="flex items-center justify-between text-sm">
          订单
          <Badge
            variant={o.state === "paid" ? "default" : o.state === "quoted" ? "secondary" : "outline"}
            className="text-[10px]"
          >
            {ORDER_TEXT[o.state]}
          </Badge>
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-3 px-4 text-sm">
        {o.state === "none" ? (
          <p className="text-xs leading-relaxed text-muted-foreground">
            系统满意后在右侧「项目信息」里点「确认下单」；下单后平台人工报价，支付完成即交易完成。
          </p>
        ) : (
          <>
            <div className="flex items-baseline justify-between">
              <span className="text-xs text-muted-foreground">报价</span>
              <span className="text-xl font-semibold tabular-nums">{o.price ?? "待定"}</span>
            </div>
            {o.note ? (
              <div className="rounded-lg bg-muted p-2.5 text-xs leading-relaxed text-muted-foreground">{o.note}</div>
            ) : null}
          </>
        )}
        {/* 原型步进：模拟后台动作（真实流程里由后台运营操作） */}
        {o.state === "ordered" ? (
          <div className="flex flex-wrap gap-1.5">
            <Button
              size="xs"
              variant="outline"
              onClick={() => backofficeQuote("¥1,280", "按功能清单 2 项报价；含一年云主机与日常备份。")}
            >
              （原型）后台来报价
            </Button>
            <Button size="xs" variant="ghost" onClick={cancelOrder}>
              取消，回去继续修改
            </Button>
          </div>
        ) : null}
        {o.state === "quoted" ? (
          <div className="flex flex-wrap gap-1.5">
            <Button size="sm" onClick={pay}>
              去支付（原型内模拟）
            </Button>
            <Button size="xs" variant="outline" onClick={() => backofficeQuote("¥1,180", "价格调整：首月云主机费用减免。")}>
              （原型）后台改价
            </Button>
            <Button size="xs" variant="ghost" onClick={cancelOrder}>
              取消，回去继续修改
            </Button>
          </div>
        ) : null}
        {o.state === "paid" ? (
          <p className="text-xs leading-relaxed text-muted-foreground">
            需求文档已存入平台经验库；系统与数据为您保留，后续新需求请开新项目。
          </p>
        ) : null}
      </CardContent>
    </Card>
  )
}

export function UsageCard({ desk }: { desk: Desk }) {
  const { s, totalCost } = desk
  return (
    <Card className="gap-2 py-3">
      <CardHeader className="px-4">
        <CardTitle className="text-xs text-muted-foreground">平台用量 · 计入报价参考</CardTitle>
      </CardHeader>
      <CardContent className="px-4">
        <div className="flex items-baseline justify-between">
          <span className="text-xs text-muted-foreground">
            {s.usage.length === 0
              ? "尚未生成"
              : `${s.usage.length} 次制作（${s.usage.map((u) => u.kind).join("、")}）`}
          </span>
          <span className="text-sm font-semibold tabular-nums">¥{totalCost.toFixed(1)}</span>
        </div>
      </CardContent>
    </Card>
  )
}
