/**
 * ============================================================================
 * 原 型 —— 设计过程体验（#278）：状态归约＋双场景脚本（一次性，勿当生产代码）
 * ============================================================================
 * 血统承 proto/_shared/run-engine（事件归约＋定时播放），扩展三类设计语义：
 * ①设计物清单计划区＋出稿活性行（复用真直播卡 WorkSnapshot 形状）；
 * ②候选分代（drafts 带 gen，旧代不覆盖）＋稿到达（占位→到达/界面类分阶段）；
 * ③gate 交互续跑点——播放停在等用户动作（改稿发言/挑选定稿/下单/支付），
 * 动作即分支跳续（探索轮可改稿可定稿，两路都通）。
 * 媒体按稿 id 从 media.ts 查（平面=TOWEL_DRAFTS、界面=COFFEE_DESIGNS 分阶段）。
 * ============================================================================
 */

import type { GenerationSegmentFact } from "@/lib/projects/detail";
import type { WorkPart, WorkSnapshot } from "@/lib/store/work-message";

import { COFFEE_DESIGNS, draftName, TOWEL_DRAFTS, TOWEL_SPEC, type SpecTokens } from "./media";

export type ScenarioId = "towel" | "coffee";

export type Draft = {
  id: string;
  /** 媒体正身 id（id 可能带代次后缀，媒体/命名/规范都查这个）。 */
  media: string;
  item: string;
  gen: number;
  state: "placeholder" | "arrived";
  /** 界面类渐进阶段 0-4（0=占位）。 */
  stage: number;
};

/** 稿 id：第一代＝媒体 id 本身；改稿代加 -gN 后缀（同媒体可多代重用）。 */
export function draftIdFor(mediaId: string, gen: number): string {
  return gen <= 1 ? mediaId : `${mediaId}-g${gen}`;
}

/** 点哪改哪的改稿弹药（item → 第二代起复用的媒体集）。 */
export const REV_MEDIA: Record<string, string[]> = {
  main: ["tw-2-1", "tw-2-2", "tw-2-3"],
  tag: ["tag-2-1", "tag-2-2"],
  home: ["cf-2-1", "cf-2-2"],
  menu: ["cf-m3", "cf-m4"],
};

export type DesignItem = {
  id: string;
  title: string;
  brief: string;
  status: "pending" | "active" | "done";
  finalized?: string;
};

export type Annotation = {
  id: string;
  /** 相对系统预览舞台的百分比几何。 */
  x: number;
  y: number;
  w: number;
  h: number;
  text?: string;
  sent: boolean;
};

export type ChatMsg =
  | { kind: "user"; text: string; attachment?: { name: string; url: string } }
  | { kind: "agent"; text: string }
  | { kind: "work"; work: WorkSnapshot; plan: GenerationSegmentFact[] | null }
  | {
      kind: "design-close";
      item: string;
      draftId: string;
      media: string;
      version: number;
      spec: SpecTokens;
      /** 定稿触发的后续（收尾卡「去向」叙事）。 */
      triggers: string[];
    }
  | {
      kind: "build-close";
      version: number;
      alignName: string;
      lint: { total: number; fixed: number; residual: string };
      stats: { time: string; files: number; add: number };
    }
  | { kind: "quote"; price: string; lines: string[] }
  | { kind: "notice"; text: string; tone?: "ok" | "amber" };

export type DesignState = {
  scenario: ScenarioId;
  chat: ChatMsg[];
  /** 生长中的直播卡（设计执行体 / 构建 run 同一骨架）。 */
  work: WorkSnapshot | null;
  workPlan: GenerationSegmentFact[] | null;
  items: DesignItem[];
  drafts: Draft[];
  picked: string | null;
  spec: SpecTokens | null;
  versions: { n: number; label: string; draftId?: string; media?: string }[];
  order: "none" | "placed" | "quoted" | "paid";
  buildStage: number;
  builtDraft?: string;
  annotations: Annotation[];
  annotateMode: null | "select" | "circle";
  activeTab: string;
  designTabOn: boolean;
  orderTabOn: boolean;
  outputsOpen: boolean;
  runActive: boolean;
};

export type Ev = Record<string, unknown> & { t: string };
export type Step = [delayMs: number, ev: Ev];

let seq = 0;
const nid = (p: string) => `${p}-${Date.now().toString(36)}-${seq++}`;

