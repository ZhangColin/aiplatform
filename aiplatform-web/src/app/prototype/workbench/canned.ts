// ══════════════════════════════════════════════════════════════════
// PROTOTYPE（throwaway）——wayfinder T4 (#6)：开发平台工作台 UX
//
// 计划：三个结构迥异的变体（A 三栏对话中心 / B 直播舞台 / C 决策
// 收件箱），`?variant=A|B|C` 切换，挂 throwaway 路由 /prototype/workbench。
// 罐头流按 demo 事件类型造样本；无持久化、无真后端。
// ══════════════════════════════════════════════════════════════════

export const PROJECT = {
  id: "PRJ-0012",
  name: "宠物医院预约官网",
  stage: "开发" as const,
  engine: "OpenCode",
  role: "开发工程师",
  // docs/11 §3 七步四门；done 截止到当前步
  steps: ["需求梳理", "Demo", "开发", "测试", "验收", "交付"] as const,
  currentStepIndex: 2,
  owner: "王女士",
  previewUrl: "https://preview.local/prj-0012",
}

export type RunStatus = "running" | "waiting" | "done"

export const RUN = {
  taskId: "TASK-0042",
  title: "实现预约表单页（含手机号校验）",
  status: "running" as RunStatus,
  startedSecondsAgo: 402, // 06:42
  tokens: "≈ 84k",
}

// —— agent 直播事件流（类型对齐 demo：task-start / text / reasoning /
// step-start / step-finish / knowledge-retrieved / patch / error）——

export type ToolStatus = "running" | "done"

export interface FeedEvent {
  kind:
    | "task-start"
    | "text"
    | "reasoning"
    | "tool"
    | "knowledge"
    | "patch"
    | "error"
    | "hitl"
  // kind 细分载荷，各变体自行取用
  text?: string
  reasoning?: string
  tool?: { name: string; arg: string; status: ToolStatus }
  knowledge?: { count: number; items: { source: string; project: string; chunk: string }[] }
  patch?: { file: string; added: number; removed: number; summary: string }
  hitl?: string
}

export const FEED: FeedEvent[] = [
  {
    kind: "task-start",
    text: "实现预约表单页（含手机号校验）",
  },
  {
    kind: "text",
    text: "我先看了一下现有页面结构和路由，预约表单会挂在 /booking 下，复用官网的头部与主题色。接下来读取 PRD 确认字段清单。",
  },
  {
    kind: "tool",
    tool: { name: "read", arg: "docs/PRD-0712.md", status: "done" },
  },
  {
    kind: "reasoning",
    reasoning:
      "PRD 里字段：宠物名、品种、体重、预约日期时段、主人手机号。手机号校验用轻量正则即可，不必引第三方库。日期时段控件现有组件库有现成的，但要确认可禁用过去日期……表单提交走 /api/booking，后端接口文档还没发，先用约定字段写好 client。",
  },
  {
    kind: "knowledge",
    knowledge: {
      count: 2,
      items: [
        {
          source: "PRD · 宠物寄养需求",
          project: "宠物寄养小程序（上单）",
          chunk: "……预约时段需按门店营业时间过滤，节假日不约；手机号必填，验证码后期再上……",
        },
        {
          source: "Bug 记录 · 表单类",
          project: "知识库 · 历史缺陷",
          chunk: "……移动端日期选择器在 iOS 上无法弹出（B-0117），改用 input[type=date] 规避……",
        },
      ],
    },
  },
  {
    kind: "tool",
    tool: { name: "edit", arg: "src/pages/booking/Form.tsx", status: "done" },
  },
  {
    kind: "patch",
    patch: {
      file: "src/pages/booking/Form.tsx",
      added: 148,
      removed: 12,
      summary: "新增预约表单组件：字段清单、手机号正则校验、时段选择（禁用过去日期）",
    },
  },
  {
    kind: "text",
    text: "表单骨架完成。提交前需要确认两件业务上的事，我来发起提问；另外构建命令需要你批准。",
  },
  {
    kind: "hitl",
    hitl: "等待你的回答与审批（2 项）",
  },
  {
    kind: "tool",
    tool: { name: "bash", arg: "pnpm build", status: "running" },
  },
]

// —— HITL：问答卡（demo pendingQuestions 形状：一卡多题）——

export interface Question {
  header: string
  question: string
  multiple: boolean
  custom: boolean
  options: { label: string; description?: string }[]
}

