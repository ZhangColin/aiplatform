"use client";

/**
 * ============================================================================
 * 原 型 —— 过程可见性（一次性，走查完即归档，勿当生产代码）
 * ============================================================================
 * 验证问题（issue #68）：非技术用户「看着系统一点点做出来」的感觉是否成立。
 * 口径来源：#64（动作状态卡 + 步骤分组 + 一条生长中的工作消息 + 收尾卡）、
 * #65（收尾卡合并叙事 / 判定行 / 文件清单 / 版本控件）、#67（受理空窗反馈）。
 * 呈现参考：docs/research/ 六家对标（v0 Work Details / bolt 每消息检查点 /
 * Lovable 实时已耗时与「看当时快照」）。
 *
 * 用法：pnpm dev → http://localhost:3333/proto/process-visibility
 * 选场景 → ▶ 播放。场景④的问答卡要点选才继续；收尾卡的版本控件随时可点。
 * ============================================================================
 */

import * as React from "react";
import {
  ArrowUp,
  Check,
  ChevronDown,
  Clock3,
  Eye,
  FileCode2,
  FileText,
  Files,
  Flower2,
  Info,
  Link2,
  Lock,
  Monitor,
  Package,
  Palette,
  Paperclip,
  Play,
  ReceiptText,
  RefreshCw,
  RotateCcw,
  Search,
  Sparkles,
  TriangleAlert,
  X,
} from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Bubble, BubbleContent } from "@/components/ui/bubble";
import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import {
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from "@/components/ui/empty";
import {
  Message,
  MessageAvatar,
  MessageContent,
  MessageGroup,
} from "@/components/ui/message";
import {
  MessageScroller,
  MessageScrollerContent,
  MessageScrollerProvider,
  MessageScrollerViewport,
} from "@/components/ui/message-scroller";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { cn } from "@/lib/utils";

/* ============================================================================
   纯逻辑模块（事件 → 对话面状态；#66「parts 事件模型」前端投影雏形）
   不碰 DOM；页面壳只做渲染与定时。
   ============================================================================ */

type Part =
  | { type: "narration"; text: string }
  | { type: "step"; title: string }
  | { type: "action"; id: string; icon: ActionIcon; label: string; status: "running" | "done" | "failed"; duration?: string }
  | { type: "question"; question: string; options: string[]; picked: number | null }
  | { type: "wrap"; version: number; summary: string; lines: WrapLine[]; files: FileRow[]; stats: Stats }
  | { type: "wrap-failed"; reason: string };

type ActionIcon = "file" | "doc" | "package" | "palette" | "link" | "search";
type WrapLine = { side: "doc" | "system"; text: string };
type FileRow = [name: string, add: number, del: number];
type Stats = { time: string; files: number; add: number; del: number };

type Msg =
  | { kind: "user"; text: string }
  | { kind: "agent"; text: string }
  | { kind: "work"; parts: Part[]; status: "growing" | "done" | "failed" };

type State = {
  messages: Msg[];
  previewStage: number; // 0 空 / 1 骨架 / 2 黑白 / 3 彩色绿 / 4 粉色 / 5 粉色+会员
  doc: { pink: boolean; citywide: boolean; member: boolean };
  versions: { n: number; stage: number }[];
  viewing: number | null;
  tab: "system" | "doc" | "project";
  accepting: boolean; // 受理空窗：用户发出 → 首个内容之间
  runActive: boolean;
  waitingAnswer?: boolean;
};

type Ev = Record<string, unknown> & { t: string };
type Step = [delayMs: number, ev: Ev];

function initialState(): State {
  return {
    messages: [],
    previewStage: 0,
    doc: { pink: false, citywide: false, member: false },
    versions: [],
    viewing: null,
    tab: "system",
    accepting: false,
    runActive: false,
  };
}

function reduce(state: State, ev: Ev): State {
  const s: State = structuredClone(state);
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
      s.doc[ev.key as keyof State["doc"]] = true;
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
      s.tab = "system";
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
      s.tab = ev.tab as State["tab"];
      break;
  }
  return s;
}

/* ---------- 场景脚本（数据） ---------- */

type Scenario = { id: string; name: string; watch: string; steps: Step[]; retry?: Step[] };