export function initialState(scenario: ScenarioId): DesignState {
  const base: DesignState = {
    scenario,
    chat: [],
    work: null,
    workPlan: null,
    items: [],
    drafts: [],
    picked: null,
    spec: null,
    versions: [],
    order: "none",
    buildStage: 0,
    annotations: [],
    annotateMode: null,
    activeTab: "system",
    designTabOn: false,
    orderTabOn: false,
    outputsOpen: false,
    runActive: false,
  };
  if (scenario === "towel") {
    base.items = [
      { id: "main", title: "毛巾主图案", brief: "35×75cm 浴室毛巾 · 温柔耐看、工厂可打样", status: "pending" },
      { id: "tag", title: "包装吊牌", brief: "配套吊牌 · 与主图案呼应", status: "pending" },
    ];
    base.chat = [
      {
        kind: "user",
        text: "我们棉品工作室要出一批毛巾，图案帮我设计一下——想要温柔一点、耐看的，参考图传给你了。",
        attachment: { name: "喜欢的配色与感觉.jpg", url: "" },
      },
      {
        kind: "agent",
        text: "需求都问清楚了，文档里列了两件设计物：毛巾主图案、包装吊牌。参考图我存在物料里了，出稿时会参考它的感觉。按顺序先做主图案。",
      },
    ];
  } else {
    base.items = [
      { id: "home", title: "首屏页", brief: "官网门面 · 一屏说清「巷角咖啡」", status: "pending" },
      { id: "menu", title: "菜单页", brief: "饮品与价格 · 分类清晰可维护", status: "pending" },
    ];
    base.chat = [
      { kind: "user", text: "给巷角咖啡做个官网，先把首屏做漂亮，后面页面跟着来。" },
      {
        kind: "agent",
        text: "文档整理好了（功能清单四页）。这个项目做「系统＋设计」：先走设计——范围就是这几页，从首屏页开始；设计定了再按定稿那张搭系统。",
      },
    ];
  }
  return base;
}

/* ============================ 归约 ============================ */

