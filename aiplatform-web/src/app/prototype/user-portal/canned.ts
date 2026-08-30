// ══════════════════════════════════════════════════════════════════
// PROTOTYPE（throwaway）——wayfinder T5 (#7)：需求端门户 UX
//
// 计划：三个结构迥异的变体（A 对话优先·CC 首页模式 / B 项目台·订单
// 跟踪式 / C 跟着走向导），`?variant=A|B|C` 切换，挂 throwaway 路由
// /prototype/user-portal。罐头项目覆盖用户视角全部状态；无持久化。
//
// 文案红线（票面）：用户不懂技术——不出现「阶段/状态机/智能体/期」
// 这类词；需求梳理=「聊需求」，Demo=「看原型」，测试=「质检」。
// ══════════════════════════════════════════════════════════════════

import * as React from "react"

// —— 用户视角六步（docs/11 §3 七步四门的用户侧呈现；开发完成门是
//    开发平台侧动作，用户视角折叠进「测试中→验收」之间，不单列）——
export const USER_STEPS = [
  { key: "chat", label: "聊需求", hint: "顾问陪你把想做的事聊清楚" },
  { key: "demo", label: "看原型", hint: "先看可点击的原型，确认长相" },
  { key: "dev", label: "制作中", hint: "团队按确认的需求制作系统" },
  { key: "test", label: "质检中", hint: "质检团队逐项检查系统" },
  { key: "accept", label: "验收", hint: "亲手体验，你来拍板" },
  { key: "deliver", label: "交付", hint: "拿到源码包和使用说明" },
] as const

export type GateKey = "requirement" | "demo" | "accept"

export const GATES: Record<
  GateKey,
  { label: string; waiting: string; approve: string; reject: string }
> = {
  requirement: {
    label: "确认 PRD",
    waiting: "PRD 已整理好，等你确认",
    approve: "确认无误，开始做原型",
    reject: "有补充 / 不对的地方",
  },
  demo: {
    label: "确认原型",
    waiting: "原型做好了，等你打开看看",
    approve: "满意，按这个做",
    reject: "要改一改",
  },
  accept: {
    label: "验收",
    waiting: "系统已可以体验，等你验收",
    approve: "验收通过，交付",
    reject: "还有问题",
  },
}

// —— 问答卡（demo pendingQuestions 形状：一卡多题，spec 0001 §5）——
export interface QuestionOption {
  label: string
  description?: string
}
export interface Question {
  header: string
  question: string
  multiple?: boolean
  custom?: boolean
  options: QuestionOption[]
}
export interface QuestionCard {
  id: string
  from: string
  questions: Question[]
}

export const BA_FIRST_ROUND: QuestionCard = {
  id: "q-first",
  from: "项目顾问",
  questions: [
    {
      header: "给谁用",
      question: "做出来的东西主要给谁用？",
      multiple: false,
      custom: true,
      options: [
        { label: "给我的顾客用", description: "对外展示 / 在线预约下单这类" },
        { label: "给我自己或团队用", description: "内部登记 / 盘点这类工具" },
      ],
    },
    {
      header: "什么时候要用",
      question: "希望什么时候能用上？",
      multiple: false,
      custom: true,
      options: [
        { label: "越快越好", description: "先跑起来，细节慢慢调" },
        { label: "不急，做扎实点", description: "两周上下都可以" },
      ],
    },
  ],
}

const BOOKING_QUESTIONS: QuestionCard = {
  id: "q-101",
  from: "项目顾问",
  questions: [
    {
      header: "谁来预约",
      question: "主要是谁在网站上填预约信息？",
      multiple: false,
      custom: true,
      options: [
        { label: "宠物主人自己", description: "顾客在家自己操作" },
        { label: "医院前台代客登记", description: "店内员工代填" },
      ],
    },
    {
      header: "预约要填什么",
      question: "预约时需要填哪些信息？（可多选）",
      multiple: true,
      custom: true,
      options: [
        { label: "宠物信息", description: "名字 / 品种 / 体重" },
        { label: "主人联系方式" },
        { label: "期望日期和时段" },
        { label: "症状描述", description: "方便医生提前了解" },
      ],
    },
    {
      header: "定金",
      question: "预约时需要在线付定金吗？",
      multiple: false,
      custom: false,
      options: [
        { label: "不需要，到店再付" },
        { label: "需要，在线付定金" },
      ],
    },
  ],
}

