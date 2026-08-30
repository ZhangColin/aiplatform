// PROTOTYPE（throwaway）—— T5 共享原子：问答卡体 / 门卡体 / 预览弹窗
// 元素级共享（≈共享 <Header>），三个变体各自决定布局与信息层级。
"use client"

import * as React from "react"
import { CheckCircle2, ExternalLink, MessageSquarePlus } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group"
import { Separator } from "@/components/ui/separator"
import { Textarea } from "@/components/ui/textarea"

import {
  GATES,
  type GateKey,
  type QuestionCard as QuestionCardData,
  REQUIREMENT_ITEMS,
} from "./canned"

// —— 问答卡体（demo pendingQuestions 形状；spec 0001 §5：一卡多题、
//    单选/多选/自定义、选项可带说明）——
export function QuestionCardBody({
  data,
  onDone,
  compact,
}: {
  data: QuestionCardData
  onDone: () => void
  compact?: boolean
}) {
  const [picked, setPicked] = React.useState<Record<number, string[]>>(
    Object.fromEntries(data.questions.map((_, i) => [i, []]))
  )
  const [customs, setCustoms] = React.useState<Record<number, string>>({})
  const allAnswered = data.questions.every(
    (q, i) =>
      picked[i]?.length > 0 || (q.custom && (customs[i] ?? "").trim().length > 0)
  )

  const toggle = (i: number, label: string, multiple?: boolean) =>
    setPicked((prev) => {
      const arr = prev[i] ?? []
      return {
        ...prev,
        [i]: multiple
          ? arr.includes(label)
            ? arr.filter((x) => x !== label)
            : [...arr, label]
          : [label],
      }
    })

  return (
    <Card className={compact ? "gap-3 py-4" : "gap-4 py-5"}>
      <CardHeader className="gap-1">
        <CardDescription className="flex items-center gap-1.5 text-amber-600 dark:text-amber-400">
          <MessageSquarePlus className="size-4" />
          {data.from}向你提问 · 回答后继续
        </CardDescription>
        <CardTitle className="text-base">
          {data.questions.length} 个小问题
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-5">
        {data.questions.map((q, qi) => (
          <div key={qi} className="space-y-2.5">
            <p className="text-sm leading-relaxed">
              <span className="font-medium">
                {q.header}：
              </span>
              {q.question}
            </p>
            <div className="space-y-2">
              {q.options.map((opt) => (
                <label
                  key={opt.label}
                  className="flex min-h-10 cursor-pointer items-center gap-2.5 rounded-lg border px-3 py-2 text-sm transition-colors has-[button[data-state=checked]]:border-primary has-[button[data-state=checked]]:bg-primary/5"
                >
                  {q.multiple ? (
                    <Checkbox
                      checked={(picked[qi] ?? []).includes(opt.label)}
                      onCheckedChange={() => toggle(qi, opt.label, true)}
                    />
                  ) : (
                    <RadioGroup
                      value={picked[qi]?.[0] ?? ""}
                      onValueChange={(v) => toggle(qi, v)}
                      className="gap-0"
                    >
                      <RadioGroupItem value={opt.label} />
                    </RadioGroup>
                  )}
                  <span className="flex-1">
                    {opt.label}
                    {opt.description ? (
                      <span className="block text-xs text-muted-foreground">
                        {opt.description}
                      </span>
                    ) : null}
                  </span>
                </label>
              ))}
            </div>
            {q.custom ? (
              <Input
                placeholder="也可以直接输入你自己的答案…"
                value={customs[qi] ?? ""}
                onChange={(e) =>
                  setCustoms((prev) => ({ ...prev, [qi]: e.target.value }))
                }
                className="h-9"
              />
            ) : null}
          </div>
        ))}
        <Button className="w-full" disabled={!allAnswered} onClick={onDone}>
          {allAnswered ? "提交回答" : "每题选一个（或填写）后可提交"}
        </Button>
      </CardContent>
    </Card>
  )
}

// —— 驳回/补充框：说明必填、原样转给对方（CC「需求变更必填说明」模式）——
function RejectBox({
  verb,
  onSubmit,
}: {
  verb: string // 「提补充」/「要求修改」/「反馈问题」
  onSubmit: (reason: string) => void
}) {
  const [open, setOpen] = React.useState(false)
  const [reason, setReason] = React.useState("")
  const ok = reason.trim().length >= 5

  if (!open)
    return (
      <Button variant="outline" onClick={() => setOpen(true)}>
        {verb}
      </Button>
    )

  return (
    <div className="space-y-2 rounded-lg border border-amber-500/40 bg-amber-500/5 p-3">
      <p className="text-xs text-amber-700 dark:text-amber-400">
        请具体说一说（必填，会原样转给{verb.includes("补充") ? "顾问" : "团队"}）：
      </p>
      <Textarea
        autoFocus
        value={reason}
        onChange={(e) => setReason(e.target.value)}
        placeholder="例如：预约页面希望加上疫苗提醒的选项……"
        className="min-h-16 text-sm"
      />
      <div className="flex justify-end gap-2">
        <Button size="sm" variant="ghost" onClick={() => setOpen(false)}>
          先不提了
        </Button>
        <Button
          size="sm"
          disabled={!ok}
          onClick={() => {
            onSubmit(reason.trim())
            setOpen(false)
            setReason("")
          }}
        >
          提交{verb.slice(0, 1)}
        </Button>
      </div>
    </div>
  )
}