export function reduce(state: DesignState, ev: Ev): DesignState {
  const s: DesignState = structuredClone(state);
  const work = () => s.work!;
  switch (ev.t) {
    case "user": {
      s.chat.push({
        kind: "user",
        text: ev.text as string,
        attachment: ev.attachment as { name: string; url: string } | undefined,
      });
      break;
    }
    case "agent": {
      s.chat.push({ kind: "agent", text: ev.text as string });
      break;
    }
    case "notice": {
      s.chat.push({ kind: "notice", text: ev.text as string, tone: (ev.tone as "ok" | "amber") ?? "ok" });
      break;
    }
    case "work-start": {
      s.runActive = true;
      s.work = {
        runId: nid("run"),
        frozen: false,
        parts: [],
        slice: ev.slice as WorkSnapshot["slice"],
        startedAt: Date.now(),
      };
      s.workPlan = (ev.plan as GenerationSegmentFact[] | null) ?? null;
      /* 焦点跟随（确定性）：构建 run 起跑 → 成果区滑出＋切系统 tab。 */
      if (ev.focus) {
        s.outputsOpen = true;
        s.activeTab = ev.focus as string;
      }
      break;
    }
    case "note": {
      work().parts.push({ kind: "text", id: nid("t"), text: ev.text as string });
      break;
    }
    case "act": {
      work().parts.push({
        kind: "action",
        id: ev.id as string,
        toolCallId: ev.id as string,
        toolName: (ev.tool as string) ?? "image_generate",
        state: "running",
        label: ev.label as string,
      });
      break;
    }
    case "act-done": {
      const a = work().parts.find(
        (p): p is Extract<WorkPart, { kind: "action" }> => p.kind === "action" && p.id === ev.id,
      );
      if (a) a.state = "completed";
      break;
    }
    case "check-start": {
      work().parts.push({ kind: "check", id: "check", state: "checking" });
      break;
    }
    case "check-pass": {
      const c = work().parts.find(
        (p): p is Extract<WorkPart, { kind: "check" }> => p.kind === "check",
      );
      if (c) c.state = "passed";
      break;
    }
    case "plan-row": {
      const row = s.workPlan?.find((r) => r.ord === ev.ord);
      if (row) row.status = ev.status as GenerationSegmentFact["status"];
      break;
    }
    case "item-status": {
      const it = s.items.find((i) => i.id === ev.id);
      if (it) it.status = ev.status as DesignItem["status"];
      break;
    }
    case "round-freeze": {
      if (s.work) {
        s.work.frozen = true;
        s.work.endedAt = Date.now();
        s.chat.push({ kind: "work", work: s.work, plan: s.workPlan });
        s.work = null;
        s.workPlan = null;
      }
      s.runActive = false;
      break;
    }
    case "drafts-open": {
      const gen = ev.gen as number;
      for (const media of ev.ids as string[]) {
        s.drafts.push({ id: draftIdFor(media, gen), media, item: ev.item as string, gen, state: "placeholder", stage: 0 });
      }
      /* 焦点跟随（确定性）：首稿开画 → 挂设计稿 tab＋滑出成果区＋激活。 */
      if (ev.focus) {
        s.designTabOn = true;
        s.outputsOpen = true;
        s.activeTab = "design";
      }
      break;
    }
    case "draft-arrive": {
      const d = s.drafts.find((x) => x.id === ev.id);
      if (d) d.state = "arrived";
      break;
    }
    case "live-stage": {
      const d = s.drafts.find((x) => x.id === ev.id);
      if (d && d.stage < (ev.stage as number)) d.stage = ev.stage as number;
      break;
    }
    case "pick": {
      s.picked = s.picked === ev.id ? null : (ev.id as string);
      break;
    }
    case "draft-delete": {
      /* 画布整理：删掉不要的稿（定稿那张不显示删除口——收口不误删）。 */
      s.drafts = s.drafts.filter((d) => d.id !== ev.id);
      if (s.picked === ev.id) s.picked = null;
      break;
    }
    case "finalize": {
      const id = ev.id as string;
      const draft = s.drafts.find((d) => d.id === id);
      if (!draft) break;
      const item = s.items.find((i) => i.id === draft.item)!;
      item.status = "done";
      item.finalized = id;
      s.picked = null;
      const n = s.versions.length + 1;
      s.versions.push({ n, label: `设计定稿 · ${item.title}`, draftId: id, media: draft.media });
      s.spec = specOf(state.scenario, draft.media, (s.spec?.version ?? 0) + 1);
      if (state.scenario === "coffee") s.builtDraft = draft.media;
      const nextItem = s.items.find((i) => i.status !== "done");
      const triggers = !nextItem
        ? state.scenario === "coffee"
          ? ["收进交付物包（系统＋设计）", "设计规范已按这张刷新", "全部页面设计完成，自动开始按稿搭建系统"]
          : ["收进设计资产包", "设计规范已按这张刷新", "全部设计物完成，可以下单了"]
        : ["收进交付物包", "设计规范已按这张刷新", `接着做下一件：${nextItem.title}`];
      s.chat.push({ kind: "design-close", item: item.title, draftId: id, media: draft.media, version: n, spec: s.spec, triggers });
      break;
    }
    case "build-stage": {
      s.buildStage = ev.stage as number;
      break;
    }
    case "build-close": {
      if (s.work) {
        s.work.frozen = true;
        s.work.endedAt = Date.now();
        s.chat.push({ kind: "work", work: s.work, plan: s.workPlan });
        s.work = null;
        s.workPlan = null;
      }
      s.runActive = false;
      const n = s.versions.length + 1;
      s.versions.push({ n, label: "系统按稿对齐 · 首个构建", draftId: s.builtDraft });
      const align = s.builtDraft ? COFFEE_DESIGNS[s.builtDraft]?.name ?? "定稿" : "定稿";
      s.chat.push({
        kind: "build-close",
        version: n,
        alignName: align,
        lint: ev.lint as { total: number; fixed: number; residual: string },
        stats: ev.stats as { time: string; files: number; add: number },
      });
      break;
    }
    case "order-place": {
      s.order = "placed";
      s.orderTabOn = true;
      break;
    }
    case "quote": {
      s.order = "quoted";
      s.chat.push({ kind: "quote", price: ev.price as string, lines: ev.lines as string[] });
      break;
    }
    case "pay": {
      s.order = "paid";
      s.chat.push({
        kind: "notice",
        text: "支付完成——设计资产包已打包好，在「订单」页就能下载带走。项目归档后也随时能回来取。",
        tone: "ok",
      });
      break;
    }
    case "anno-add": {
      s.annotations.push({
        id: nid("anno"),
        x: ev.x as number,
        y: ev.y as number,
        w: ev.w as number,
        h: ev.h as number,
        sent: false,
      });
      break;
    }
    case "anno-text": {
      const a = s.annotations.find((x) => x.id === ev.id);
      if (a) a.text = ev.text as string;
      break;
    }
    case "anno-send": {
      const a = s.annotations.find((x) => x.id === ev.id);
      if (!a) break;
      a.sent = true;
      s.annotateMode = null;
      s.chat.push({ kind: "user", text: `（圈注）${a.text ?? "这里跟定稿不一样"}` });
      s.chat.push({
        kind: "agent",
        text: "看到了，圈出来的地方和定稿稿对了一遍——确实偏了，下一轮更新就按定稿那张改回来。",
      });
      break;
    }
    case "annotate-mode": {
      s.annotateMode = ev.mode as DesignState["annotateMode"];
      break;
    }
    case "tab": {
      s.activeTab = ev.id as string;
      break;
    }
    case "outputs-open": {
      s.outputsOpen = true;
      break;
    }
    case "design-tab-on": {
      s.designTabOn = true;
      break;
    }
    case "order-tab-on": {
      s.orderTabOn = true;
      break;
    }
  }
  return s;
}

