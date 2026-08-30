// ══════════════════════════════════════════════════════════════════
// PROTOTYPE（throwaway）—— wayfinder #6：需求端界面形态
// 第二轮：壳沿用现有工作台布局（PortalSidebar + WorkbenchShell +
// 主面板 line 页签 + 直播时间线大块），槽位内容按新业务重排。
//
// 计划：单变体（原布局 · 新业务适配），挂 throwaway 路由
// /prototype/demand-desk。罐头状态机驱动全生命周期：聊需求 → PRD
// 就绪 → 定稿生成 → 系统就绪 →（意见 → 修正）× N → 确认下单 →
// 报价 → 支付 → 归档。无持久化。
//
// 问答交互（CC / Replit 式，2026-08-30 用户反馈）：单选 chip 点即答；
// 多选才出「提交」；补充或不按选项回答 → 输入框提交即当前问题的答案。
//
// 文案红线（CONTEXT.md）：用户可见面称「系统」，不出现 Demo / 原型 /
// 演示 / 开发 / 构建 / 验收 / 门 / 期；生成中 =「正在为您生成系统」。
// ══════════════════════════════════════════════════════════════════

"use client"

import * as React from "react"

// ── 阶段 ──────────────────────────────────────────────────────────

export type Phase =
  | "chat" // 聊需求：BA 每轮一问进行中
  | "prdReady" // PRD 就绪，等「开始做系统」（定稿）
  | "generating" // 首次生成：直播流水自动推进
  | "ready" // 系统就绪：预览 + 意见输入 + 确认下单
  | "fixing" // 修正中：意见后的修正过程
  | "ordered" // 已下单，待报价（可取消回迭代）
  | "quoted" // 已报价（价格 + 备注 + 去支付 / 取消）
  | "paid" // 已支付 → 已归档，交易完成

/** 顶栏旅程面包屑（新业务主链的用户侧三段） */
export const JOURNEY = ["聊需求", "做系统", "下单支付"] as const

export function journeyStep(p: Phase): number {
  if (p === "chat" || p === "prdReady") return 0
  if (p === "ordered" || p === "quoted" || p === "paid") return 2
  return 1
}

export const systemReady = (p: Phase) =>
  p === "ready" || p === "fixing" || p === "ordered" || p === "quoted" || p === "paid"

/** 「等你」类状态（顶栏徽章琥珀 / 侧栏 badge 同源） */
export function waitingOnYou(p: Phase, hasQuestion: boolean): string | null {
  if (hasQuestion) return "等你回答"
  if (p === "prdReady") return "等您定稿"
  if (p === "ordered") return "等报价"
  if (p === "quoted") return "等您支付"
  return null
}

// ── 对话 ──────────────────────────────────────────────────────────

export interface QuestionOption {
  label: string
  desc?: string
}
export interface QuestionCard {
  id: string
  header: string
  question: string
  multiple?: boolean
  options: QuestionOption[]
}

/** 每轮一问（#2 决议）：三轮就把 PRD 聊出来 */
const ROUNDS: QuestionCard[] = [
  {
    id: "q1",
    header: "给谁用",
    question: "这个系统主要给谁用？",
    options: [
      { label: "给我的顾客用", desc: "对外展示、在线预约这类" },
      { label: "给我自己 / 店里员工用", desc: "登记、盘点这类内部工具" },
    ],
  },
  {
    id: "q2",
    header: "预约要填什么",
    question: "顾客预约时需要填哪些信息？（可多选）",
    multiple: true,
    options: [
      { label: "宠物信息", desc: "名字 / 品种 / 体重" },
      { label: "主人联系方式" },
      { label: "期望日期和时段" },
      { label: "症状描述", desc: "方便医生提前了解" },
    ],
  },
  {
    id: "q3",
    header: "定金",
    question: "预约时需要顾客在线付定金吗？",
    options: [
      { label: "不需要，到店再付" },
      { label: "需要，在线付定金", desc: "会记入待定项，首版先不做在线收款" },
    ],
  },
]

export type Msg =
  | { id: string; role: "ba"; text: string; knowledge?: number }
  | { id: string; role: "user"; text: string }
  | { id: string; role: "event"; text: string }

// ── PRD（七章节，单最新版；v2 = 需求变更后的版本）────────────────

export interface PrdSection {
  title: string
  lines: string[]
  /** 本轮新增的行（意见触发需求变更时高亮） */
  added?: string[]
}

