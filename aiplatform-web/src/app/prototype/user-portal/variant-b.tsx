// PROTOTYPE（throwaway）—— T5 变体 B：项目台（订单跟踪式）
// 顶栏 + 门户切换；首页 = 项目卡片网格（每卡六步进度条 + 等你动作行）；
// 详情 = 大 stepper + 「需要你做的」CTA 区 + 最新进展 + 项目信息侧栏。
// 隐喻：淘宝订单跟踪。入口 = 新建项目表单（名称/类型/描述）。
"use client"

import * as React from "react"
import {
  ArrowLeft,
  Check,
  ExternalLink,
  Flag,
  Lightbulb,
  Plus,
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
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Separator } from "@/components/ui/separator"
import { Textarea } from "@/components/ui/textarea"
import { cn } from "@/lib/utils"

import { GateBody, PreviewDialog, QuestionCardBody } from "./bits"
import {
  statusOf,
  USER_STEPS,
  useDemoProjects,
  type DemoProject,
} from "./canned"

// 步骤间的门界标（用户侧三扇 + 开发团队自查一处）
const BOUNDARIES: Record<number, { label: string; you?: boolean }> = {
  0: { label: "确认 PRD", you: true },
  1: { label: "确认原型", you: true },
  3: { label: "团队自查", you: false },
  4: { label: "验收", you: true },
}

export function VariantB() {
  const demo = useDemoProjects()
  const [openId, setOpenId] = React.useState<string | null>(null)
  const project = demo.projects.find((p) => p.id === openId) ?? null

  return (
    <div className="min-h-svh bg-muted/30">
      {/* 顶栏：品牌 + 三门户切换（v1 单账号）+ 新建 */}
      <header className="sticky top-0 z-10 border-b bg-background">
        <div className="mx-auto flex h-13 max-w-6xl items-center gap-4 px-4">
          {project && (
            <Button
              size="icon-sm"
              variant="ghost"
              aria-label="返回项目列表"
              onClick={() => setOpenId(null)}
            >
              <ArrowLeft />
            </Button>
          )}
          <span className="text-sm font-bold">AI 开发平台</span>
          <nav className="flex items-center gap-1 text-sm">
            <span className="rounded-md bg-primary/10 px-2.5 py-1 font-medium text-primary">
              我的项目
            </span>
            {["开发平台", "任务平台"].map((t) => (
              <span
                key={t}
                className="cursor-not-allowed rounded-md px-2.5 py-1 text-muted-foreground/60"
                title="v1 单账号可切换，此处示意"
              >
                {t}
              </span>
            ))}
          </nav>
          <div className="ml-auto flex items-center gap-2">
            <NewProjectButton demo={demo} onCreated={setOpenId} />
            <span className="grid size-7 place-items-center rounded-full bg-primary/15 text-xs font-semibold text-primary">
              王
            </span>
          </div>
        </div>
      </header>

      {project ? (
        <ProjectDetail demo={demo} project={project} />
      ) : (
        <ProjectGrid demo={demo} onOpen={setOpenId} />
      )}
    </div>
  )
}

// —— 首页：项目卡片网格 ——
function ProjectGrid({
  demo,
  onOpen,
}: {
  demo: ReturnType<typeof useDemoProjects>
  onOpen: (id: string) => void
}) {
  return (
    <main className="mx-auto max-w-6xl px-4 py-8">
      <h1 className="mb-1 text-xl font-bold">我的项目</h1>
      <p className="mb-6 text-sm text-muted-foreground">
        每个项目从聊需求到交付共六步，需要你拍板时会明确告诉你。
      </p>
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
                <CardDescription className="line-clamp-1">
                  {p.wish}
                </CardDescription>
              </CardHeader>
              <CardContent className="space-y-3">
                <MiniStepper step={p.step} gate={p.gate} />
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
    </main>
  )
}

// —— 卡片上的六步缩略进度 ——
function MiniStepper({
  step,
  gate,
}: {
  step: number
  gate?: DemoProject["gate"]
}) {
  return (
    <div className="flex items-center gap-0.5" aria-hidden>
      {USER_STEPS.map((s, i) => {
        const gateHere =
          gate === "requirement" && i === 1
            ? true
            : gate === "demo" && i === 2
              ? true
              : gate === "accept" && i === 5
                ? true
                : false
        return (
          <React.Fragment key={s.key}>
            {i > 0 && <span className="h-px flex-1 bg-border" />}
            <span
              className={cn(
                "size-2 rounded-full",
                i < step || (step >= 5 && i === 5)
                  ? "bg-emerald-500"
                  : i === step
                    ? gateHere
                      ? "bg-amber-500 ring-2 ring-amber-500/30"
                      : "bg-primary"
                    : "bg-border"
              )}
            />
          </React.Fragment>
        )
      })}
      <span className="ml-2 text-xs text-muted-foreground">
        {USER_STEPS[Math.min(step, 5)].label}
      </span>
    </div>
  )
}