/** 定稿 → 设计规范 token（跟定稿那张走；平面查表、界面类取候选 design 配色）。 */
function specOf(scenario: ScenarioId, draftId: string, version: number): SpecTokens {
  if (scenario === "towel") {
    return { version, pairs: TOWEL_SPEC[draftId] ?? TOWEL_SPEC["tw-2-1"] };
  }
  const d = COFFEE_DESIGNS[draftId] ?? COFFEE_DESIGNS["cf-1-2"];
  return { version, pairs: [["主色", d.accent], ["浅底", d.soft], ["圆角", "12px"]] };
}

/* ============================ 场景脚本 ============================ */

export type Gate = {
  /** 探索轮 gate：改稿/定稿都动态派发（点哪改哪——改稿作用域跟选中稿走、
   * 定稿后按完成度决定去向），不再指向静态段。 */
  kind?: "explore";
  /** 用户发改稿意见 → 跳续到的段 id（探索轮才有，可循环回自身）。 */
  revision?: string;
  /** 用户定稿 → 跳续到的段 id。 */
  finalize?: string;
  /** 用户点下单 → 跳续到的段 id。 */
  order?: string;
  /** 用户点支付 → 跳续到的段 id。 */
  pay?: string;
  /** 走查提示（「现在轮到你」）。 */
  hint: string;
};

export type Segment = { id: string; steps: Step[]; gate?: Gate };
export type Scenario = { id: ScenarioId; name: string; segments: Segment[] };

/** 界面类候选「随编码逐步显现」：到稿（骨架出现即占位翻活）→ 四阶段推进。 */
function liveRamp(id: string, stageGap = 950): Step[] {
  return [
    [1300, { t: "draft-arrive", id }],
    [80, { t: "live-stage", id, stage: 1 }],
    [stageGap, { t: "live-stage", id, stage: 2 }],
    [stageGap, { t: "live-stage", id, stage: 3 }],
    [stageGap, { t: "live-stage", id, stage: 4 }],
  ] as Step[];
}

/* ---------- 场景一：毛巾设计图（平面类 · 设计即交付） ---------- */