export interface Prd {
  sections: PrdSection[]
  pending: string[]
  updatedAt: string
  /** 需求变更轮次：0 = 初版 */
  rev: number
}

const PRD_V1: Prd = {
  rev: 0,
  updatedAt: "今天 14:32",
  pending: ["定金是否要在线收（首版先不做）", "宠物体重是否必填"],
  sections: [
    {
      title: "需求背景",
      lines: ["店主希望顾客在手机上直接完成预约，减少电话登记占用前台的时间。"],
    },
    {
      title: "目标用户",
      lines: ["宠物主人（手机上自助预约）", "医院前台（查看、导出当天预约）"],
    },
    {
      title: "核心场景",
      lines: ["顾客填宠物信息与期望时段 → 提交 → 前台在列表里看到并逐一确认。"],
    },
    {
      title: "范围边界",
      lines: ["暂不做：在线收款、会员积分（见待定项）。"],
    },
    {
      title: "关键约束",
      lines: ["手机优先；页面风格温暖干净；无需安装，浏览器打开即用。"],
    },
    {
      title: "功能清单",
      lines: [
        "1. 在线预约表单 — 宠物名、品种、日期时段、手机号；手机号格式错时不能提交并提示",
        "2. 店内预约列表 — 按天查看、导出表格（含全部字段）",
      ],
    },
  ],
}

const PRD_V2_DIFF: PrdSection[] = [
  {
    title: "范围边界",
    lines: ["暂不做：在线收款（见待定项）。"],
    added: [],
  },
  {
    title: "功能清单",
    lines: [
      "1. 在线预约表单 — 宠物名、品种、日期时段、手机号；手机号格式错时不能提交并提示",
      "2. 店内预约列表 — 按天查看、导出表格（含全部字段）",
      "3. 会员积分 — 预约成功自动 +10 分，页顶展示累计分",
    ],
    added: ["3. 会员积分 — 预约成功自动 +10 分，页顶展示累计分"],
  },
]

/** 需求变更后的 PRD（功能清单 +1，范围边界收紧） */
const prdV2 = (base: Prd): Prd => ({
  ...base,
  rev: 1,
  updatedAt: "刚刚",
  sections: base.sections.map((sec) => PRD_V2_DIFF.find((d) => d.title === sec.title) ?? sec),
})

// ── 知识命中（#5：成交项目经验，切入之初注入）───────────────────

export interface KnowledgeHit {
  project: string
  chunk: string
}

export const KNOWLEDGE_HITS: KnowledgeHit[] = [
  {
    project: "宠物美容预约小程序（已成交）",
    chunk: "……预约时段需按门店营业时间过滤，节假日不约；手机号必填，验证码后期再上……",
  },
  {
    project: "社区诊所挂号（已成交）",
    chunk: "……导出必须含全部字段，前台习惯按日期排序，别按提交时间……",
  },
]

// ── 直播分段时间线（LivePanel 大块口径：text / reasoning 折叠 /
//    tool 卡 / patch diff / step 边界 / knowledge 横幅 / finish）──

export type PatchLine = { kind: "add" | "remove" | "ctx"; text: string }

export type Seg =
  | { kind: "text"; text: string }
  | { kind: "reasoning"; text: string }
  | { kind: "tool"; name: string; arg?: string }
  | {
      kind: "patch"
      path: string
      added: number
      removed: number
      summary?: string
      lines: PatchLine[]
    }
  | { kind: "step"; phase: "start" | "done"; name: string }
  | { kind: "knowledge" }
  | { kind: "finish"; text: string }