// —— PRD（需求确认门展示用）——
export const REQUIREMENT_ITEMS = [
  "宠物主人可在线填写预约（宠物信息、联系方式、日期时段）",
  "手机号必填并校验格式",
  "医院可在后台查看 / 导出当天预约列表",
  "页面风格：温暖干净，支持手机浏览",
  "暂不做：在线支付、会员积分（可记入下一期）",
]

export interface LogEntry {
  t: string
  text: string
}

export interface DemoProject {
  id: string
  name: string
  emoji: string
  wish: string // 用户当初的一句话
  step: number // USER_STEPS 下标
  gate?: GateKey // 等用户拍板的门
  question?: QuestionCard // 等用户回答的问答卡
  eta?: string // 安心态预计时间
  log: LogEntry[]
  ideas: string[] // 下期需求池
}

const initialProjects: DemoProject[] = [
  {
    id: "p1",
    name: "宠物医院预约官网",
    emoji: "🐶",
    wish: "想给宠物医院做个能在线预约的网站",
    step: 0,
    question: BOOKING_QUESTIONS,
    log: [
      { t: "昨天 14:20", text: "顾问小艾接手，开始陪你聊需求" },
      { t: "昨天 14:22", text: "小艾提了 3 个问题，等你回答" },
    ],
    ideas: [],
  },
  {
    id: "p2",
    name: "咖啡豆小店官网",
    emoji: "🫘",
    wish: "卖咖啡豆的小官网，能展示豆子和价格就行",
    step: 0,
    gate: "requirement",
    log: [
      { t: "8 月 16 日", text: "和顾问聊完需求" },
      { t: "8 月 18 日", text: "PRD 整理好了，等你确认" },
    ],
    ideas: [],
  },
  {
    id: "p3",
    name: "亲子活动报名",
    emoji: "🎈",
    wish: "周末亲子活动的报名页，家长填信息就行",
    step: 1,
    gate: "demo",
    log: [
      { t: "8 月 12 日", text: "PRD 已确认" },
      { t: "8 月 19 日", text: "可点击的原型做好了，等你打开看看" },
    ],
    ideas: [],
  },
  {
    id: "p4",
    name: "仓库进出登记",
    emoji: "📦",
    wish: "仓库东西进出记一笔，月底能导出表格",
    step: 2,
    eta: "预计 8 月 22 日有进展",
    log: [
      { t: "8 月 8 日", text: "原型已确认，团队开工" },
      { t: "8 月 15 日", text: "登记和导出功能做完了，正在做权限" },
    ],
    ideas: [],
  },
  {
    id: "p5",
    name: "门店库存盘点",
    emoji: "🧮",
    wish: "店里每周盘一次货，手机上能录入数量",
    step: 3,
    eta: "质检通常 1–2 天",
    log: [
      { t: "8 月 17 日", text: "制作完成，进入质检" },
      { t: "昨天 09:10", text: "质检团队正在逐项检查，不用你操作" },
    ],
    ideas: [],
  },
  {
    id: "p6",
    name: "员工风采墙",
    emoji: "🎨",
    wish: "公司内网展示员工风采，能点赞和留言",
    step: 4,
    gate: "accept",
    log: [
      { t: "8 月 14 日", text: "质检通过，可以体验了" },
      { t: "8 月 14 日", text: "等你打开体验并验收" },
    ],
    ideas: [],
  },
  {
    id: "p7",
    name: "会议纪要小站",
    emoji: "📝",
    wish: "团队周会的纪要放一个页面上，能搜索",
    step: 5,
    log: [
      { t: "8 月 6 日", text: "你验收通过" },
      { t: "8 月 6 日", text: "源码包和使用说明已交付，项目完成 🎉" },
    ],
    ideas: ["纪要能不能按人筛选", "想要个手机上的快捷入口"],
  },
]

