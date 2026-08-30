// PROTOTYPE（throwaway）—— T5 变体 A：对话优先（CC 首页模式）
// 首页 = 居中大输入「一句话说出你想做的」+ 紧凑项目行；详情 = 全屏
// 对话流，问答卡 / 门卡嵌在流底部，底部输入随时补充需求。
// 隐喻：跟项目顾问聊微信。入口 = 零摩擦一句话。
"use client"

import * as React from "react"
import {
  ArrowLeft,
  BadgeCheck,
  CircleDashed,
  Send,
  Sparkles,
} from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { cn } from "@/lib/utils"

import { GateBody, QuestionCardBody } from "./bits"
import {
  statusOf,
  USER_STEPS,
  useDemoProjects,
  type DemoProject,
} from "./canned"

const EXAMPLES = [
  "给宠物医院做个在线预约网站",
  "咖啡豆小店官网，能展示价格",
  "仓库进出登记，月底导出表格",
]

export function VariantA() {
  const demo = useDemoProjects()
  const [openId, setOpenId] = React.useState<string | null>(null)
  const project = demo.projects.find((p) => p.id === openId) ?? null

  return (
    <div className="min-h-svh bg-background">
      <header className="sticky top-0 z-10 border-b bg-background/80 backdrop-blur">
        <div className="mx-auto flex h-13 max-w-3xl items-center gap-2 px-4">
          {project ? (
            <Button
              size="icon-sm"
              variant="ghost"
              aria-label="返回项目列表"
              onClick={() => setOpenId(null)}
            >
              <ArrowLeft />
            </Button>
          ) : (
            <span className="flex items-center gap-1.5 text-sm font-semibold">
              <Sparkles className="size-4 text-primary" /> 造物台
            </span>
          )}
          {project ? (
            <span className="truncate text-sm font-semibold">
              {project.emoji} {project.name}
            </span>
          ) : (
            <span className="text-xs text-muted-foreground">
              不用懂技术 · 像聊天一样把想做的做出来
            </span>
          )}
          <div className="ml-auto flex items-center gap-2">
            <span className="grid size-7 place-items-center rounded-full bg-primary/15 text-xs font-semibold text-primary">
              王
            </span>
          </div>
        </div>
      </header>

      {project ? (
        <ProjectChat demo={demo} project={project} />
      ) : (
        <Home demo={demo} onOpen={setOpenId} />
      )}
    </div>
  )
}