export const GEN_SEGMENTS: Seg[] = [
  { kind: "knowledge" },
  { kind: "step", phase: "start", name: "读需求" },
  { kind: "text", text: "先读需求文档，核对功能清单 2 项：在线预约表单、店内预约列表。" },
  { kind: "tool", name: "read", arg: "docs/需求文档.md" },
  { kind: "step", phase: "done", name: "读需求" },
  { kind: "step", phase: "start", name: "搭页面" },
  {
    kind: "reasoning",
    text: "首页要温暖干净：米白底 + 暖橙点缀，圆润字体；手机优先，先写窄屏布局再放宽屏适配……",
  },
  {
    kind: "patch",
    path: "src/pages/index.html",
    added: 42,
    removed: 3,
    summary: "首页：头部、主题色、手机适配",
    lines: [
      { kind: "ctx", text: "<body>" },
      { kind: "add", text: '  <header class="warm">宠爱有家 · 宠物医院</header>' },
      { kind: "add", text: '  <section class="hero">专业·温柔·方便</section>' },
      { kind: "add", text: '  <meta name="viewport" content="width=device-width">' },
    ],
  },
  { kind: "step", phase: "done", name: "搭页面" },
  { kind: "step", phase: "start", name: "做表单" },
  {
    kind: "patch",
    path: "src/pages/booking.html",
    added: 68,
    removed: 0,
    summary: "预约表单：宠物名 / 品种 / 日期时段 / 手机号（格式校验）",
    lines: [
      { kind: "add", text: '  <label>宠物名</label><input name="pet" required>' },
      { kind: "add", text: '  <label>手机号</label><input name="mobile" pattern="^1\\d{10}$">' },
      { kind: "add", text: '  <button class="ok">提交预约</button>' },
    ],
  },
  { kind: "step", phase: "done", name: "做表单" },
  { kind: "step", phase: "start", name: "连数据" },
  { kind: "tool", name: "bash", arg: "预约数据表就位 · 预置 12 条测试数据" },
  { kind: "step", phase: "done", name: "连数据" },
  { kind: "step", phase: "start", name: "自测" },
  { kind: "text", text: "完整跑一遍：提交预约 → 店内列表立即可见 ✓；手机号格式错时提交被拦下 ✓。" },
  { kind: "step", phase: "done", name: "自测" },
  { kind: "finish", text: "系统已就绪 · 预览已自动刷新，去看看吧" },
]

const fixSegs = (opinion: string, feature: boolean): Seg[] =>
  feature
    ? [
        { kind: "text", text: `「${opinion}」是新需求：先把它写进需求文档，再安排系统更新。` },
        {
          kind: "patch",
          path: "docs/需求文档.md",
          added: 1,
          removed: 1,
          summary: "功能清单 +1 会员积分；范围边界相应收紧",
          lines: [
            { kind: "remove", text: "暂不做：在线收款、会员积分（见待定项）。" },
            { kind: "add", text: "3. 会员积分 — 预约成功自动 +10 分，页顶展示累计分" },
          ],
        },
        { kind: "step", phase: "start", name: "做积分" },
        {
          kind: "patch",
          path: "src/pages/booking.html",
          added: 25,
          removed: 2,
          summary: "页顶积分条 + 预约成功自动 +10 分",
          lines: [
            { kind: "add", text: '  <span class="pts">我的积分 120</span>' },
            { kind: "add", text: '  onBooked(() => points.add(10))' },
          ],
        },
        { kind: "tool", name: "bash", arg: "完整跑一遍积分入账" },
        { kind: "step", phase: "done", name: "做积分" },
        { kind: "finish", text: "系统已更新 · 预览已自动刷新" },
      ]
    : [
        { kind: "text", text: `收到您的意见：「${opinion}」` },
        { kind: "step", phase: "start", name: "修改" },
        { kind: "tool", name: "edit", arg: "预约页按钮样式" },
        {
          kind: "patch",
          path: "src/pages/booking.html",
          added: 2,
          removed: 2,
          summary: "提交按钮改绿色，对比度检查通过",
          lines: [
            { kind: "remove", text: '  <button class="ok">提交预约</button>' },
            { kind: "add", text: '  <button class="ok green">提交预约</button>' },
          ],
        },
        { kind: "step", phase: "done", name: "修改" },
        { kind: "finish", text: "系统已更新 · 预览已自动刷新" },
      ]

// ── 系统预览（罐头 srcdoc，三个版本演示「迭代 → 系统更新」）──────