export const QUESTIONS: Question[] = [
  {
    header: "验证码渠道",
    question: "预约提交时的手机验证用哪种方式？",
    multiple: false,
    custom: true,
    options: [
      { label: "短信验证码", description: "需接短信服务商，v1 成本略高" },
      { label: "微信验证码", description: "依赖公众号/小程序体系" },
      { label: "暂不验证", description: "只做格式校验，后期再补" },
    ],
  },
  {
    header: "宠物信息",
    question: "预约时需要收集哪些宠物信息？（可多选）",
    multiple: true,
    custom: true,
    options: [
      { label: "品种" },
      { label: "年龄" },
      { label: "疫苗记录", description: "上传图片附件" },
      { label: "绝育状态" },
    ],
  },
]

// —— HITL：工具审批卡（含终止逃生口）——

export const APPROVAL = {
  id: "APR-0031",
  tool: "bash",
  args: "pnpm build --mode staging",
  reason: "构建产物用于部署预览环境",
  expiresInMin: 30,
}

// —— 决策门（开发完成确认 · v1 拍板的唯一开发平台侧门）——

export const GATE = {
  title: "开发完成确认门",
  state: "locked" as const, // 测试循环未关闭 → 锁
  lockedReason: "测试循环还有 1 条未关闭 Bug，全部复测通过后解锁",
}

// —— 测试外包循环（任务平台联动）——

export const TEST_TASK = {
  id: "TASK-T03",
  title: "首页与预约流程手工测试",
  assignee: "测试一姐（OPC）",
  state: "已提交 · 待确认" as const,
  bugs: [
    { id: "B-0201", title: "预约页在 Safari 下日期控件偏移", status: "已修复 · 待复测" },
    { id: "B-0202", title: "提交成功后 toast 不消失", status: "已修复 · 待复测" },
    { id: "B-0203", title: "手机号输入框无格式提示", status: "待修复" },
  ],
}

// —— 预览（iframe sandbox 口径自 CC：不给 allow-same-origin）——

export const PREVIEW_IFRAME_PROPS = {
  sandbox: "allow-scripts allow-forms allow-popups",
  title: "项目预览",
} as const

export const PREVIEW_SRCDOC = `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><style>
  :root{color-scheme:light}*{box-sizing:border-box}body{margin:0;font-family:-apple-system,"PingFang SC",sans-serif;background:#fafafa;color:#1c2333}
  header{padding:14px 20px;background:#fff;border-bottom:1px solid #e5e7eb;display:flex;align-items:center;gap:10px}
  .logo{width:26px;height:26px;border-radius:8px;background:#308ce8;color:#fff;display:grid;place-items:center;font-size:14px}
  h1{font-size:15px;margin:0;flex:1}
  .btn{background:#308ce8;color:#fff;border:none;border-radius:8px;padding:8px 14px;font-size:13px}
  .wrap{max-width:420px;margin:24px auto;padding:0 16px}
  .card{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:18px}
  label{display:block;font-size:12px;color:#6b7280;margin:12px 0 4px}
  input{width:100%;padding:9px 10px;border:1px solid #d1d5db;border-radius:8px;font-size:14px}
  .ok{margin-top:16px;width:100%;padding:11px;background:#308ce8;border:none;border-radius:8px;color:#fff;font-size:14px}
  .hint{font-size:11px;color:#9ca3af;margin-top:10px;text-align:center}
</style></head><body>
<header><div class="logo">🐾</div><h1>宠爱有家 · 宠物医院</h1><button class="btn">预约挂号</button></header>
<div class="wrap"><div class="card">
  <strong style="font-size:14px">在线预约</strong>
  <label>宠物名</label><input value="布丁">
  <label>品种</label><input value="英短蓝猫">
  <label>预约日期</label><input type="date" value="2026-08-22">
  <label>主人手机号</label><input value="138****8000" placeholder="用于接收确认短信">
  <button class="ok" onclick="this.textContent='已提交（罐头）'">提交预约</button>
  <div class="hint">PROTOTYPE 罐头预览 · sandbox 限制同源</div>
</div></div></body></html>`

// —— 小工具：运行用时（各变体共用，原型级实现）——

export function formatElapsed(totalSeconds: number) {
  const m = Math.floor(totalSeconds / 60)
  const s = totalSeconds % 60
  return `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`
}