// —— 首页：零摩擦一句话入口 ——
function Home({
  demo,
  onOpen,
}: {
  demo: ReturnType<typeof useDemoProjects>
  onOpen: (id: string) => void
}) {
  const [text, setText] = React.useState("")

  const start = (wish: string) => {
    onOpen(demo.create(wish, wish))
  }

  return (
    <main className="mx-auto max-w-3xl px-4 pb-24">
      <section className="py-16 text-center sm:py-20">
        <h1 className="mb-3 text-2xl font-bold sm:text-3xl">
          说说你想做什么，我们帮你做出来
        </h1>
        <p className="mb-8 text-sm text-muted-foreground">
          一句话就行 —— 顾问会陪你聊清楚需求，做好后请你验收
        </p>
        <div className="mx-auto flex max-w-xl gap-2">
          <Input
            autoFocus
            value={text}
            onChange={(e) => setText(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && text.trim() && start(text.trim())}
            placeholder="例如：给宠物医院做个在线预约的网站"
            className="h-12 flex-1 rounded-full text-base"
          />
          <Button
            size="lg"
            className="h-12 rounded-full px-6"
            disabled={!text.trim()}
            onClick={() => start(text.trim())}
          >
            开始聊
          </Button>
        </div>
        <div className="mt-4 flex flex-wrap justify-center gap-2">
          {EXAMPLES.map((e) => (
            <button
              key={e}
              onClick={() => start(e)}
              className="rounded-full border px-3 py-1 text-xs text-muted-foreground transition-colors hover:border-primary/40 hover:text-foreground"
            >
              {e}
            </button>
          ))}
        </div>
      </section>

      <section>
        <h2 className="mb-3 text-sm font-semibold text-muted-foreground">
          我的项目
        </h2>
        <ul className="divide-y rounded-xl border">
          {demo.projects.map((p) => {
            const st = statusOf(p)
            return (
              <li key={p.id}>
                <button
                  onClick={() => onOpen(p.id)}
                  className="flex w-full items-center gap-3 px-4 py-3.5 text-left transition-colors hover:bg-muted/40"
                >
                  <span className="text-xl">{p.emoji}</span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-sm font-medium">
                      {p.name}
                    </span>
                    <span className="block truncate text-xs text-muted-foreground">
                      {st.label}
                    </span>
                  </span>
                  {st.tone === "wait" && (
                    <Badge className="bg-amber-500 text-amber-950 hover:bg-amber-500">
                      等你
                    </Badge>
                  )}
                  {st.tone === "done" && (
                    <Badge variant="secondary" className="gap-1">
                      <BadgeCheck className="size-3" /> 已交付
                    </Badge>
                  )}
                  <span className="hidden text-xs text-muted-foreground sm:block">
                    {p.log[p.log.length - 1]?.t}
                  </span>
                </button>
              </li>
            )
          })}
        </ul>
      </section>
    </main>
  )
}

// —— 详情：全屏对话流 ——
type Msg = { role: "user" | "advisor"; text: string }

function ProjectChat({
  demo,
  project,
}: {
  demo: ReturnType<typeof useDemoProjects>
  project: DemoProject
}) {
  const [msgs, setMsgs] = React.useState<Msg[]>([])
  const [input, setInput] = React.useState("")
  const st = statusOf(project)

  const send = () => {
    const text = input.trim()
    if (!text) return
    setMsgs((m) => [
      ...m,
      { role: "user", text },
      { role: "advisor", text: "收到！我记下了，会整理进需求；有疑问随时在这里问我。" },
    ])
    setInput("")
  }

  return (
    <>
      {/* 细进度胶囊：六个小点，聊胜于无的存在感 */}
      <div className="border-b bg-muted/30">
        <div className="mx-auto flex max-w-2xl items-center gap-1.5 overflow-x-auto px-4 py-2 text-xs">
          {USER_STEPS.map((s, i) => (
            <React.Fragment key={s.key}>
              {i > 0 && <CircleDashed className="size-3 shrink-0 text-muted-foreground/40" />}
              <span
                className={cn(
                  "shrink-0",
                  i < project.step
                    ? "text-emerald-600 dark:text-emerald-400"
                    : i === project.step
                      ? "font-semibold text-foreground"
                      : "text-muted-foreground/60"
                )}
              >
                {s.label}
              </span>
            </React.Fragment>
          ))}
        </div>
      </div>

      <main className="mx-auto max-w-2xl px-4 pb-40 pt-6">
        <div className="space-y-4">
          <Pill>项目「{project.name}」创建成功 · {project.wish}</Pill>
          <Bubble role="advisor">
            你好，我是你的项目顾问小艾 👋 这个项目由我陪你走完：先聊清楚需求 →
            你确认原型 → 团队制作 → 你验收后交付。过程中有任何想法，直接在下面输入框告诉我就行。
          </Bubble>

          {project.log.map((entry, i) => (
            <Pill key={i}>{entry.t} · {entry.text}</Pill>
          ))}

          {msgs.map((m, i) => (
            <Bubble key={i} role={m.role}>
              {m.text}
            </Bubble>
          ))}

          {/* 流底部：当前等待物 —— 问答卡 / 门卡 / 安心卡 / 交付卡 */}
          {project.question && (
            <QuestionCardBody
              data={project.question}
              onDone={() => demo.answer(project.id)}
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
            <Card className="py-4">
              <CardContent className="flex items-center gap-3 text-sm text-muted-foreground">
                <span className="relative flex size-2.5">
                  <span className="absolute inline-flex size-full animate-ping rounded-full bg-emerald-400 opacity-60" />
                  <span className="relative inline-flex size-2.5 rounded-full bg-emerald-500" />
                </span>
                {st.label}，不用你操作
                {project.eta ? <span>（{project.eta}）</span> : null}
              </CardContent>
            </Card>
          )}
          {project.step >= 5 && <DeliverCard project={project} />}
        </div>
      </main>

      {/* 底部输入：补充需求 / 提问（改动必填原因的口径写进 placeholder） */}
      <div className="fixed inset-x-0 bottom-0 border-t bg-background/90 backdrop-blur">
        <div className="mx-auto flex max-w-2xl items-center gap-2 px-4 py-3">
          <Input
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && send()}
            placeholder={
              project.step >= 5
                ? "项目已交付；新想法会记到下一期…"
                : "补充需求或提问（改动请说明原因，顾问会跟你确认）…"
            }
            className="h-11 flex-1 rounded-full"
          />
          <Button
            size="icon"
            className="size-11 rounded-full"
            aria-label="发送"
            disabled={!input.trim()}
            onClick={send}
          >
            <Send />
          </Button>
        </div>
      </div>
    </>
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
  role,
  children,
}: {
  role: "user" | "advisor"
  children: React.ReactNode
}) {
  return (
    <div
      className={cn(
        "max-w-[85%] rounded-2xl px-4 py-2.5 text-sm leading-relaxed",
        role === "user"
          ? "ml-auto rounded-br-md bg-primary text-primary-foreground"
          : "rounded-bl-md border bg-background"
      )}
    >
      {role === "advisor" && (
        <p className="mb-1 text-xs font-medium text-primary">顾问小艾</p>
      )}
      {children}
    </div>
  )
}

function DeliverCard({ project }: { project: DemoProject }) {
  return (
    <Card className="border-emerald-500/40">
      <CardContent className="space-y-3 py-4">
        <p className="text-sm font-semibold text-emerald-600 dark:text-emerald-400">
          🎉 项目已交付，以下东西归你了
        </p>
        <div className="flex flex-wrap gap-2">
          <Button size="sm" variant="secondary" disabled>
            源码包（zip）
          </Button>
          <Button size="sm" variant="secondary" disabled>
            使用说明书
          </Button>
        </div>
        <p className="text-xs text-muted-foreground">
          （演示原型：按钮置灰示意，真实交付物由后端提供下载）
        </p>
        {project.ideas.length > 0 && (
          <div className="rounded-lg border border-dashed p-3">
            <p className="mb-1 text-xs font-medium text-muted-foreground">
              记到下一期的新想法
            </p>
            {project.ideas.map((idea) => (
              <p key={idea} className="text-sm">
                · {idea}
              </p>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  )
}