const petDoc = (o: { green?: boolean; breed?: boolean; points?: boolean }) => `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<style>
body{margin:0;font-family:system-ui,-apple-system,"PingFang SC",sans-serif;color:#3a3226;background:#faf6f0}
header{display:flex;align-items:center;gap:10px;padding:12px 16px;background:#fff;border-bottom:1px solid #eee;position:sticky;top:0}
.logo{width:32px;height:32px;border-radius:10px;background:#ffe4d6;display:grid;place-items:center;font-size:17px}
h1{font-size:15px;margin:0}
.pts{margin-left:auto;font-size:12px;background:#fff4d9;border-radius:99px;padding:4px 10px}
main{padding:16px;max-width:520px;margin:0 auto}
.card{background:#fff;border:1px solid #eee;border-radius:14px;padding:14px 16px;margin-bottom:14px}
.card h2{font-size:13px;margin:0 0 2px}
.card .sub{font-size:11px;color:#a39a8b}
label{display:block;font-size:12px;color:#8a8071;margin:10px 0 4px}
input,select{width:100%;box-sizing:border-box;padding:8px 10px;border:1px solid #e5ddd2;border-radius:9px;font-size:13px;background:#fff}
.row2{display:flex;gap:8px}.row2>*{flex:1}
.ok{width:100%;margin-top:14px;border:0;border-radius:10px;padding:10px;font-size:14px;color:#fff;background:${o.green ? "#2e9e5b" : "#2f7df6"}}
li{font-size:13px;margin:6px 0;display:flex;justify-content:space-between}
.tag{font-size:11px;color:#2e9e5b;background:#e8f7ee;border-radius:6px;padding:2px 6px}
</style></head><body>
<header><div class="logo">🐾</div><h1>宠爱有家 · 宠物医院</h1>${o.points ? '<span class="pts">我的积分 120</span>' : ""}</header>
<main>
<div class="card"><h2>在线预约</h2><div class="sub">填好提交，前台马上能看到</div>
<label>宠物名</label><input value="布丁">
<label>品种</label>${o.breed ? `<select><option>金毛</option><option>柯基</option><option>英短</option><option>其他</option></select>` : `<input value="金毛">`}
<div class="row2"><div><label>预约日期</label><input type="date" value="2026-09-02"></div>
<div><label>时段</label><select><option>09:00–10:00</option><option>10:00–11:00</option><option>16:00–17:00</option></select></div></div>
<label>主人手机号</label><input value="13800001234">
<button class="ok" onclick="this.textContent='已提交 ✓（罐头）'">提交预约</button></div>
<div class="card"><h2>今日预约 · 店内看</h2>
<li><span>布丁 · 疫苗复诊</span><span class="tag">已确认</span></li>
<li><span>可乐 · 洗澡</span><span class="tag">已确认</span></li>
<li><span>妞妞 · 驱虫</span><span class="tag">待确认</span></li>
<div class="sub" style="margin-top:8px">导出表格（含全部字段）</div></div>
</main></body></html>`

/** 系统版本 → 预览内容。v1 初版；v2 按钮改绿 + 品种下拉；v3 再加会员积分 */
export function previewSrcdoc(version: number): string {
  if (version >= 3) return petDoc({ green: true, breed: true, points: true })
  if (version === 2) return petDoc({ green: true, breed: true })
  return petDoc({})
}

// ── 文件树（成果区「文件」模式：目录缩进展开，文件随系统版本长出）──

export interface FileEntry {
  path: string // 树内完整路径（含目录）
  name: string
  /** 文件就位所需的最低系统版本（需求文档 = PRD 就绪即有） */
  minVersion: number
  code: string // 主区域代码视图（罐头片段）
}

export const FILE_TREE: FileEntry[] = [
  {
    path: "docs/需求文档.md",
    name: "需求文档.md",
    minVersion: 0,
    code: "（文档视图，非代码）",
  },
  {
    path: "src/pages/官网首页.html",
    name: "官网首页.html",
    minVersion: 1,
    code: `<header class="warm">宠爱有家 · 宠物医院</header>
<section class="hero">专业 · 温柔 · 方便</section>
<!-- 手机适配：viewport 已设，窄屏优先排版 -->`,
  },
  {
    path: "src/pages/预约表单.html",
    name: "预约表单.html",
    minVersion: 1,
    code: `<label>宠物名</label><input name="pet" required>
<label>品种</label>${"{{ breedSelect }}"}
<label>主人手机号</label><input name="mobile" pattern="^1\\d{10}$">
<button class="ok${"{{ btnClass }}"}">提交预约</button>`,
  },
  {
    path: "src/pages/店内列表.html",
    name: "店内列表.html",
    minVersion: 1,
    code: `<table data-source="预约单">
  <!-- 按天查看 · 导出含全部字段（按日期排序） -->
</table>`,
  },
  {
    path: "src/会员积分.js",
    name: "会员积分.js",
    minVersion: 3,
    code: `onBooked(() => points.add(10))
render(<span class="pts">我的积分 {points.total}</span>)`,
  },
]

/** 系统版本下可见的文件（PRD 就绪即有需求文档） */
export function visibleFiles(systemVersion: number, prdReady: boolean): FileEntry[] {
  return FILE_TREE.filter((f) =>
    f.minVersion === 0 ? prdReady : systemVersion >= f.minVersion
  )
}

// ── 订单 / 用量 ───────────────────────────────────────────────────

export type OrderState = "none" | "ordered" | "quoted" | "paid"

