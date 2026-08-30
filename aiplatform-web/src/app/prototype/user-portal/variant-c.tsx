// PROTOTYPE（throwaway）—— T5 变体 C：跟着走向导（待办优先）
// 首页 = 「现在等你拍板」聚合卡置顶 + 项目旅程行；详情 = 单栏纵向旅程
// 时间线（已完成折叠 / 当前大卡展开 / 未来灰置预告——预期管理）。
// 隐喻：物流时间线 + 新手引导。入口 = 三步引导式开场（想做什么→给谁
// 用→什么时候要），不给用户一张白表单。
"use client"

import * as React from "react"
import {
  ArrowLeft,
  Check,
  ChevronRight,
  Lock,
  Plus,
  Sparkles,
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
} from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Progress } from "@/components/ui/progress"
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group"
import { Textarea } from "@/components/ui/textarea"
import { cn } from "@/lib/utils"

import { GateBody, QuestionCardBody } from "./bits"
import {
  statusOf,
  USER_STEPS,
  useDemoProjects,
  type DemoProject,
} from "./canned"

// 每步完成后的「结果句」（时间线已折叠行用）
const STEP_DONE_TEXT: Record<number, string> = {
  0: "需求聊清楚了，PRD 已确认",
  1: "原型看过了，你点了满意",
  2: "系统制作完成",
  3: "质检逐项检查通过",
  4: "你亲手体验后验收通过",
}

export function VariantC() {
  const demo = useDemoProjects()
  const [openId, setOpenId] = React.useState<string | null>(null)
  const [wizard, setWizard] = React.useState(false)
  const project = demo.projects.find((p) => p.id === openId) ?? null
  const waiting = demo.projects.filter(
    (p) => p.question || (p.gate && p.step < 5)
  )

  return (
    <div className="min-h-svh bg-background">
      <header className="sticky top-0 z-10 border-b bg-background/80 backdrop-blur">
        <div className="mx-auto flex h-13 max-w-2xl items-center gap-2 px-4">
          {project && (
            <Button
              size="icon-sm"
              variant="ghost"
              aria-label="返回"
              onClick={() => setOpenId(null)}
            >
              <ArrowLeft />
            </Button>
          )}
          <span className="flex items-center gap-1.5 text-sm font-semibold">
            <Sparkles className="size-4 text-primary" /> 造物台
          </span>
          <span className="grid size-7 place-items-center rounded-full bg-primary/15 text-xs font-semibold text-primary ml-auto">
            王
          </span>
        </div>
      </header>

      {project ? (
        <Journey demo={demo} project={project} />
      ) : (
        <main className="mx-auto max-w-2xl space-y-8 px-4 py-8 pb-28">
          <div>
            <h1 className="mb-1 text-xl font-bold">王女士，下午好 👋</h1>
            <p className="text-sm text-muted-foreground">
              需要你做的事都列在下面，其余的交给团队。
            </p>
          </div>

          {/* 现在等你：跨项目聚合 */}
          <section>
            <h2 className="mb-3 text-sm font-semibold text-muted-foreground">
              现在等你
            </h2>
            {waiting.length === 0 ? (
              <Card className="py-5">
                <CardContent className="flex items-center gap-3 text-sm text-muted-foreground">
                  <Check className="size-4 text-emerald-500" />
                  都处理好了，剩下的事团队在做，不用你盯着。
                </CardContent>
              </Card>
            ) : (
              <div className="space-y-2.5">
                {waiting.map((p) => {
                  const st = statusOf(p)
                  return (
                    <Card
                      key={p.id}
                      onClick={() => setOpenId(p.id)}
                      className="cursor-pointer gap-2 border-amber-500/40 bg-amber-500/[0.04] py-4 transition-colors hover:bg-amber-500/10"
                    >
                      <CardContent className="flex items-center gap-3 px-4">
                        <span className="text-lg">{p.emoji}</span>
                        <span className="min-w-0 flex-1">
                          <span className="block truncate text-sm font-medium">
                            {p.name}
                          </span>
                          <span className="block truncate text-xs text-muted-foreground">
                            {st.label}
                          </span>
                        </span>
                        <ChevronRight className="size-4 shrink-0 text-muted-foreground" />
                      </CardContent>
                    </Card>
                  )
                })}
              </div>
            )}
          </section>

          {/* 项目旅程行 */}
          <section>
            <h2 className="mb-3 text-sm font-semibold text-muted-foreground">
              我的项目
            </h2>
            <ul className="space-y-2">
              {demo.projects.map((p) => {
                const st = statusOf(p)
                return (
                  <li key={p.id}>
                    <button
                      onClick={() => setOpenId(p.id)}
                      className="flex w-full items-center gap-3 rounded-xl border px-4 py-3 text-left transition-colors hover:bg-muted/40"
                    >
                      <span className="text-lg">{p.emoji}</span>
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-sm font-medium">
                          {p.name}
                        </span>
                        <span className="block truncate text-xs text-muted-foreground">
                          {p.log[p.log.length - 1]?.text}
                        </span>
                      </span>
                      <Badge
                        variant={st.tone === "wait" ? "default" : "secondary"}
                        className={cn(
                          "shrink-0 font-normal",
                          st.tone === "wait" && "bg-amber-500 text-amber-950 hover:bg-amber-500"
                        )}
                      >
                        {USER_STEPS[Math.min(p.step, 5)].label}
                      </Badge>
                    </button>
                  </li>
                )
              })}
            </ul>
          </section>
        </main>
      )}

      {/* 底部大 CTA：引导式开场 */}
      {!project && (
        <div className="fixed inset-x-0 bottom-16 border-t bg-background/90 p-3 backdrop-blur">
          <div className="mx-auto max-w-2xl">
            <Button className="h-11 w-full text-base" onClick={() => setWizard(true)}>
              <Plus /> 说个新想法，开始一个项目
            </Button>
          </div>
        </div>
      )}

      <StartWizard
        open={wizard}
        onOpenChange={setWizard}
        onCreated={(id) => {
          setWizard(false)
          setOpenId(id)
        }}
        demo={demo}
      />
    </div>
  )
}