// —— 新建项目：表单弹窗（表单 + 对话结合的「表单」极）——
function NewProjectButton({
  demo,
  onCreated,
}: {
  demo: ReturnType<typeof useDemoProjects>
  onCreated: (id: string) => void
}) {
  const [open, setOpen] = React.useState(false)
  const [name, setName] = React.useState("")
  const [type, setType] = React.useState("website")
  const [desc, setDesc] = React.useState("")
  const ok = name.trim() && desc.trim()

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger render={<Button size="sm" />}>
        <Plus /> 新建项目
      </DialogTrigger>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>新建项目</DialogTitle>
          <DialogDescription>
            填个大概就行，提交后顾问会陪你把细节聊清楚。
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          <div className="space-y-2">
            <Label>项目名称</Label>
            <Input
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="例如：宠物医院预约官网"
            />
          </div>
          <div className="space-y-2">
            <Label>想要什么类型</Label>
            <Select value={type} onValueChange={(v) => setType(v ?? "website")}>
              <SelectTrigger className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="website">对外网站（展示 / 预约 / 下单）</SelectItem>
                <SelectItem value="tool">内部工具（登记 / 盘点 / 报表）</SelectItem>
                <SelectItem value="mini">手机端页面</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <div className="space-y-2">
            <Label>想解决什么问题</Label>
            <Textarea
              value={desc}
              onChange={(e) => setDesc(e.target.value)}
              placeholder="用大白话描述就行，例如：顾客能在线预约看病时间，店里能导出每天的预约表"
            />
          </div>
        </div>
        <DialogFooter>
          <Button
            disabled={!ok}
            onClick={() => {
              onCreated(demo.create(name.trim(), desc.trim()))
              setOpen(false)
              setName("")
              setDesc("")
            }}
          >
            创建，开始聊需求
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

// —— 详情：大 stepper + 分区 ——
function ProjectDetail({
  demo,
  project,
}: {
  demo: ReturnType<typeof useDemoProjects>
  project: DemoProject
}) {
  const [preview, setPreview] = React.useState(false)
  const [idea, setIdea] = React.useState("")
  const st = statusOf(project)

  return (
    <main className="mx-auto max-w-6xl px-4 py-8">
      <div className="mb-6 flex flex-wrap items-center gap-3">
        <h1 className="text-xl font-bold">
          {project.emoji} {project.name}
        </h1>
        {st.tone === "wait" && (
          <Badge className="bg-amber-500 text-amber-950 hover:bg-amber-500">
            需要你：{st.label}
          </Badge>
        )}
        {project.step >= 1 && project.step < 5 && (
          <Button
            size="sm"
            variant="outline"
            className="ml-auto"
            onClick={() => setPreview(true)}
          >
            <ExternalLink /> 打开{project.step <= 1 ? "原型" : "系统"}
          </Button>
        )}
      </div>

      {/* 大 stepper：六步 + 门界标 */}
      <Card className="mb-6 gap-2 py-5">
        <CardContent className="px-6">
          <div className="flex items-start">
            {USER_STEPS.map((s, i) => {
              return (
                <React.Fragment key={s.key}>
                  <div className="flex min-w-14 flex-1 flex-col items-center gap-1.5 text-center">
                    <span
                      className={cn(
                        "grid size-7 place-items-center rounded-full border text-xs font-semibold",
                        i < project.step || project.step >= 5
                          ? "border-emerald-500 bg-emerald-500 text-white"
                          : i === project.step
                            ? st.tone === "wait"
                              ? "border-amber-500 bg-amber-500/10 text-amber-600 dark:text-amber-400"
                              : "border-primary bg-primary/10 text-primary"
                            : "border-border text-muted-foreground/50"
                      )}
                    >
                      {i < project.step || project.step >= 5 ? <Check className="size-4" /> : i + 1}
                    </span>
                    <span
                      className={cn(
                        "text-xs",
                        i === project.step
                          ? "font-semibold"
                          : "text-muted-foreground/70"
                      )}
                    >
                      {s.label}
                    </span>
                    {i === project.step && (
                      <span className="text-[11px] leading-tight text-muted-foreground">
                        {st.tone === "wait" ? st.label : s.hint}
                      </span>
                    )}
                  </div>
                  {i < USER_STEPS.length - 1 && (
                    <div className="mt-3.5 flex w-8 shrink-0 flex-col items-center gap-1">
                      <span className="h-px w-full bg-border" />
                      {BOUNDARIES[i] && (
                        <span
                          className={cn(
                            "flex items-center gap-0.5 rounded-full px-1.5 text-[10px] leading-4",
                            BOUNDARIES[i].you
                              ? "bg-amber-500/15 text-amber-700 dark:text-amber-400"
                              : "bg-muted text-muted-foreground"
                          )}
                        >
                          {BOUNDARIES[i].you ? <Flag className="size-2.5" /> : null}
                          {BOUNDARIES[i].label}
                        </span>
                      )}
                    </div>
                  )}
                </React.Fragment>
              )
            })}
          </div>
        </CardContent>
      </Card>

      <div className="grid gap-6 lg:grid-cols-3">
        {/* 主栏 */}
        <div className="space-y-6 lg:col-span-2">
          {project.question && (
            <div>
              <SectionTitle>需要你做的</SectionTitle>
              <QuestionCardBody
                data={project.question}
                onDone={() => demo.answer(project.id)}
              />
            </div>
          )}
          {project.gate && (
            <div>
              <SectionTitle>需要你做的</SectionTitle>
              <GateBody
                gate={project.gate}
                projectName={project.name}
                onApprove={() => demo.approve(project.id)}
                onReject={(r) => demo.reject(project.id, r)}
              />
            </div>
          )}
          {!project.question && !project.gate && project.step < 5 && (
            <Card className="py-5">
              <CardContent className="flex items-center gap-3 text-sm text-muted-foreground">
                <span className="relative flex size-2.5">
                  <span className="absolute inline-flex size-full animate-ping rounded-full bg-emerald-400 opacity-60" />
                  <span className="relative inline-flex size-2.5 rounded-full bg-emerald-500" />
                </span>
                {st.label}，不用你操作{project.eta ? `（${project.eta}）` : ""}
              </CardContent>
            </Card>
          )}

          <div>
            <SectionTitle>最新进展</SectionTitle>
            <Card className="py-5">
              <CardContent className="space-y-3">
                {[...project.log].reverse().map((entry, i) => (
                  <div key={i} className="flex gap-3 text-sm">
                    <span className="w-20 shrink-0 text-xs text-muted-foreground">
                      {entry.t}
                    </span>
                    <span className={i === 0 ? "font-medium" : undefined}>
                      {entry.text}
                    </span>
                  </div>
                ))}
              </CardContent>
            </Card>
          </div>

          {project.step >= 5 && (
            <>
              <SectionTitle>交付物</SectionTitle>
              <Card className="border-emerald-500/40 py-5">
                <CardContent className="space-y-3">
                  <p className="text-sm font-semibold text-emerald-600 dark:text-emerald-400">
                    🎉 项目已交付
                  </p>
                  <div className="flex gap-2">
                    <Button size="sm" variant="secondary" disabled>
                      源码包（zip）
                    </Button>
                    <Button size="sm" variant="secondary" disabled>
                      使用说明书
                    </Button>
                  </div>
                </CardContent>
              </Card>
            </>
          )}
        </div>

        {/* 侧栏 */}
        <div className="space-y-6">
          <Card className="gap-3 py-5">
            <CardHeader>
              <CardTitle className="text-sm">项目信息</CardTitle>
            </CardHeader>
            <CardContent className="space-y-2.5 text-sm">
              <p className="text-muted-foreground">
                当初你说：「{project.wish}」
              </p>
              <Separator />
              <p className="flex justify-between text-muted-foreground">
                当前环节
                <span className="text-foreground">
                  {USER_STEPS[Math.min(project.step, 5)].label}
                </span>
              </p>
              <p className="flex justify-between text-muted-foreground">
                最新更新
                <span className="text-foreground">
                  {project.log[project.log.length - 1]?.t}
                </span>
              </p>
            </CardContent>
          </Card>

          <Card className="gap-3 py-5">
            <CardHeader>
              <CardTitle className="flex items-center gap-1.5 text-sm">
                <Lightbulb className="size-4 text-amber-500" /> 下一期想法池
              </CardTitle>
              <CardDescription>
                随时记，不打扰当前项目；做下一期时一起聊
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-2">
              {project.ideas.length === 0 && (
                <p className="text-xs text-muted-foreground">还没有记录</p>
              )}
              {project.ideas.map((idea) => (
                <p key={idea} className="rounded-md bg-muted/60 px-2 py-1.5 text-sm">
                  {idea}
                </p>
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
      </div>

      <PreviewDialog
        open={preview}
        onOpenChange={setPreview}
        projectName={project.name}
        demo={project.step <= 1}
      />
    </main>
  )
}

function SectionTitle({ children }: { children: React.ReactNode }) {
  return (
    <h2 className="mb-2.5 text-sm font-semibold text-muted-foreground">
      {children}
    </h2>
  )
}