const TOWEL: Scenario = {
  id: "towel",
  name: "① 毛巾设计图（设计即交付）",
  segments: [
    {
      id: "start",
      steps: [
        [600, { t: "work-start", slice: { title: "毛巾主图案", index: 1, total: 2 }, plan: [
          { ord: 1, description: "毛巾主图案", status: "pending" },
          { ord: 2, description: "包装吊牌", status: "pending" },
        ] }],
        [500, { t: "note", text: "参考图看过了——温柔耐看的方向。第一轮先出三张不同性格的稿，一张张到、不用等齐。" }],
        [500, { t: "drafts-open", item: "main", gen: 1, ids: ["tw-1-1", "tw-1-2", "tw-1-3"], focus: "design" }],
        [400, { t: "act", id: "a1", label: "出第 1 稿（蓝白条纹 · 经典耐看）" }],
        [2400, { t: "act-done", id: "a1" }],
        [80, { t: "draft-arrive", id: "tw-1-1" }],
        [300, { t: "act", id: "a2", label: "出第 2 稿（暖橙波点 · 亲和日常）" }],
        [3100, { t: "act-done", id: "a2" }],
        [80, { t: "draft-arrive", id: "tw-1-2" }],
        [300, { t: "act", id: "a3", label: "出第 3 稿（灰绿几何 · 现代简洁）" }],
        [4200, { t: "act-done", id: "a3" }],
        [80, { t: "draft-arrive", id: "tw-1-3" }],
        [500, { t: "note", text: "三张都到了：条纹最经典、波点最亲和、几何最现代。在右边「设计稿」页点着对比看看——选一张定下来，或直接告诉我想怎么改。" }],
        [400, { t: "round-freeze" }],
      ],
      gate: {
        kind: "explore",
        hint: "轮到你了：点画布上任意一张（点哪改哪）——定稿它，或选中后说说怎么改",
      },
    },
    {
      id: "item2",
      steps: [
        [700, { t: "agent", text: "主图案定了，规范也按这张刷新了（「设计稿」页顶部能看到当前色板）。接着做包装吊牌——直接沿用主图案的感觉。" }],
        [600, { t: "work-start", slice: { title: "包装吊牌", index: 2, total: 2 }, plan: [
          { ord: 1, description: "毛巾主图案", status: "closed" },
          { ord: 2, description: "包装吊牌", status: "pending" },
        ] }],
        [400, { t: "note", text: "吊牌出两张配套方案。" }],
        [400, { t: "drafts-open", item: "tag", gen: 1, ids: ["tag-1-1", "tag-1-2"], focus: "design" }],
        [400, { t: "act", id: "c1", label: "出吊牌第 1 稿（波点呼应）" }],
        [2000, { t: "act-done", id: "c1" }],
        [80, { t: "draft-arrive", id: "tag-1-1" }],
        [300, { t: "act", id: "c2", label: "出吊牌第 2 稿（色块呼应）" }],
        [2400, { t: "act-done", id: "c2" }],
        [80, { t: "draft-arrive", id: "tag-1-2" }],
        [500, { t: "note", text: "吊牌两张也到了，选一张收尾。" }],
        [400, { t: "round-freeze" }],
      ],
      gate: {
        kind: "explore",
        hint: "轮到你了：点哪改哪——想改毛巾主图案就点它的稿，想收吊牌就定稿吊牌",
      },
    },
    {
      id: "done",
      steps: [
        [600, { t: "agent", text: "两件设计物都定稿了。设计资产已经齐了——下单后打包好就能下载带走（选定稿、规范文件、衍生尺寸都在包里）。" }],
      ],
      gate: {
        order: "order",
        hint: "轮到你了：点顶部的「确认下单」走完整商业流（也可以先随便逛逛）",
      },
    },
    {
      id: "order",
      steps: [
        [1200, { t: "agent", text: "订单收到，后台正在核价——报价出来会直接发在这里。" }],
        [2600, { t: "quote", price: "¥ 399", lines: ["毛巾主图案＋包装吊牌 · 设计资产包制作与商用授权", "含选定稿源文件、规范文件、衍生尺寸"] }],
      ],
      gate: {
        pay: "paid",
        hint: "轮到你了：报价已出，在订单页或报价卡上完成支付",
      },
    },
    {
      id: "paid",
      steps: [[900, { t: "pay" }]],
    },
  ],
};

/* ---------- 场景二：咖啡店首屏 → 系统（界面类 · 设计服务系统） ---------- */

