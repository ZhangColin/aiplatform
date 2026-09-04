/**
 * ============================================================================
 * 原 型 共 享 引 擎 —— 过程呈现（#68 已验证形态，勿当生产代码）
 * ============================================================================
 * 事件 → 对话面状态的纯归约 + 场景脚本。供平台整体 UI 原型（#72）各方向
 * 变体复用：壳各不同，过程呈现只有一份（#68 走查已判「过程效果可以」）。
 * ============================================================================
 */

export type Part =
  | { type: "narration"; text: string }
  | { type: "step"; title: string }
  | { type: "action"; id: string; icon: ActionIcon; label: string; status: "running" | "done" | "failed"; duration?: string }
  | { type: "question"; question: string; options: string[]; picked: number | null }
  | { type: "wrap"; version: number; summary: string; lines: WrapLine[]; files: FileRow[]; stats: Stats }
  | { type: "wrap-failed"; reason: string };

export type ActionIcon = "file" | "doc" | "package" | "palette" | "link" | "search";
export type WrapLine = { side: "doc" | "system"; text: string };
export type FileRow = [name: string, add: number, del: number];
export type Stats = { time: string; files: number; add: number; del: number };

export type Msg =
  | { kind: "user"; text: string }
  | { kind: "agent"; text: string }
  | { kind: "work"; parts: Part[]; status: "growing" | "done" | "failed" };

export type RunState = {
  messages: Msg[];
  previewStage: number; // 0 空 / 1 骨架 / 2 黑白 / 3 彩色绿 / 4 粉色 / 5 粉色+会员
  doc: { pink: boolean; citywide: boolean; member: boolean };
  versions: { n: number; stage: number }[];
  viewing: number | null;
  tab: string; // 各壳自行解释（工作区 tab / 导航项）
  accepting: boolean;
  runActive: boolean;
  waitingAnswer?: boolean;
};

export type Ev = Record<string, unknown> & { t: string };
export type Step = [delayMs: number, ev: Ev];

export function initialState(tab = "preview"): RunState {
  return {
    messages: [],
    previewStage: 0,
    doc: { pink: false, citywide: false, member: false },
    versions: [],
    viewing: null,
    tab,
    accepting: false,
    runActive: false,
  };
}

export function reduce(state: RunState, ev: Ev): RunState {
  const s: RunState = structuredClone(state);
  const lastWork = () =>
    s.messages.filter((m) => m.kind === "work").slice(-1)[0] as Extract<Msg, { kind: "work" }>;
  switch (ev.t) {
    case "user-says":
      s.messages.push({ kind: "user", text: ev.text as string });
      s.accepting = true;
      break;
    case "run-started":
      s.accepting = false;
      s.runActive = true;
      s.messages.push({ kind: "work", parts: [], status: "growing" });
      break;
    case "narration":
      lastWork().parts.push({ type: "narration", text: ev.text as string });
      break;
    case "step":
      lastWork().parts.push({ type: "step", title: ev.title as string });
      break;
    case "action-start":
      lastWork().parts.push({
        type: "action",
        id: ev.id as string,
        icon: ev.icon as ActionIcon,
        label: ev.label as string,
        status: "running",
      });
      break;
    case "action-done":
    case "action-failed": {
      const p = lastWork().parts.find(
        (p): p is Extract<Part, { type: "action" }> => p.type === "action" && p.id === ev.id,
      );
      if (p) {
        p.status = ev.t === "action-done" ? "done" : "failed";
        p.duration = ev.duration as string | undefined;
      }
      break;
    }
    case "question":
      lastWork().parts.push({
        type: "question",
        question: ev.question as string,
        options: ev.options as string[],
        picked: null,
      });
      s.waitingAnswer = true;
      break;
    case "answered": {
      const q = lastWork().parts.find(
        (p): p is Extract<Part, { type: "question" }> => p.type === "question" && p.picked === null,
      );
      if (q) q.picked = ev.choice as number;
      s.waitingAnswer = false;
      break;
    }
    case "preview-stage":
      s.previewStage = ev.stage as number;
      break;
    case "doc-flag":
      s.doc[ev.key as keyof RunState["doc"]] = true;
      break;
    case "run-finished": {
      const w = lastWork();
      w.status = "done";
      const n = s.versions.length + 1;
      s.versions.push({ n, stage: s.previewStage });
      w.parts.push({
        type: "wrap",
        version: n,
        summary: ev.summary as string,
        lines: (ev.lines as WrapLine[]) ?? [],
        files: (ev.files as FileRow[]) ?? [],
        stats: ev.stats as Stats,
      });
      s.runActive = false;
      break;
    }
    case "run-failed": {
      const w = lastWork();
      w.status = "failed";
      w.parts.push({ type: "wrap-failed", reason: ev.reason as string });
      s.runActive = false;
      break;
    }
    case "view-version":
      s.viewing = ev.n as number;
      break;
    case "back-to-current":
      s.viewing = null;
      break;
    case "rollback": {
      const v = s.versions.find((v) => v.n === s.viewing);
      if (!v) break;
      const n = s.versions.length + 1;
      s.versions.push({ n, stage: v.stage });
      s.previewStage = v.stage;
      s.viewing = null;
      s.messages.push({
        kind: "agent",
        text: `已回滚到版本 ${v.n} 的系统样貌，存为新的版本 ${n}（数据不受影响）。`,
      });
      break;
    }
    case "set-tab":
      s.tab = ev.tab as string;
      break;
  }
  return s;
}