const SCENARIOS: Scenario[] = [
  {
    id: "first",
    name: "① 第一次做系统",
    watch: "观察：发出后到第一个字出现之间的空窗，朋友慌不慌？动作卡一条条打勾 + 右边预览一点点出来，「在做」的感觉够不够？收尾卡看不看得懂「做好了什么」？",
    steps: [
      [0, { t: "user-says", text: "好，就按这个文档开始做吧" }],
      [1800, { t: "run-started" }],
      [200, { t: "narration", text: "好的，开始为您搭建花店小程序，做好了会在右边看到。" }],
      [600, { t: "step", title: "搭建项目骨架" }],
      [300, { t: "action-start", id: "a1", icon: "file", label: "创建项目结构" }],
      [1900, { t: "action-done", id: "a1", duration: "1.9 秒" }],
      [200, { t: "action-start", id: "a2", icon: "package", label: "安装基础组件" }],
      /* 这里藏了一次静默重试：转得久，但不出任何错误提示 */
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
    watch: "观察：小改动的呈现会不会太重或太轻？收尾卡里「文档 + 系统」两行，朋友能不能看懂「两边都改了」？预览变粉能不能对上「真的改了」？",
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
    watch: "观察：「系统无需改动」这行判定，朋友看了是安心（没乱动我的系统）还是困惑（为什么不做？原因说得清吗）？",
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
    watch: "观察：干到一半被问答卡打断，朋友是否明白「需要我选一个才会继续」？选项读得懂吗？选完后续动作与收尾卡能不能对上自己选的答案？",
    steps: [
      [0, { t: "user-says", text: "加一个会员充值功能" }],
      [1600, { t: "run-started" }],
      [200, { t: "narration", text: "好的。会员充值有个地方需要您定一下，我才好往下做。" }],
      [600, {
        t: "question",
        question: "会员余额想怎么用？",
        options: ["充多少送多少，比如充 100 送 20", "只存余额，不搞赠送", "先不做赠送，以后再说"],
      }],
      /* ↓ 以下步骤在朋友点选后才继续 */
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
    watch: "观察：中间那步转很久但没有任何报错（静默重试），朋友是耐心等待还是开始怀疑坏了？失败卡出来后，「为什么没做成 + 重新尝试」是否看得懂、敢不敢点？",
    steps: [
      [0, { t: "user-says", text: "接入微信支付" }],
      [1600, { t: "run-started" }],
      [200, { t: "narration", text: "好的，我来接微信支付。" }],
      [400, { t: "action-start", id: "e1", icon: "file", label: "编写支付下单" }],
      [2000, { t: "action-done", id: "e1", duration: "2.0 秒" }],
      [200, { t: "action-start", id: "e2", icon: "link", label: "编写支付回调" }],
      /* 静默重试：转很久，不出错提示；然后终态失败 */
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

/* 场景预置：把排在前面的场景瞬间放完，得到连贯的对话史与版本史 */
function playStepsInstant(s: State, steps: Step[]): State {
  for (const [, ev] of steps) {
    s = reduce(s, ev);
    if (s.waitingAnswer) s = reduce(s, { t: "answered", choice: 0 });
  }
  return s;
}

function preludeState(uptoIdx: number): State {
  let s = initialState();
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

/* ============================================================================
   一次性页面壳（渲染 + 定时播放；勿移植）
   ============================================================================ */

const ACTION_ICONS: Record<ActionIcon, React.ReactNode> = {
  file: <FileCode2 className="size-3.5" />,
  doc: <FileText className="size-3.5" />,
  package: <Package className="size-3.5" />,
  palette: <Palette className="size-3.5" />,
  link: <Link2 className="size-3.5" />,
  search: <Search className="size-3.5" />,
};

export default function ProcessVisibilityPrototype() {
  const [scenarioIdx, setScenarioIdx] = React.useState(0);
  const [state, setState] = React.useState<State>(() => preludeState(0));
  const [playing, setPlaying] = React.useState(false);
  const [speed, setSpeed] = React.useState(1);
  const stateRef = React.useRef(state);
  const timer = React.useRef<ReturnType<typeof setTimeout> | null>(null);
  const speedRef = React.useRef(speed);
  speedRef.current = speed;
  const pendingRef = React.useRef<{ steps: Step[]; next: number } | null>(null);

  /* 唯一写入口：事件归约 → ref 与渲染态同步（定时器闭包只信 ref，避开 StrictMode 双调） */
  function commit(ev: Ev) {
    const next = reduce(stateRef.current, ev);
    stateRef.current = next;
    setState(next);
  }

  function scheduleFrom(steps: Step[], i: number) {
    if (i >= steps.length) {
      setPlaying(false);
      return;
    }
    const [delay, ev] = steps[i];
    timer.current = setTimeout(() => {
      commit(ev);
      if (stateRef.current.waitingAnswer) {
        /* 问答卡：暂停播放，等点选后续播（见 onAnswer） */
        pendingRef.current = { steps, next: i + 1 };
        return;
      }
      scheduleFrom(steps, i + 1);
    }, delay / speedRef.current);
  }

  function stop() {
    setPlaying(false);
    pendingRef.current = null;
    if (timer.current) clearTimeout(timer.current);
    timer.current = null;
  }

  function resetTo(s: State) {
    stateRef.current = s;
    setState(s);
  }

  const play = (idx: number) => {
    stop();
    setScenarioIdx(idx);
    resetTo(preludeState(idx));
    setPlaying(true);
    scheduleFrom(SCENARIOS[idx].steps, 0);
  };

  const selectScenario = (idx: number) => {
    stop();
    setScenarioIdx(idx);
    resetTo(preludeState(idx));
  };

  const onAnswer = (choice: number) => {
    commit({ t: "answered", choice });
    const pend = pendingRef.current;
    pendingRef.current = null;
    if (pend) scheduleFrom(pend.steps, pend.next);
  };

  const onRetry = () => {
    const sc = SCENARIOS[scenarioIdx];
    if (!sc.retry || stateRef.current.runActive) return;
    setPlaying(true);
    scheduleFrom(sc.retry, 0);
  };

  return (
    <div className="flex h-svh flex-col">
      <ProtoBar
        scenarioIdx={scenarioIdx}
        playing={playing}
        speed={speed}
        onSelect={selectScenario}
        onPlay={() => play(scenarioIdx)}
        onReset={() => selectScenario(scenarioIdx)}
        onSpeed={setSpeed}
      />
      {/* ---------- 产品壳 ---------- */}
      <header className="flex h-12 shrink-0 items-center gap-2 border-b px-3">
        <span className="flex size-6 shrink-0 items-center justify-center rounded-md bg-primary text-xs font-bold text-primary-foreground">
          AI
        </span>
        <span className="text-sm font-semibold">AI 开发平台</span>
        <span className="text-muted-foreground/40">/</span>
        <span className="text-xs text-muted-foreground">巷口花店小程序</span>
        <div className="ml-auto flex items-center gap-1">
          {state.runActive ? <LivePill /> : null}
        </div>
      </header>
      <div className="flex min-h-0 flex-1">
        <ChatArea state={state} onAnswer={onAnswer} onDispatch={commit} onRetry={onRetry} />
        <OutputsArea state={state} onDispatch={commit} />
      </div>
    </div>
  );
}

/* ---------- 顶栏 LIVE 计时（真实时间，纯摆件） ---------- */
function LivePill() {
  const [sec, setSec] = React.useState(0);
  React.useEffect(() => {
    const t = setInterval(() => setSec((s) => s + 1), 1000);
    return () => clearInterval(t);
  }, []);
  return (
    <span className="flex items-center gap-2 rounded-full border border-red-500/40 bg-red-500/10 px-2.5 py-1">
      <span className="relative flex size-2">
        <span className="absolute inline-flex size-full animate-ping rounded-full bg-red-500 opacity-60" />
        <span className="relative inline-flex size-2 rounded-full bg-red-500" />
      </span>
      <span className="text-xs font-semibold text-red-600">LIVE</span>
      <span className="font-mono text-xs tabular-nums text-red-600">
        {Math.floor(sec / 60)}:{String(sec % 60).padStart(2, "0")}
      </span>
    </span>
  );
}

/* ---------- 对话区 ---------- */
function ChatArea({
  state,
  onAnswer,
  onDispatch,
  onRetry,
}: {
  state: State;
  onAnswer: (choice: number) => void;
  onDispatch: (ev: Ev) => void;
  onRetry: () => void;
}) {
  return (
    <div className="flex w-[400px] shrink-0 flex-col border-r">
      <MessageScrollerProvider>
        <MessageScroller className="min-h-0 flex-1">
          <MessageScrollerViewport>
            <MessageScrollerContent className="gap-5 p-4 pb-2">
              <MessageGroup>
                {state.messages.map((m, i) => {
                  if (m.kind === "user") {
                    return (
                      <Message key={i} align="end">
                        <Bubble variant="tinted" align="end">
                          <BubbleContent className="whitespace-pre-wrap">{m.text}</BubbleContent>
                        </Bubble>
                      </Message>
                    );
                  }
                  if (m.kind === "agent") {
                    return (
                      <Message key={i}>
                        <MessageAvatar className="size-6 bg-muted">
                          <Sparkles className="size-3.5 text-muted-foreground" />
                        </MessageAvatar>
                        <MessageContent>
                          <Bubble variant="muted" align="start">
                            <BubbleContent className="whitespace-pre-wrap">{m.text}</BubbleContent>
                          </Bubble>
                        </MessageContent>
                      </Message>
                    );
                  }
                  return (
                    <WorkMessage key={i} msg={m} waitingAnswer={!!state.waitingAnswer} onAnswer={onAnswer} onDispatch={onDispatch} onRetry={onRetry} />
                  );
                })}
                {state.accepting ? (
                  <Message>
                    <MessageAvatar className="size-6 bg-muted">
                      <Sparkles className="size-3.5 text-muted-foreground" />
                    </MessageAvatar>
                    <MessageContent>
                      <div className="flex w-fit items-center gap-2 rounded-xl border px-3 py-2 text-xs text-muted-foreground">
                        <TypingDots /> 正在处理您的消息…
                      </div>
                    </MessageContent>
                  </Message>
                ) : null}
              </MessageGroup>
            </MessageScrollerContent>
          </MessageScrollerViewport>
        </MessageScroller>
      </MessageScrollerProvider>
      {/* 输入框（原型中为摆件） */}
      <div className="shrink-0 border-t p-3">
        <div className="flex items-center gap-2 rounded-xl border bg-muted/40 px-3 py-2.5 text-sm text-muted-foreground/70">
          <Paperclip className="size-4" />
          <span className="min-w-0 flex-1 truncate">说说想改什么…（原型里请用上方场景播放）</span>
          <Button size="icon" className="size-7 rounded-lg" disabled>
            <ArrowUp className="size-4" />
          </Button>
        </div>
      </div>
    </div>
  );
}

function TypingDots() {
  return (
    <span className="inline-flex gap-1">
      {[0, 1, 2].map((i) => (
        <span
          key={i}
          className="size-1.5 animate-pulse rounded-full bg-muted-foreground/60"
          style={{ animationDelay: `${i * 0.2}s` }}
        />
      ))}
    </span>
  );
}

/* ---------- 工作消息：一条生长中的容器 ---------- */
function WorkMessage({
  msg,
  waitingAnswer,
  onAnswer,
  onDispatch,
  onRetry,
}: {
  msg: Extract<Msg, { kind: "work" }>;
  waitingAnswer: boolean;
  onAnswer: (choice: number) => void;
  onDispatch: (ev: Ev) => void;
  onRetry: () => void;
}) {
  const growing = msg.status === "growing";
  return (
    <Message>
      <MessageAvatar className="size-6 bg-muted">
        <Sparkles className="size-3.5 text-muted-foreground" />
      </MessageAvatar>
      <MessageContent>
        <div
          className={cn(
            "rounded-xl border p-3",
            growing && "border-foreground/15 shadow-[0_0_0_3px_var(--color-muted)]",
          )}
        >
          {growing ? <WorkHeader /> : null}
          {msg.parts.map((p, i) => (
            <PartView key={i} part={p} onAnswer={onAnswer} onDispatch={onDispatch} onRetry={onRetry} />
          ))}
          {growing && !waitingAnswer ? (
            <div className="mt-2 flex items-center gap-2 text-xs text-muted-foreground">
              <TypingDots /> 正在干活…
            </div>
          ) : null}
        </div>
      </MessageContent>
    </Message>
  );
}

/** 工作消息头：脉冲点 + 实时已耗时（Lovable 式），定格即消失。 */
function WorkHeader() {
  const [sec, setSec] = React.useState(0);
  React.useEffect(() => {
    const t = setInterval(() => setSec((s) => s + 1), 1000);
    return () => clearInterval(t);
  }, []);
  return (
    <div className="mb-1 flex items-center gap-2 text-xs font-medium">
      <span className="relative flex size-2">
        <span className="absolute inline-flex size-full animate-ping rounded-full bg-foreground/50" />
        <span className="relative inline-flex size-2 rounded-full bg-foreground/70" />
      </span>
      正在做
      <span className="ml-auto font-mono tabular-nums text-muted-foreground">
        {Math.floor(sec / 60)}:{String(sec % 60).padStart(2, "0")}
      </span>
    </div>
  );
}

function PartView({
  part,
  onAnswer,
  onDispatch,
  onRetry,
}: {
  part: Part;
  onAnswer: (choice: number) => void;
  onDispatch: (ev: Ev) => void;
  onRetry: () => void;
}) {
  switch (part.type) {
    case "narration":
      return <p className="py-1 text-sm leading-relaxed">{part.text}</p>;
    case "step":
      return (
        <div className="mt-3 mb-1 flex items-center gap-2 text-xs font-semibold text-muted-foreground">
          {part.title}
          <Separator className="flex-1" />
        </div>
      );
    case "action":
      return <ActionRow part={part} />;
    case "question":
      return <QuestionView part={part} onAnswer={onAnswer} />;
    case "wrap":
      return <WrapCard part={part} onDispatch={onDispatch} />;
    case "wrap-failed":
      return <FailedCard part={part} onRetry={onRetry} />;
  }
}

function ActionRow({ part }: { part: Extract<Part, { type: "action" }> }) {
  return (
    <div className="flex items-center gap-2 rounded-md px-1 py-1.5 text-[13px]">
      <span className="text-muted-foreground">{ACTION_ICONS[part.icon]}</span>
      <span className={cn("min-w-0 flex-1", part.status === "done" && "text-muted-foreground")}>
        {part.label}
      </span>
      {part.status === "running" ? (
        <span className="flex items-center gap-1.5 text-xs text-muted-foreground">
          <Spinner className="size-3" /> 进行中
        </span>
      ) : part.status === "done" ? (
        <span className="flex items-center gap-1.5 text-xs text-muted-foreground">
          <Check className="size-3.5 text-green-600" strokeWidth={3} />
          {part.duration}
        </span>
      ) : (
        <span className="flex items-center gap-1.5 text-xs text-destructive">
          <X className="size-3.5" strokeWidth={3} /> 没做成
        </span>
      )}
    </div>
  );
}

function QuestionView({
  part,
  onAnswer,
}: {
  part: Extract<Part, { type: "question" }>;
  onAnswer: (choice: number) => void;
}) {
  const interactive = part.picked === null;
  return (
    <div
      className={cn(
        "mt-2 rounded-xl border p-3",
        interactive ? "border-primary/40 bg-primary/[0.04]" : "border-border bg-muted/30",
      )}
    >
      <div className="mb-1.5 flex items-center gap-2">
        <span
          className={cn(
            "rounded-full px-2 py-0.5 text-xs font-medium",
            interactive ? "bg-primary/15 text-primary" : "bg-muted text-muted-foreground",
          )}
        >
          需要您定一下
        </span>
        {!interactive ? <span className="ml-auto text-xs text-muted-foreground">已回答</span> : null}
      </div>
      <p className="text-sm font-medium">{part.question}</p>
      <div className="mt-2.5 flex flex-col gap-1.5">
        {part.options.map((o, i) =>
          interactive ? (
            <Button
              key={i}
              size="sm"
              variant="outline"
              className="h-auto justify-start rounded-lg px-3 py-2 text-left whitespace-normal"
              onClick={() => onAnswer(i)}
            >
              {o}
            </Button>
          ) : (
            <span
              key={i}
              className={cn(
                "rounded-lg border px-3 py-2 text-sm",
                part.picked === i
                  ? "border-primary/50 bg-primary/10 font-medium"
                  : "opacity-50",
              )}
            >
              {o}
            </span>
          ),
        )}
      </div>
    </div>
  );
}

/* ---------- 收尾卡 ---------- */
function WrapCard({
  part,
  onDispatch,
}: {
  part: Extract<Part, { type: "wrap" }>;
  onDispatch: (ev: Ev) => void;
}) {
  const [filesOpen, setFilesOpen] = React.useState(false);
  const shown = filesOpen ? part.files : part.files.slice(0, 5);
  return (
    <div className="mt-2.5 rounded-xl border border-green-600/25 bg-green-500/[0.06] p-3">
      <div className="mb-1.5 flex items-center gap-2">
        <Check className="size-4 text-green-600" strokeWidth={3} />
        <span className="text-sm font-semibold">本轮完成</span>
        <Badge variant="outline" className="border-green-600/30 bg-background text-green-700">
          版本 {part.version}
        </Badge>
      </div>
      <p className="text-sm leading-relaxed">{part.summary}</p>
      {part.lines.map((l, i) => (
        <div key={i} className="mt-1.5 flex items-start gap-2 text-[13px]">
          {l.side === "doc" ? (
            <FileText className="mt-0.5 size-3.5 shrink-0 text-muted-foreground" />
          ) : (
            <Monitor className="mt-0.5 size-3.5 shrink-0 text-muted-foreground" />
          )}
          <span>{l.text}</span>
        </div>
      ))}
      {part.files.length > 0 ? (
        <Collapsible open={filesOpen} onOpenChange={setFilesOpen} className="mt-2.5 rounded-lg border bg-background px-2.5 py-2">
          <div className="font-mono text-xs">
            {shown.map(([name, add, del]) => (
              <div key={name} className="flex items-center justify-between gap-2 py-0.5">
                <span className="min-w-0 truncate text-foreground/80">{name}</span>
                <span className="shrink-0 tabular-nums">
                  <span className="text-green-600">+{add}</span>
                  {del > 0 ? <span className="text-destructive"> −{del}</span> : null}
                </span>
              </div>
            ))}
          </div>
          {part.files.length > 5 ? (
            <CollapsibleTrigger className="mt-1 flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground">
              <ChevronDown className={cn("size-3.5 transition-transform", filesOpen && "rotate-180")} />
              {filesOpen ? "收起" : `查看全部 ${part.files.length} 个文件`}
            </CollapsibleTrigger>
          ) : null}
          <CollapsibleContent />
        </Collapsible>
      ) : null}
      <div className="mt-2.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
        <span className="flex items-center gap-1">
          <Clock3 className="size-3.5" /> 用时 <b className="font-medium text-foreground/80">{part.stats.time}</b>
        </span>
        <span className="flex items-center gap-1">
          <Files className="size-3.5" /> <b className="font-medium text-foreground/80">{part.stats.files}</b> 个文件
        </span>
        <span className="tabular-nums">
          <span className="font-medium text-green-600">+{part.stats.add}</span>
          {part.stats.del > 0 ? <span className="font-medium text-destructive"> −{part.stats.del}</span> : null} 行
        </span>
      </div>
      <div className="mt-3 flex gap-2">
        <Button size="sm" variant="outline" className="h-7 bg-background text-xs" onClick={() => onDispatch({ t: "view-version", n: part.version })}>
          <Eye className="size-3.5" /> 查看当时
        </Button>
        <Button size="sm" variant="outline" className="h-7 bg-background text-xs" onClick={() => onDispatch({ t: "view-version", n: part.version })}>
          <RotateCcw className="size-3.5" /> 回滚到此
        </Button>
      </div>
    </div>
  );
}

function FailedCard({
  part,
  onRetry,
}: {
  part: Extract<Part, { type: "wrap-failed" }>;
  onRetry: () => void;
}) {
  return (
    <div className="mt-2.5 rounded-xl border border-destructive/25 bg-destructive/[0.05] p-3">
      <div className="mb-1.5 flex items-center gap-2">
        <TriangleAlert className="size-4 text-destructive" />
        <span className="text-sm font-semibold text-destructive">这轮没做完</span>
      </div>
      <p className="text-sm leading-relaxed">{part.reason}</p>
      <div className="mt-3">
        <Button size="sm" className="h-7 text-xs" onClick={onRetry}>
          <RefreshCw className="size-3.5" /> 重新尝试
        </Button>
      </div>
    </div>
  );
}

/* ---------- 成果区 ---------- */
function OutputsArea({ state, onDispatch }: { state: State; onDispatch: (ev: Ev) => void }) {
  return (
    <div className="flex min-w-0 flex-1 flex-col">
      <Tabs
        value={state.tab}
        onValueChange={(v) => onDispatch({ t: "set-tab", tab: v })}
        className="flex min-h-0 flex-1 flex-col"
      >
        <TabsList className="m-2 w-fit">
          <TabsTrigger value="system" className="px-4">系统</TabsTrigger>
          <TabsTrigger value="doc" className="px-4">文档</TabsTrigger>
          <TabsTrigger value="project" className="px-4">项目</TabsTrigger>
        </TabsList>
        <div className="mx-3 mt-1 mb-3 flex min-h-0 flex-1 flex-col overflow-hidden rounded-xl border">
          {state.viewing !== null && state.tab === "system" ? (
            <div className="flex shrink-0 items-center gap-2 border-b border-amber-500/30 bg-amber-500/10 px-3 py-2 text-[13px]">
              <Eye className="size-4 text-amber-600" />
              <span>
                正在查看 <b>版本 {state.viewing}</b> 的系统（当时的样貌）
              </span>
              <span className="flex-1" />
              <Button size="sm" className="h-7 text-xs" onClick={() => onDispatch({ t: "rollback" })}>
                <RotateCcw className="size-3.5" /> 回滚到此
              </Button>
              <Button size="sm" variant="outline" className="h-7 bg-background text-xs" onClick={() => onDispatch({ t: "back-to-current" })}>
                返回当前
              </Button>
            </div>
          ) : null}
          {state.tab === "system" ? (
            <SystemView
              stage={
                state.viewing !== null
                  ? state.versions.find((v) => v.n === state.viewing)?.stage ?? 0
                  : state.previewStage
              }
            />
          ) : state.tab === "doc" ? (
            <DocView doc={state.doc} />
          ) : (
            <Empty className="flex-1">
              <EmptyHeader>
                <EmptyMedia variant="icon">
                  <ReceiptText />
                </EmptyMedia>
                <EmptyTitle>还没有订单</EmptyTitle>
                <EmptyDescription>系统做好后，可以在这里下单购买</EmptyDescription>
              </EmptyHeader>
            </Empty>
          )}
        </div>
      </Tabs>
    </div>
  );
}

function SystemView({ stage }: { stage: number }) {
  return (
    <>
      <div className="flex h-9 shrink-0 items-center gap-2 border-b bg-muted/40 px-3">
        <RefreshCw className="size-3.5 text-muted-foreground" />
        <div className="mx-auto flex w-full max-w-md items-center gap-1.5 rounded-md border bg-background px-2.5 py-1 text-xs text-muted-foreground">
          <Lock className="size-3" /> preview·巷口花店.做系统.app
        </div>
        <span className="size-3.5" />
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto">
        {stage === 0 ? (
          <Empty className="h-full">
            <EmptyHeader>
              <EmptyMedia variant="icon">
                <Flower2 />
              </EmptyMedia>
              <EmptyTitle>系统还没有做出来</EmptyTitle>
              <EmptyDescription>开工后，这里会一点点长出你的花店小程序</EmptyDescription>
            </EmptyHeader>
          </Empty>
        ) : stage === 1 ? (
          <div className="space-y-3 p-4">
            <Skeleton className="h-9 w-full" />
            <Skeleton className="h-28 w-full" />
            <div className="grid grid-cols-3 gap-2.5">
              <Skeleton className="h-28" />
              <Skeleton className="h-28" />
              <Skeleton className="h-28" />
            </div>
            <Skeleton className="h-9 w-full" />
          </div>
        ) : (
          <ShopPreview stage={stage} />
        )}
      </div>
    </>
  );
}

/* 花店小程序预览：2 黑白成型 / 3 彩色绿 / 4 粉色 / 5 粉色+会员 */
function ShopPreview({ stage }: { stage: number }) {
  const pink = stage >= 4;
  const colored = stage >= 3;
  const member = stage >= 5;
  const flowers: [string, number, string][] = [
    ["粉玫瑰", 68, "🌹"],
    ["向日葵", 45, "🌻"],
    ["洋桔梗", 52, "💐"],
  ];
  return (
    <div className="text-[13px]">
      <div className="flex items-center border-b px-3.5 py-2.5">
        <span className="text-[15px] font-bold">🌷 巷口花店</span>
        <span className="ml-auto text-muted-foreground">🛒</span>
      </div>
      <div
        className={cn(
          "px-4 py-5",
          colored && (pink
            ? "bg-gradient-to-br from-pink-50 to-rose-50"
            : "bg-gradient-to-br from-green-50 to-emerald-50"),
        )}
      >
        {colored ? (
          <>
            <h2 className="mb-1 text-lg font-semibold">今日鲜花 · 当日送达</h2>
            <p className="mb-3 text-muted-foreground">巷口花店，把新鲜送到手上</p>
            <span
              className={cn(
                "inline-block rounded-full px-4 py-1.5 text-[13px] font-semibold text-white",
                pink ? "bg-pink-600" : "bg-green-600",
              )}
            >
              去逛逛
            </span>
          </>
        ) : (
          <div className="space-y-2">
            <Skeleton className="h-5 w-3/5" />
            <Skeleton className="h-3.5 w-4/5" />
            <Skeleton className="h-8 w-24 rounded-full" />
          </div>
        )}
      </div>
      {member ? (
        <div className="mx-3.5 mb-3 flex items-center gap-2 rounded-lg border border-amber-500/30 bg-amber-500/10 px-3 py-2.5">
          💳 <span><b>会员充值</b> 充 100 送 20，下单直接抵</span>
        </div>
      ) : null}
      <div className="grid grid-cols-3 gap-2.5 px-3.5 pt-1 pb-3.5">
        {flowers.map(([name, price, emoji]) => (
          <div key={name} className="overflow-hidden rounded-lg border">
            <div
              className={cn(
                "flex h-[74px] items-center justify-center text-[34px]",
                colored ? (pink ? "bg-pink-50" : "bg-green-50") : "bg-muted",
              )}
            >
              {colored ? emoji : <Skeleton className="size-10" />}
            </div>
            <div className="px-2.5 pt-1.5 pb-2">
              <div className="font-semibold">{name}</div>
              <div className={cn("mt-0.5 font-bold", colored ? (pink ? "text-pink-600" : "text-green-600") : "text-muted-foreground")}>
                ¥{price}
              </div>
            </div>
          </div>
        ))}
      </div>
      <div className="flex border-t py-2 text-xs">
        {["首页", "分类", "购物车", "我的"].map((t, i) => (
          <span key={t} className={cn("flex-1 text-center", i === 0 ? "font-semibold" : "text-muted-foreground")}>
            {t}
          </span>
        ))}
      </div>
    </div>
  );
}

function DocView({ doc }: { doc: State["doc"] }) {
  return (
    <div className="min-h-0 flex-1 overflow-y-auto">
      <div className="mx-auto max-w-xl px-6 py-6">
        <h1 className="text-lg font-semibold">巷口花店小程序 · 需求文档</h1>
        <p className="mb-5 mt-0.5 text-xs text-muted-foreground">由访谈整理，随每轮修改更新</p>
        <h3 className="mb-1 mt-4 text-sm font-semibold">一、做什么</h3>
        <p className="text-[13.5px] text-foreground/80">
          给「巷口花店」做一个微信小程序：客人能浏览鲜花、下单付款，店主能收到订单。
        </p>
        <h3 className="mb-1 mt-4 text-sm font-semibold">二、整体风格</h3>
        <p className="text-[13.5px] text-foreground/80">
          {doc.pink ? <Fresh>整体配色为粉色系，温馨柔和。</Fresh> : "整体配色为绿色系，清新自然。"}
        </p>
        <h3 className="mb-1 mt-4 text-sm font-semibold">三、配送说明</h3>
        <p className="text-[13.5px] text-foreground/80">
          {doc.citywide ? <Fresh>全城配送。</Fresh> : "门店 3 公里内配送。"}
        </p>
        {doc.member ? (
          <>
            <h3 className="mb-1 mt-4 text-sm font-semibold">四、会员充值</h3>
            <p className="text-[13.5px] text-foreground/80">
              <Fresh>会员可充值余额，充 100 送 20，下单可用余额支付。</Fresh>
            </p>
          </>
        ) : null}
      </div>
    </div>
  );
}

/** 本轮更新过的文档段落：淡黄标记。 */
function Fresh({ children }: { children: React.ReactNode }) {
  return <mark className="rounded bg-amber-500/15 px-1 text-inherit">{children}</mark>;
}

/* ---------- 原型控制条（非产品） ---------- */
function ProtoBar({
  scenarioIdx,
  playing,
  speed,
  onSelect,
  onPlay,
  onReset,
  onSpeed,
}: {
  scenarioIdx: number;
  playing: boolean;
  speed: number;
  onSelect: (idx: number) => void;
  onPlay: () => void;
  onReset: () => void;
  onSpeed: (n: number) => void;
}) {
  return (
    <div className="shrink-0 bg-zinc-900 text-zinc-100">
      <div className="flex flex-wrap items-center gap-2 px-3 py-2 text-[13px]">
        <Popover>
          <PopoverTrigger className="flex items-center gap-1 text-zinc-400 hover:text-zinc-100">
            <Info className="size-3.5" /> 这是什么
          </PopoverTrigger>
          <PopoverContent className="w-[460px] text-[13px] leading-relaxed">
            <b>原型 · 过程可见性（#68）</b>
            <br />
            验证问题：非技术用户「看着系统一点点做出来」的感觉是否成立。
            <br />
            玩法：选一个场景 → ▶ 播放。对话区会模拟智能体干活的全过程，右边成果区是系统预览。所有内容都是演的，不是真在生成。
            <br />
            走查时让朋友自己点，别解说；每个场景下面的黄字是给你（主持人）的观察要点。场景④里问答卡需要朋友自己选一个答案才会继续；场景⑤最后的「重新尝试」也可以点。收尾卡上的「查看当时 / 回滚到此」随时可点。
          </PopoverContent>
        </Popover>
        <div className="flex flex-wrap gap-1.5">
          {SCENARIOS.map((sc, i) => (
            <button
              key={sc.id}
              onClick={() => onSelect(i)}
              className={cn(
                "rounded-full border border-zinc-700 bg-zinc-800 px-3 py-1 text-zinc-300 transition-colors",
                i === scenarioIdx && "border-zinc-100 bg-zinc-100 font-semibold text-zinc-900",
              )}
            >
              {sc.name}
            </button>
          ))}
        </div>
        <div className="ml-auto flex items-center gap-2">
          <ToggleGroup
            variant="outline"
            value={[String(speed)]}
            onValueChange={(v) => v.length && onSpeed(Number(v[0]))}
            className="gap-0"
          >
            {[1, 2, 4].map((n) => (
              <ToggleGroupItem
                key={n}
                value={String(n)}
                className="h-7 border-zinc-700 bg-transparent px-2.5 text-xs text-zinc-400 data-pressed:bg-zinc-700 data-pressed:text-zinc-100"
              >
                {n}×
              </ToggleGroupItem>
            ))}
          </ToggleGroup>
          <Button size="sm" className="h-7 text-xs" disabled={playing} onClick={onPlay}>
            <Play className="size-3.5" /> 播放
          </Button>
          <Button size="sm" variant="outline" className="h-7 border-zinc-700 bg-transparent text-xs text-zinc-300 hover:bg-zinc-800 hover:text-zinc-100" onClick={onReset}>
            重置
          </Button>
        </div>
      </div>
      <div className="px-3 pb-1.5 text-xs text-amber-400">
        👁 {SCENARIOS[scenarioIdx].watch}
      </div>
    </div>
  );
}