const COFFEE: Scenario = {
  id: "coffee",
  name: "② 首屏设计稿 → 系统（设计服务系统）",
  segments: [
    {
      id: "start",
      steps: [
        [600, { t: "work-start", slice: { title: "首屏页", index: 1, total: 2 }, plan: [
          { ord: 1, description: "首屏页", status: "pending" },
          { ord: 2, description: "菜单页", status: "pending" },
        ] }],
        [500, { t: "note", text: "首屏是界面类设计物：稿不是图片，是真能点的轻页面——在「设计稿」页会看着它一点点写出来。" }],
        [500, { t: "drafts-open", item: "home", gen: 1, ids: ["cf-1-1", "cf-1-2", "cf-1-3"], focus: "design" }],
        [300, { t: "act", id: "d1", label: "写第 1 稿（居中沉稳）", tool: "write_file" }],
        ...liveRamp("cf-1-1"),
        [200, { t: "act-done", id: "d1" }],
        [300, { t: "act", id: "d2", label: "写第 2 稿（左文右图）", tool: "write_file" }],
        ...liveRamp("cf-1-2"),
        [200, { t: "act-done", id: "d2" }],
        [300, { t: "act", id: "d3", label: "写第 3 稿（大字报）", tool: "write_file" }],
        ...liveRamp("cf-1-3"),
        [200, { t: "act-done", id: "d3" }],
        [400, { t: "note", text: "三张都能点了：居中沉稳、左文右图、大字报。在「设计稿」页点开直接试——选一张，或告诉我要改哪里。" }],
        [400, { t: "round-freeze" }],
      ],
      gate: {
        kind: "explore",
        hint: "轮到你了：点画布上任意一张（点哪改哪）——定稿它，或选中后说说怎么改",
      },
    },
    {
      id: "menu",
      steps: [
        [700, { t: "agent", text: "首屏定了。接着做菜单页——风格直接沿用首屏定稿的规范，出两种布局方向，在画布上跟首屏并排看。" }],
        [600, { t: "work-start", slice: { title: "菜单页", index: 2, total: 2 }, plan: [
          { ord: 1, description: "首屏页", status: "closed" },
          { ord: 2, description: "菜单页", status: "pending" },
        ] }],
        [400, { t: "note", text: "菜单页两张：卡片网格、简洁列表——同一套规范、两种信息密度。" }],
        [400, { t: "drafts-open", item: "menu", gen: 1, ids: ["cf-m1", "cf-m2"], focus: "design" }],
        [300, { t: "act", id: "g1", label: "写菜单稿 1（卡片网格）", tool: "write_file" }],
        ...liveRamp("cf-m1"),
        [200, { t: "act-done", id: "g1" }],
        [300, { t: "act", id: "g2", label: "写菜单稿 2（简洁列表）", tool: "write_file" }],
        ...liveRamp("cf-m2"),
        [200, { t: "act-done", id: "g2" }],
        [400, { t: "note", text: "菜单两张也到了——跟首屏的稿并排摆着，选一张。" }],
        [400, { t: "round-freeze" }],
      ],
      gate: {
        kind: "explore",
        hint: "轮到你了：点哪改哪——首屏的稿也能回头改；两页都定了就自动开搭",
      },
    },
    {
      id: "build",
      steps: [
        [700, { t: "agent", text: "定稿收进规范了。这就开始搭系统——按定稿那张对齐，规范色板会先写进系统底座，边搭边做合规扫描。" }],
        [600, { t: "work-start", focus: "system", slice: { title: "系统初始化", index: 3, total: 3 }, plan: [
          { ord: 1, description: "首屏页", status: "closed" },
          { ord: 2, description: "菜单页", status: "closed" },
          { ord: 3, description: "其余页面", status: "pending" },
        ] }],
        [400, { t: "note", text: "先把设计规范写进系统底座（色、圆角），再按定稿搭首屏。" }],
        [400, { t: "act", id: "f1", label: "把设计规范写入系统主题", tool: "write_file" }],
        [1800, { t: "act-done", id: "f1" }],
        [200, { t: "build-stage", stage: 1 }],
        [300, { t: "act", id: "f2", label: "按定稿搭首屏结构", tool: "write_file" }],
        [2600, { t: "act-done", id: "f2" }],
        [200, { t: "build-stage", stage: 2 }],
        [300, { t: "act", id: "f3", label: "套用规范配色与文案", tool: "edit_file" }],
        [2400, { t: "act-done", id: "f3" }],
        [200, { t: "build-stage", stage: 3 }],
        [400, { t: "note", text: "首屏成形了，跑一遍规范合规扫描。" }],
        [300, { t: "check-start" }],
        [2000, { t: "note", text: "扫描发现 3 处没用规范色的地方，自动改了一轮再扫。" }],
        [1600, { t: "act", id: "f4", label: "修正违规样式（重试）", tool: "edit_file" }],
        [1400, { t: "act-done", id: "f4" }],
        [200, { t: "build-stage", stage: 4 }],
        [300, { t: "check-pass" }],
        [300, { t: "note", text: "首屏按稿收口。其余页面沿用同一规范快速跟上。" }],
        [300, { t: "act", id: "f5", label: "其余页面套用同一规范", tool: "write_file" }],
        [2600, { t: "act-done", id: "f5" }],
        [400, { t: "plan-row", ord: 3, status: "closed" }],
        [300, { t: "build-close", lint: { total: 3, fixed: 2, residual: "联系页底部按钮仍用裸色 #e11d48——如实标注，未静默放行" }, stats: { time: "2 分 18 秒", files: 14, add: 486 } }],
        [900, { t: "agent", text: "系统按定稿搭好了，去「系统」页点点看。觉得哪里不像定稿那张，用底部的圈选圈出来告诉我，下一轮就改。想把设计稿的图和 HTML 带走，点开稿卡就能下载（支付后开通）。" }],
      ],
      gate: {
        order: "order2",
        hint: "轮到你了：去「系统」页圈注对稿，或点开稿卡试下载；想带走就顶部「确认下单」",
      },
    },
    {
      id: "order2",
      steps: [
        [1200, { t: "agent", text: "订单收到，后台正在核价——报价出来直接发在这里。" }],
        [2600, { t: "quote", price: "¥ 1,999", lines: ["巷角咖啡官网 · 系统制作（按两页定稿）＋设计资产包", "含首年托管与发布、选定稿源文件与规范文件"] }],
      ],
      gate: {
        pay: "paid2",
        hint: "轮到你了：支付后稿卡的「下载图 / 下载 HTML」就开通了",
      },
    },
    {
      id: "paid2",
      steps: [[900, { t: "pay" }]],
    },
  ],
};