export const ORDER_TEXT: Record<OrderState, string> = {
  none: "未下单",
  ordered: "待报价",
  quoted: "已报价",
  paid: "已支付 · 已归档",
}

export interface UsageRun {
  kind: "生成" | "修正"
  cost: number
}

// ── 状态机 ────────────────────────────────────────────────────────

let seq = 0
const nid = () => `m${++seq}`

interface RunState {
  segs: Seg[]
  index: number
  active: boolean
  kind: "生成" | "修正"
  startedAt: number
}

export interface DeskState {
  phase: Phase
  round: number // 聊需求第几轮（0 起）
  messages: Msg[]
  pendingQuestion?: QuestionCard
  /** 多选题已勾选 chips（单选不需要勾选态：点即答） */
  pendingSelections: string[]
  prd: Prd
  run: RunState
  /** 最近一次已完成的 run（空闲态直播回看用） */
  lastRun?: { segs: Seg[]; kind: "生成" | "修正" }
  queuedOpinions: string[] // run 中提的意见，做完一起处理（#3 决议）
  systemVersion: number
  order: { state: OrderState; price?: string; note?: string }
  usage: UsageRun[]
}

export function useDesk() {
  const [s, setS] = React.useState<DeskState>({
    phase: "chat",
    round: 0,
    pendingQuestion: ROUNDS[0],
    pendingSelections: [],
    messages: [
      { id: nid(), role: "user", text: "想给宠物医院做个能在线预约的网站" },
      {
        id: nid(),
        role: "ba",
        text: "好，接过您的想法。我参考了 2 个相似项目的经验，先问几个关键问题，把需求聊清楚再动手。",
        knowledge: 2,
      },
    ],
    prd: PRD_V1,
    run: { segs: [], index: 0, active: false, kind: "生成", startedAt: 0 },
    queuedOpinions: [],
    systemVersion: 0,
    order: { state: "none" },
    usage: [],
  })

  const push = (msgs: Msg[]) => setS((p) => ({ ...p, messages: [...p.messages, ...msgs] }))

  /** 多选勾选 / 取消勾选 */
  const togglePending = (label: string) =>
    setS((p) => ({
      ...p,
      pendingSelections: p.pendingSelections.includes(label)
        ? p.pendingSelections.filter((x) => x !== label)
        : [...p.pendingSelections, label],
    }))

  /** 聊需求：一轮回答 = 选中项（可空）+ 自由文本（可空），二者至少其一 */
  const answer = (selected: string[], custom?: string) =>
    setS((p) => {
      if (!p.pendingQuestion) return p
      const parts: string[] = []
      if (selected.length > 0) parts.push(selected.join("、"))
      if (custom) parts.push(custom)
      const text = parts.join("；补充：")
      const msgs: Msg[] = [...p.messages, { id: nid(), role: "user", text }]
      const round = p.round + 1
      if (round < ROUNDS.length) {
        msgs.push(
          { id: nid(), role: "ba", text: "明白了，下一个问题。" },
        )
        return {
          ...p,
          messages: msgs,
          round,
          pendingQuestion: ROUNDS[round],
          pendingSelections: [],
        }
      }
      msgs.push(
        {
          id: nid(),
          role: "ba",
          text: "信息够啦。需求文档我已整理好：一共 2 个功能点；有两件事先记在「待定项」，不影响开工。您过目，没问题就点「开始做系统」。",
        },
        { id: nid(), role: "event", text: "需求文档已就绪 · 待定项 2 条" }
      )
      return {
        ...p,
        messages: msgs,
        round,
        pendingQuestion: undefined,
        pendingSelections: [],
        phase: "prdReady",
      }
    })

  /** 定稿：开始做系统（#2 决议：显式确认动作） */
  const finalize = () => {
    setS((p) => ({
      ...p,
      phase: "generating",
      run: { segs: GEN_SEGMENTS, index: 0, active: true, kind: "生成", startedAt: Date.now() },
    }))
    push([{ id: nid(), role: "event", text: "已定稿 · 开始做系统" }])
  }

  /** 意见 → BA 受理判定（#3 决议：实现问题 / 需求变更；run 中的意见排队） */
  const opinion = (text: string) => {
    const feature = text.includes("积分")
    setS((p) => {
      const msgs: Msg[] = [...p.messages, { id: nid(), role: "user", text }]
      if (p.run.active) {
        // run 中的意见排队，本轮做完一起处理，不即时回复
        return { ...p, messages: msgs, queuedOpinions: [...p.queuedOpinions, text] }
      }
      msgs.push({
        id: nid(),
        role: "ba",
        text: feature
          ? "这是新需求：我先把「会员积分」写进需求文档，再安排系统更新。"
          : "这是制作问题，我直接安排修改，改完预览会自动刷新。",
      })
      return {
        ...p,
        messages: msgs,
        prd: feature ? prdV2(p.prd) : p.prd,
        phase: "fixing",
        run: {
          segs: fixSegs(text, feature),
          index: 0,
          active: true,
          kind: "修正",
          startedAt: Date.now(),
        },
      }
    })
  }

  /** 确认下单（#3 决议：首次生成完成后常驻，零迭代可点） */
  const confirmOrder = () => {
    setS((p) => ({ ...p, phase: "ordered", order: { ...p.order, state: "ordered" } }))
    push([{ id: nid(), role: "event", text: "已下单 · 等待报价（可随时取消，回去继续修改）" }])
  }

  const cancelOrder = () => {
    setS((p) => ({ ...p, phase: "ready", order: { state: "none" } }))
    push([{ id: nid(), role: "event", text: "已取消下单 · 回到修改模式" }])
  }

  /** 原型步进：模拟后台报价 / 改价（#4 决议：后台人工报价） */
  const backofficeQuote = (price: string, note: string) => {
    setS((p) => ({ ...p, phase: "quoted", order: { state: "quoted", price, note } }))
    push([{ id: nid(), role: "event", text: `报价已出：${price} · ${note}` }])
  }

  /** 支付（v1 mock；成功即订单 + 项目归档，#4 决议） */
  const pay = () => {
    setS((p) => ({ ...p, phase: "paid", order: { ...p.order, state: "paid" } }))
    push([
      { id: nid(), role: "event", text: "支付成功 · 订单已归档" },
      {
        id: nid(),
        role: "ba",
        text: "交易完成 🎉 本项目的需求文档已存入平台经验库，将来帮到相似项目时会有您的功劳。有新想法欢迎随时再来开新项目。",
      },
    ])
  }

  /** 直播分段时间线自动推进（原型罐头：每 700ms 一段） */
  React.useEffect(() => {
    if (!s.run.active) return
    const t = setInterval(() => {
      setS((p) => {
        if (!p.run.active) return p
        const next = p.run.index + 1
        if (next < p.run.segs.length) return { ...p, run: { ...p.run, index: next } }
        // ── run 完成：完成信号 → 预览自动刷新（#3 决议）──
        const firstGen = p.phase === "generating"
        const version = firstGen ? 1 : p.systemVersion + 1
        // 直播定盘在右侧栏：过程条目不进对话流，完成只落事件行
        const done: Msg[] = [
          {
            id: nid(),
            role: "event",
            text: firstGen ? "系统已生成，预览已就绪" : "系统已更新 · 预览已自动刷新",
          },
        ]
        const queued = p.queuedOpinions
        let extraPhase: Phase = "ready"
        let run: RunState = { segs: [], index: 0, active: false, kind: p.run.kind, startedAt: 0 }
        if (queued.length > 0) {
          // 排队意见合并处理（#3：run 中意见下一轮合并）
          const text = queued[queued.length - 1]
          const feature = text.includes("积分")
          done.push({
            id: nid(),
            role: "ba",
            text: `您趁制作时提的 ${queued.length} 条意见我记下了，现在一起处理：「${text}」安排修改。`,
          })
          run = { segs: fixSegs(text, feature), index: 0, active: true, kind: "修正", startedAt: Date.now() }
          extraPhase = "fixing"
        }
        return {
          ...p,
          phase: extraPhase,
          run,
          lastRun: { segs: p.run.segs, kind: p.run.kind },
          queuedOpinions: [],
          systemVersion: version,
          usage: [...p.usage, { kind: p.run.kind, cost: p.run.kind === "生成" ? 18.6 : 3.2 }],
          messages: [...p.messages, ...done],
          prd: queued.some((q) => q.includes("积分")) && p.prd.rev === 0 ? prdV2(p.prd) : p.prd,
        }
      })
    }, 700)
    return () => clearInterval(t)
  }, [s.run.active])

  return {
    s,
    togglePending,
    answer,
    finalize,
    opinion,
    confirmOrder,
    cancelOrder,
    backofficeQuote,
    pay,
    totalCost: s.usage.reduce((a, b) => a + b.cost, 0),
  }
}

export type Desk = ReturnType<typeof useDesk>