// —— 用户视角状态徽章 ——
export function statusOf(p: DemoProject): { label: string; tone: "wait" | "calm" | "done" | "busy" } {
  if (p.step >= 5) return { label: "已交付", tone: "done" }
  if (p.gate) return { label: GATES[p.gate].waiting, tone: "wait" }
  if (p.question) return { label: `等你回答 ${p.question.questions.length} 个问题`, tone: "wait" }
  if (p.step === 2 || p.step === 3) return { label: USER_STEPS[p.step].hint, tone: "calm" }
  return { label: USER_STEPS[p.step].hint, tone: "busy" }
}

export function needsYou(p: DemoProject): string | null {
  if (p.gate) return GATES[p.gate].label
  if (p.question) return `回答顾问的 ${p.question.questions.length} 个问题`
  return null
}

// —— 交互 hook：三个变体各自持一份实例（切换变体即重置）——
export function useDemoProjects() {
  const [projects, setProjects] = React.useState(initialProjects)
  const [now, setNow] = React.useState<string>("刚刚")

  const patch = React.useCallback(
    (id: string, fn: (p: DemoProject) => DemoProject) =>
      setProjects((ps) => ps.map((p) => (p.id === id ? fn(p) : p))),
    []
  )

  const log = (p: DemoProject, text: string): LogEntry[] => [
    ...p.log,
    { t: "刚刚", text },
  ]

  /** 问答卡提交：问题清空，顾问去整理 PRD */
  const answer = React.useCallback(
    (id: string) =>
      patch(id, (p) => ({
        ...p,
        question: undefined,
        gate: "requirement",
        log: log(p, "回答已提交，顾问正在整理 PRD……"),
      })),
    [patch]
  )

  /** 门通过：推进一步，落一条动态 */
  const approve = React.useCallback(
    (id: string) =>
      patch(id, (p) => {
        const step = Math.min(5, p.step + 1)
        const text =
          p.gate === "requirement"
            ? "你确认了 PRD，团队开始做原型"
            : p.gate === "demo"
              ? "你确认了原型，团队开始制作系统"
              : "你验收通过，源码包和使用说明已交付 🎉"
        return {
          ...p,
          gate: undefined,
          step,
          eta: step === 2 ? "预计 8 月 26 日有进展" : step === 3 ? "质检通常 1–2 天" : undefined,
          log: log(p, text),
        }
      }),
    [patch]
  )

  /** 门驳回：停留原地，说明必填且原样转给对方（CC 模式） */
  const reject = React.useCallback(
    (id: string, reason: string) =>
      patch(id, (p) => ({
        ...p,
        gate: undefined,
        log: log(
          p,
          p.gate === "requirement"
            ? `你对 PRD 提了补充：「${reason}」，顾问会据此修改`
            : p.gate === "demo"
              ? `你要求修改原型：「${reason}」，团队会改好后再次请你确认`
              : `你反馈了问题：「${reason}」，团队会修复后再次请你验收`
        ),
      })),
    [patch]
  )

  /** 新想法记入下期需求池 */
  const addIdea = React.useCallback(
    (id: string, text: string) =>
      patch(id, (p) => ({
        ...p,
        ideas: [...p.ideas, text],
        log: log(p, `新想法已记录（将来做下一期时用）：「${text}」`),
      })),
    [patch]
  )

  /** 一句话 / 表单建项目：直接进聊需求，顾问第一轮问题就位 */
  const create = React.useCallback(
    (name: string, wish: string) => {
      const id = `new-${Date.now()}`
      setProjects((ps) => [
        {
          id,
          name,
          emoji: "✨",
          wish,
          step: 0,
          question: BA_FIRST_ROUND,
          log: [
            { t: "刚刚", text: `项目「${name}」创建成功` },
            { t: "刚刚", text: "顾问小艾接手，开始陪你聊需求" },
          ],
          ideas: [],
        },
        ...ps,
      ])
      return id
    },
    []
  )

  return { projects, answer, approve, reject, addIdea, create, now, setNow }
}