export const SCENARIOS: Scenario[] = [TOWEL, COFFEE];

export function segmentOf(scenario: Scenario, id: string): Segment {
  return scenario.segments.find((seg) => seg.id === id) ?? scenario.segments[0];
}

/**
 * 动态改稿步组（点哪改哪）：作用域＝选中稿（无选中＝最近未收口件的最新稿）。
 * 对该稿的设计物出新一代（代次自增、媒体复用 REV_MEDIA、旧稿全保留），
 * 平面类整图到达、界面类 liveRamp 渐进，收口回探索 gate。
 */
export function buildRevisionSteps(state: DesignState, scopeId: string | null, rangeLabel: string): Step[] {
  const scope =
    state.drafts.find((d) => d.id === scopeId) ??
    [...state.drafts].reverse().find((d) => {
      const it = state.items.find((i) => i.id === d.item);
      return it && it.status !== "done";
    }) ??
    state.drafts[state.drafts.length - 1];
  if (!scope) return [];
  const item = state.items.find((i) => i.id === scope.item)!;
  const gen = Math.max(...state.drafts.filter((d) => d.item === item.id).map((d) => d.gen), 0) + 1;
  const medias = REV_MEDIA[item.id] ?? [];
  const idx = state.items.findIndex((i) => i.id === item.id) + 1;
  const plan = state.items.map((it, i) => ({
    ord: i + 1,
    description: it.title,
    status: it.status === "done" ? ("closed" as const) : ("pending" as const),
  }));
  const live = !TOWEL_DRAFTS[scope.media];
  const steps: Step[] = [
    [600, { t: "work-start", slice: { title: item.title, index: idx, total: state.items.length }, plan }],
    [400, { t: "note", text: `就「${draftName(scope.media)}」按「${rangeLabel}」幅度改——新的一代直接落在画布上，旧稿全保留。` }],
    [400, { t: "drafts-open", item: item.id, gen, ids: medias, focus: "design" }],
  ];
  medias.forEach((m, i) => {
    const id = draftIdFor(m, gen);
    if (live) {
      steps.push([300, { t: "act", id: `r${gen}-${i}`, label: `改稿写出 ${draftName(m)}`, tool: "write_file" }]);
      steps.push(...liveRamp(id));
      steps.push([200, { t: "act-done", id: `r${gen}-${i}` }]);
    } else {
      steps.push([300, { t: "act", id: `r${gen}-${i}`, label: `改稿出 ${draftName(m)}` }]);
      steps.push([2200 + i * 400, { t: "act-done", id: `r${gen}-${i}` }]);
      steps.push([80, { t: "draft-arrive", id }]);
    }
  });
  steps.push([500, { t: "note", text: "新的一代到了——哪张定哪张，还想改哪张就点哪张。" }]);
  steps.push([400, { t: "round-freeze" }]);
  return steps;
}

/** 探索轮通用 gate（改稿/定稿动态派发）。 */
export function exploreGate(hint: string): Gate {
  return { kind: "explore", hint };
}