// —— 门卡体：三扇门共用骨架，内容由 gate 决定 ——
export function GateBody({
  gate,
  projectName,
  onApprove,
  onReject,
  onIdea,
}: {
  gate: GateKey
  projectName: string
  onApprove: () => void
  onReject: (reason: string) => void
  onIdea?: (text: string) => void
}) {
  const [preview, setPreview] = React.useState(false)
  const [idea, setIdea] = React.useState("")

  return (
    <Card className="border-primary/40 shadow-sm ring-1 ring-primary/10">
      <CardHeader className="gap-1">
        <CardDescription className="font-medium text-primary">
          需要你拍板 · {GATES[gate].label}
        </CardDescription>
        <CardTitle className="text-base">{GATES[gate].waiting}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        {gate === "requirement" && (
          <div className="space-y-1.5 rounded-lg border p-3">
            <p className="text-xs font-medium text-muted-foreground">
              顾问整理的 PRD
            </p>
            {REQUIREMENT_ITEMS.map((item) => (
              <p key={item} className="flex gap-2 text-sm leading-relaxed">
                <CheckCircle2 className="mt-0.5 size-4 shrink-0 text-emerald-500" />
                {item}
              </p>
            ))}
            <p className="pt-1 text-xs text-muted-foreground">
              确认后团队开始做原型；不对的地方点下方按钮提补充。
            </p>
          </div>
        )}

        {gate !== "requirement" && (
          <Button variant="secondary" className="w-full" onClick={() => setPreview(true)}>
            <ExternalLink /> 打开{gate === "demo" ? "原型" : "系统"}体验一下
          </Button>
        )}

        {gate === "accept" && (
          <div className="flex items-center gap-2 rounded-lg border border-dashed p-3">
            <Input
              value={idea}
              onChange={(e) => setIdea(e.target.value)}
              placeholder="顺手记个新想法，将来做下一期用（可不填）"
              className="h-9"
            />
            <Button
              size="sm"
              variant="ghost"
              disabled={!idea.trim()}
              onClick={() => {
                onIdea?.(idea.trim())
                setIdea("")
              }}
            >
              记下来
            </Button>
          </div>
        )}

        <Separator />
        <div className="flex flex-wrap items-center gap-2">
          <Button onClick={onApprove}>{GATES[gate].approve}</Button>
          <RejectBox
            verb={
              gate === "requirement"
                ? "提补充"
                : gate === "demo"
                  ? "要求修改"
                  : "反馈问题"
            }
            onSubmit={onReject}
          />
        </div>
      </CardContent>

      <PreviewDialog
        open={preview}
        onOpenChange={setPreview}
        projectName={projectName}
        demo={gate === "demo"}
      />
    </Card>
  )
}

// —— 预览弹窗：假浏览器壳 + 罐头页面（真实形态在独立窗口，此处示意）——
export function PreviewDialog({
  open,
  onOpenChange,
  projectName,
  demo,
}: {
  open: boolean
  onOpenChange: (v: boolean) => void
  projectName: string
  demo?: boolean
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl gap-0 overflow-hidden p-0">
        <DialogHeader className="sr-only">
          <DialogTitle>{projectName} · 预览</DialogTitle>
          <DialogDescription>
            {demo ? "可点击原型" : "系统"}预览示意
          </DialogDescription>
        </DialogHeader>
        <div className="flex items-center gap-2 border-b bg-muted/60 px-4 py-2.5">
          <span className="flex gap-1.5">
            <i className="size-2.5 rounded-full bg-red-400" />
            <i className="size-2.5 rounded-full bg-amber-400" />
            <i className="size-2.5 rounded-full bg-emerald-400" />
          </span>
          <span className="flex-1 truncate rounded-md bg-background px-3 py-1 text-xs text-muted-foreground">
            preview.local/{projectName}
          </span>
          <Badge variant="secondary" className="h-5">
            {demo ? "原型" : "系统"}演示
          </Badge>
        </div>
        <div className="max-h-[60vh] overflow-y-auto bg-background p-8">
          <div className="mx-auto max-w-md space-y-4 text-center">
            <p className="text-4xl">🐾</p>
            <h3 className="text-xl font-bold">{projectName}</h3>
            <p className="text-sm text-muted-foreground">
              这里是系统真实页面的示意位置——原型阶段用于确认长相，
              验收阶段用于亲手体验。
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
      </DialogContent>
    </Dialog>
  )
}