export type Scenario = { id: string; name: string; steps: Step[]; retry?: Step[] };

export const SCENARIOS: Scenario[] = [
  {
    id: "first",
    name: "① 第一次做系统",
    steps: [
      [0, { t: "user-says", text: "好，就按这个文档开始做吧" }],
      [1800, { t: "run-started" }],
      [200, { t: "narration", text: "好的，开始为您搭建花店小程序，做好了会在右边看到。" }],
      [600, { t: "step", title: "搭建项目骨架" }],
      [300, { t: "action-start", id: "a1", icon: "file", label: "创建项目结构" }],
      [1900, { t: "action-done", id: "a1", duration: "1.9 秒" }],
      [200, { t: "action-start", id: "a2", icon: "package", label: "安装基础组件" }],
      [5200, { t: "action-done", id: "a2", duration: "5.2 秒" }],
      [400, { t: "step", title: "实现页面" }],
      [500, { t: "preview-stage", stage: 1 }],
      [300, { t: "action-start", id: "a3", icon: "file", label: "编写首页布局" }],
      [2300, { t: "action-done", id: "a3", duration: "2.3 秒" }],
      [200, { t: "action-start", id: "a4", icon: "file", label: "编写商品列表" }],
      [2100, { t: "action-done", id: "a4", duration: "2.1 秒" }],
      [500, { t: "preview-stage", stage: 2 }],
      [400, { t: "step", title: "美化与联调" }],
      [300, { t: "action-start", id: "a5", icon: "palette", label: "调整配色与图片" }],
      [2400, { t: "action-done", id: "a5", duration: "2.4 秒" }],
      [200, { t: "action-start", id: "a6", icon: "link", label: "连接下单流程" }],
      [2200, { t: "action-done", id: "a6", duration: "2.2 秒" }],
      [500, { t: "preview-stage", stage: 3 }],
      [800, {
        t: "run-finished",
        summary: "花店小程序做好了：首页、商品列表、下单流程都能用，点右边的预览试试吧。",
        files: [
          ["src/App.tsx", 120, 0], ["src/pages/Home.tsx", 86, 0],
          ["src/components/FlowerCard.tsx", 64, 0], ["src/components/Cart.tsx", 58, 0],
          ["src/styles/theme.css", 40, 0], ["src/lib/data.ts", 32, 0],
          ["index.html", 12, 0], ["package.json", 6, 0],
        ],
        stats: { time: "1 分 36 秒", files: 8, add: 418, del: 0 },
      }],
    ],
  },
  {
    id: "recolor",
    name: "② 改颜色（文档+系统都改）",
    steps: [
      [0, { t: "user-says", text: "整体换成粉色系吧，温馨一点" }],
      [1500, { t: "run-started" }],
      [200, { t: "narration", text: "好的，把系统整体换成粉色系，需求文档里的配色说明也同步更新。" }],
      [500, { t: "action-start", id: "b1", icon: "doc", label: "更新需求文档「整体风格」章节" }],
      [1700, { t: "action-done", id: "b1", duration: "1.7 秒" }],
      [150, { t: "doc-flag", key: "pink" }],
      [150, { t: "action-start", id: "b2", icon: "palette", label: "修改全局配色" }],
      [1900, { t: "action-done", id: "b2", duration: "1.9 秒" }],
      [200, { t: "action-start", id: "b3", icon: "file", label: "调整按钮与卡片样式" }],
      [1600, { t: "action-done", id: "b3", duration: "1.6 秒" }],
      [400, { t: "preview-stage", stage: 4 }],
      [700, {
        t: "run-finished",
        summary: "首页和卡片都换成粉色系了，文档里的配色说明也同步改了。",
        lines: [
          { side: "doc", text: "文档：更新了「整体风格」章节，配色改为粉色系" },
          { side: "system", text: "系统：全局配色、按钮与卡片样式换成粉色" },
        ],
        files: [["src/styles/theme.css", 28, 30], ["src/components/FlowerCard.tsx", 18, 8], ["src/components/Cart.tsx", 12, 4]],
        stats: { time: "42 秒", files: 3, add: 58, del: 42 },
      }],
    ],
  },
  {
    id: "doc-only",
    name: "③ 改配送说明（只改文档）",
    steps: [
      [0, { t: "user-says", text: "配送范围写到全城吧，别限制 3 公里" }],
      [1500, { t: "run-started" }],
      [200, { t: "narration", text: "好的，我先把文档里的配送说明改成全程配送，再看看系统页面用不用跟着动。" }],
      [400, { t: "action-start", id: "c1", icon: "doc", label: "更新需求文档「配送说明」" }],
      [1600, { t: "action-done", id: "c1", duration: "1.6 秒" }],
      [150, { t: "doc-flag", key: "citywide" }],
      [200, { t: "action-start", id: "c2", icon: "search", label: "检查系统页面是否写死配送范围" }],
      [1400, { t: "action-done", id: "c2", duration: "1.4 秒" }],
      [700, {
        t: "run-finished",
        summary: "配送说明已经改成全程配送了。",
        lines: [
          { side: "doc", text: "文档：「配送说明」改为全程配送，删除 3 公里限制" },
          { side: "system", text: "系统无需改动：页面上没有写死配送范围，都以文档为准" },
        ],
        files: [["docs/需求文档.md", 6, 3]],
        stats: { time: "18 秒", files: 1, add: 6, del: 3 },
      }],
    ],
  },
  {
    id: "question",
    name: "④ 中途被追问",
    steps: [
      [0, { t: "user-says", text: "加一个会员充值功能" }],
      [1600, { t: "run-started" }],
      [200, { t: "narration", text: "好的。会员充值有个地方需要您定一下，我才好往下做。" }],
      [600, {
        t: "question",
        question: "会员余额想怎么用？",
        options: ["充多少送多少，比如充 100 送 20", "只存余额，不搞赠送", "先不做赠送，以后再说"],
      }],
      [300, { t: "narration", text: "明白，按您选的来。" }],
      [400, { t: "action-start", id: "d1", icon: "doc", label: "更新需求文档「会员充值」章节" }],
      [1500, { t: "action-done", id: "d1", duration: "1.5 秒" }],
      [150, { t: "doc-flag", key: "member" }],
      [200, { t: "action-start", id: "d2", icon: "file", label: "编写会员充值页面" }],
      [2200, { t: "action-done", id: "d2", duration: "2.2 秒" }],
      [200, { t: "action-start", id: "d3", icon: "link", label: "连接余额支付" }],
      [1800, { t: "action-done", id: "d3", duration: "1.8 秒" }],
      [400, { t: "preview-stage", stage: 5 }],
      [700, {
        t: "run-finished",
        summary: "会员充值做好了：会员页可以充值，下单时能直接用余额付。",
        lines: [
          { side: "doc", text: "文档：新增「会员充值」章节" },
          { side: "system", text: "系统：新增会员充值页，下单支持余额支付" },
        ],
        files: [["src/pages/Member.tsx", 92, 0], ["src/lib/balance.ts", 45, 0], ["docs/需求文档.md", 24, 0]],
        stats: { time: "51 秒", files: 3, add: 161, del: 0 },
      }],
    ],
  },
  {
    id: "failure",
    name: "⑤ 做失败了",
    steps: [
      [0, { t: "user-says", text: "接入微信支付" }],
      [1600, { t: "run-started" }],
      [200, { t: "narration", text: "好的，我来接微信支付。" }],
      [400, { t: "action-start", id: "e1", icon: "file", label: "编写支付下单" }],
      [2000, { t: "action-done", id: "e1", duration: "2.0 秒" }],
      [200, { t: "action-start", id: "e2", icon: "link", label: "编写支付回调" }],
      [6500, { t: "action-failed", id: "e2" }],
      [600, { t: "run-failed", reason: "微信支付需要真实商户号才能接通，现在只有测试环境，试了好几次都连不上。" }],
    ],
    retry: [
      [500, { t: "run-started" }],
      [200, { t: "narration", text: "我换平台的测试商户号再试一次。" }],
      [300, { t: "action-start", id: "e3", icon: "link", label: "用测试商户号联调支付" }],
      [2600, { t: "action-done", id: "e3", duration: "2.6 秒" }],
      [200, { t: "action-start", id: "e4", icon: "file", label: "补上支付回调" }],
      [1900, { t: "action-done", id: "e4", duration: "1.9 秒" }],
      [700, {
        t: "run-finished",
        summary: "微信支付接好了：下单可以调起支付。正式上线前还需要您提供真实商户号。",
        lines: [{ side: "system", text: "系统：新增微信支付下单与回调" }],
        files: [["src/lib/pay.ts", 78, 0], ["src/pages/Checkout.tsx", 34, 6]],
        stats: { time: "47 秒", files: 2, add: 112, del: 6 },
      }],
    ],
  },
];

function playStepsInstant(s: RunState, steps: Step[]): RunState {
  for (const [, ev] of steps) {
    s = reduce(s, ev);
    if (s.waitingAnswer) s = reduce(s, { t: "answered", choice: 0 });
  }
  return s;
}

/** 场景预置：开场访谈上下文 + 把排在前面的场景瞬间放完。 */
export function preludeState(uptoIdx: number, tab = "preview"): RunState {
  let s = initialState(tab);
  s.messages.push({ kind: "user", text: "我想给花店做个小程序，客人能看花、下单" });
  s.messages.push({
    kind: "agent",
    text: "好的，需求我已经问清楚并写成了文档（在右边「文档」里）。您看看，没问题的话跟我说一声，我就开始做了。",
  });
  for (let i = 0; i < uptoIdx; i++) {
    s = playStepsInstant(s, SCENARIOS[i].steps);
    if (SCENARIOS[i].retry) s = playStepsInstant(s, SCENARIOS[i].retry!);
  }
  return s;
}