// —— 三步引导式开场 ——
function StartWizard({
  open,
  onOpenChange,
  onCreated,
  demo,
}: {
  open: boolean
  onOpenChange: (v: boolean) => void
  onCreated: (id: string) => void
  demo: ReturnType<typeof useDemoProjects>
}) {
  const [step, setStep] = React.useState(0)
  const [wish, setWish] = React.useState("")
  const [who, setWho] = React.useState("")
  const [when, setWhen] = React.useState("")

  const titles = ["想做什么？", "给谁用？", "什么时候要用？"]
  const canNext = step === 0 ? wish.trim().length >= 5 : step === 1 ? !!who : !!when

  const finish = () => {
    onCreated(demo.create(wish.trim(), `${wish.trim()}（给${who}用，${when}）`))
    setStep(0)
    setWish("")
    setWho("")
    setWhen("")
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>
            {titles[step]}
            <span className="ml-2 text-xs font-normal text-muted-foreground">
              {step + 1} / 3
            </span>
          </DialogTitle>
          <DialogDescription>
            {step === 0 && "大白话就行，不用想措辞。"}
            {step === 1 && "这决定做成什么样子。"}
            {step === 2 && "不着急的话可以选从容，做得更扎实。"}
          </DialogDescription>
        </DialogHeader>

        {step === 0 && (
          <div className="space-y-2">
            <Textarea
              autoFocus
              value={wish}
              onChange={(e) => setWish(e.target.value)}
              placeholder="例如：想给宠物医院做个网站，顾客能自己约看病时间"
              className="min-h-24"
            />
            <Progress value={wish.trim() ? 100 : 20} className="h-1" />
          </div>
        )}
        {step === 1 && (
          <RadioGroup value={who} onValueChange={setWho} className="gap-2.5">
            {[
              ["顾客", "对外展示、在线预约或下单这类"],
              ["我自己 / 团队", "内部登记、盘点、报表这类工具"],
            ].map(([v, d]) => (
              <Label
                key={v}
                className="flex cursor-pointer items-start gap-2.5 rounded-lg border p-3 font-normal has-[button[data-state=checked]]:border-primary has-[button[data-state=checked]]:bg-primary/5"
              >
                <RadioGroupItem value={v} className="mt-0.5" />
                <span>
                  <span className="block font-medium">{v}</span>
                  <span className="block text-xs text-muted-foreground">{d}</span>
                </span>
              </Label>
            ))}
          </RadioGroup>
        )}
        {step === 2 && (
          <div className="grid grid-cols-3 gap-2">
            {["越快越好", "一周左右", "不急，做扎实"].map((v) => (
              <button
                key={v}
                onClick={() => setWhen(v)}
                className={cn(
                  "rounded-lg border p-3 text-sm transition-colors",
                  when === v
                    ? "border-primary bg-primary/10 font-medium text-primary"
                    : "hover:bg-muted/50"
                )}
              >
                {v}
              </button>
            ))}
          </div>
        )}

        <DialogFooter>
          {step > 0 && (
            <Button variant="ghost" onClick={() => setStep(step - 1)}>
              上一步
            </Button>
          )}
          {step < 2 ? (
            <Button disabled={!canNext} onClick={() => setStep(step + 1)}>
              下一步
            </Button>
          ) : (
            <Button disabled={!canNext} onClick={finish}>
              好了，开始吧
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

// —— 详情：纵向旅程时间线 ——
function Journey({
  demo,
  project,
}: {
  demo: ReturnType<typeof useDemoProjects>
  project: DemoProject
}) {
  const [idea, setIdea] = React.useState("")
  const st = statusOf(project)
  const done = project.step >= 5

  return (
    <main className="mx-auto max-w-2xl px-4 py-8 pb-24">
      <div className="mb-2 flex items-center gap-2">
        <h1 className="text-xl font-bold">
          {project.emoji} {project.name}
        </h1>
        <Badge
          variant={st.tone === "wait" ? "default" : "secondary"}
          className={cn(
            st.tone === "wait" && "bg-amber-500 text-amber-950 hover:bg-amber-500"
          )}
        >
          {USER_STEPS[Math.min(project.step, 5)].label}
        </Badge>
      </div>
      <p className="mb-3 text-sm text-muted-foreground">
        整体进度 {Math.min(project.step + (done ? 0 : 1), 6)} / 6
      </p>
      <Progress
        value={((done ? 6 : project.step + 1) / 6) * 100}
        className="mb-8 h-1.5"
      />

      <div className="relative space-y-3">
        {/* 连接竖线 */}
        <span className="absolute bottom-4 left-[15px] top-4 w-px bg-border" />

        {USER_STEPS.map((s, i) => {
          const isPast = i < project.step
          const isCurrent = i === project.step && !done
          if (i > project.step && !done) return null

          return (
            <div key={s.key} className="relative flex gap-4">
              {/* 节点圆 */}
              <span
                className={cn(
                  "relative z-10 mt-0.5 grid size-8 shrink-0 place-items-center rounded-full border-2 text-xs",
                  isPast
                    ? "border-emerald-500 bg-emerald-500 text-white"
                    : isCurrent
                      ? st.tone === "wait"
                        ? "animate-pulse border-amber-500 bg-amber-500/15 text-amber-600 dark:text-amber-400"
                        : "border-primary bg-primary/10 text-primary"
                      : "border-emerald-500 bg-emerald-500 text-white"
                )}
              >
                <Check className="size-4" />
              </span>

              <div className="min-w-0 flex-1 pb-2">
                {isPast ? (
                  <div className="pt-1.5">
                    <p className="flex items-center gap-2 text-sm text-muted-foreground">
                      <span className="font-medium text-foreground">{s.label}</span>
                      <span className="text-xs">{STEP_DONE_TEXT[i]}</span>
                    </p>
                  </div>
                ) : (
                  <CurrentStepCard demo={demo} project={project} stepLabel={s.label} />
                )}
              </div>
            </div>
          )
        })}

        {/* 已交付：末节点后的总结卡 */}
        {done && (
          <div className="relative flex gap-4">
            <span className="relative z-10 mt-0.5 grid size-8 shrink-0 place-items-center rounded-full border-2 border-emerald-500 bg-emerald-500 text-white">
              🎉
            </span>
            <div className="flex-1 pb-2">
              <Card className="border-emerald-500/40">
                <CardHeader className="gap-1">
                  <CardTitle className="text-base">项目完成，东西归你了</CardTitle>
                  <CardDescription>
                    源码包和使用说明书随时可再下载
                  </CardDescription>
                </CardHeader>
                <CardContent className="flex flex-wrap gap-2">
                  <Button size="sm" variant="secondary" disabled>
                    源码包（zip）
                  </Button>
                  <Button size="sm" variant="secondary" disabled>
                    使用说明书
                  </Button>
                </CardContent>
              </Card>
            </div>
          </div>
        )}

        {/* 未来预告：预期管理 */}
        {!done && (
          <div className="space-y-2 pt-2">
            <p className="flex items-center gap-1.5 pl-12 text-xs text-muted-foreground/60">
              <Lock className="size-3" /> 接下来（不用你操作的部分我们不啰嗦）：
            </p>
            {USER_STEPS.slice(project.step + 1).map((s) => (
              <p key={s.key} className="pl-12 text-xs text-muted-foreground/50">
                {s.label} · {s.hint}
              </p>
            ))}
          </div>
        )}
      </div>

      {/* 补充想法 */}
      <div className="mt-8 flex gap-2">
        <Input
          value={idea}
          onChange={(e) => setIdea(e.target.value)}
          placeholder={
            done
              ? "新想法会记到下一期，想做了随时说…"
              : "想补充或修改什么？说了顾问会跟你确认…"
          }
          onKeyDown={(e) => {
            if (e.key === "Enter" && idea.trim()) {
              demo.addIdea(project.id, idea.trim())
              setIdea("")
            }
          }}
        />
        <Button
          variant="outline"
          disabled={!idea.trim()}
          onClick={() => {
            demo.addIdea(project.id, idea.trim())
            setIdea("")
          }}
        >
          说一声
        </Button>
      </div>
      {project.ideas.length > 0 && (
        <div className="mt-3 rounded-lg border border-dashed p-3">
          <p className="mb-1 text-xs font-medium text-muted-foreground">
            已记下（{done ? "做下一期时用" : "会跟需求一起确认"}）
          </p>
          {project.ideas.map((x) => (
            <p key={x} className="text-sm">· {x}</p>
          ))}
        </div>
      )}
    </main>
  )
}

// 当前步骤大卡
function CurrentStepCard({
  demo,
  project,
  stepLabel,
}: {
  demo: ReturnType<typeof useDemoProjects>
  project: DemoProject
  stepLabel: string
}) {
  const st = statusOf(project)

  return (
    <Card
      className={cn(
        "gap-3 py-5",
        st.tone === "wait" && "border-amber-500/50 shadow-sm"
      )}
    >
      <CardHeader className="gap-1">
        <CardTitle className="text-base">
          现在这一步：{stepLabel}
        </CardTitle>
        <CardDescription>{USER_STEPS[project.step].hint}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
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
        {!project.question && !project.gate && (
          <p className="flex items-center gap-2.5 rounded-lg bg-muted/50 px-3 py-2.5 text-sm text-muted-foreground">
            <span className="relative flex size-2.5">
              <span className="absolute inline-flex size-full animate-ping rounded-full bg-emerald-400 opacity-60" />
              <span className="relative inline-flex size-2.5 rounded-full bg-emerald-500" />
            </span>
            {st.label}，不用你操作
            {project.eta ? <span>（{project.eta}）</span> : null}
          </p>
        )}
      </CardContent>
    </Card>
  )
}
