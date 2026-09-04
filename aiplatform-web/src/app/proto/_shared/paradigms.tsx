"use client";

/**
 * 原型共享：页面范式库 + 范式注册表。
 * 「支持不断填入扩展」的字面化——每种面（预览/文档/文件/终端/订单/未来更多）
 * 是一个自包含范式组件，注册进 PARADIGMS 即可被各壳的 tab 簇/导航挂载。
 * 各壳共用范式内容；壳的布局各不相同。
 */

import * as React from "react";
import {
  ChevronRight,
  Database,
  Eye,
  FileText,
  Flower2,
  Folder,
  FolderOpen,
  Lock,
  Monitor,
  ReceiptText,
  RefreshCw,
  RotateCcw,
  Settings,
  SquareTerminal,
} from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from "@/components/ui/empty";
import { Skeleton } from "@/components/ui/skeleton";
import { cn } from "@/lib/utils";

import type { Ev, RunState } from "./run-engine";

/* ================= 范式：系统预览 ================= */

export function PreviewPane({ state, onDispatch }: { state: RunState; onDispatch: (ev: Ev) => void }) {
  const stage =
    state.viewing !== null
      ? state.versions.find((v) => v.n === state.viewing)?.stage ?? 0
      : state.previewStage;
  return (
    <div className="flex min-h-0 flex-1 flex-col">
      {state.viewing !== null ? (
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
              <EmptyMedia variant="icon"><Flower2 /></EmptyMedia>
              <EmptyTitle>系统还没有做出来</EmptyTitle>
              <EmptyDescription>开工后，这里会一点点长出你的花店小程序</EmptyDescription>
            </EmptyHeader>
          </Empty>
        ) : stage === 1 ? (
          <div className="space-y-3 p-4">
            <Skeleton className="h-9 w-full" />
            <Skeleton className="h-28 w-full" />
            <div className="grid grid-cols-3 gap-2.5">
              <Skeleton className="h-28" /><Skeleton className="h-28" /><Skeleton className="h-28" />
            </div>
            <Skeleton className="h-9 w-full" />
          </div>
        ) : (
          <ShopPreview stage={stage} />
        )}
      </div>
    </div>
  );
}

function ShopPreview({ stage }: { stage: number }) {
  const pink = stage >= 4;
  const colored = stage >= 3;
  const member = stage >= 5;
  const flowers: [string, number, string][] = [
    ["粉玫瑰", 68, "🌹"], ["向日葵", 45, "🌻"], ["洋桔梗", 52, "💐"],
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
          colored && (pink ? "bg-gradient-to-br from-pink-50 to-rose-50" : "bg-gradient-to-br from-green-50 to-emerald-50"),
        )}
      >
        {colored ? (
          <>
            <h2 className="mb-1 text-lg font-semibold">今日鲜花 · 当日送达</h2>
            <p className="mb-3 text-muted-foreground">巷口花店，把新鲜送到手上</p>
            <span className={cn("inline-block rounded-full px-4 py-1.5 text-[13px] font-semibold text-white", pink ? "bg-pink-600" : "bg-green-600")}>
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
            <div className={cn("flex h-[74px] items-center justify-center text-[34px]", colored ? (pink ? "bg-pink-50" : "bg-green-50") : "bg-muted")}>
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
          <span key={t} className={cn("flex-1 text-center", i === 0 ? "font-semibold" : "text-muted-foreground")}>{t}</span>
        ))}
      </div>
    </div>
  );
}

/* ================= 范式：文档浏览 ================= */

const DOCS = [
  { id: "prd", name: "需求文档", updated: true },
  { id: "faq", name: "常见问题（智能体整理）", updated: false },
];

export function DocBrowserPane({ state }: { state: RunState }) {
  const [active, setActive] = React.useState("prd");
  return (
    <div className="flex min-h-0 flex-1">
      <div className="w-44 shrink-0 border-r p-2">
        <div className="px-2 pb-1.5 text-xs font-semibold text-muted-foreground">项目文档</div>
        {DOCS.map((d) => (
          <button
            key={d.id}
            onClick={() => setActive(d.id)}
            className={cn(
              "flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-[13px]",
              active === d.id ? "bg-muted font-medium" : "text-muted-foreground hover:bg-muted/50",
            )}
          >
            <FileText className="size-3.5 shrink-0" />
            <span className="min-w-0 flex-1 truncate">{d.name}</span>
            {d.updated && <span className="size-1.5 rounded-full bg-amber-500" title="本轮有更新" />}
          </button>
        ))}
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto">
        {active === "prd" ? <PrdView doc={state.doc} /> : <FaqView />}
      </div>
    </div>
  );
}

function Fresh({ children }: { children: React.ReactNode }) {
  return <mark className="rounded bg-amber-500/15 px-1 text-inherit">{children}</mark>;
}

function PrdView({ doc }: { doc: RunState["doc"] }) {
  return (
    <div className="mx-auto max-w-xl px-6 py-6">
      <h1 className="text-lg font-semibold">巷口花店小程序 · 需求文档</h1>
      <p className="mb-5 mt-0.5 text-xs text-muted-foreground">由访谈整理，随每轮修改更新</p>
      <h3 className="mb-1 mt-4 text-sm font-semibold">一、做什么</h3>
      <p className="text-[13.5px] text-foreground/80">给「巷口花店」做一个微信小程序：客人能浏览鲜花、下单付款，店主能收到订单。</p>
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
  );
}

function FaqView() {
  return (
    <div className="mx-auto max-w-xl px-6 py-6">
      <h1 className="text-lg font-semibold">常见问题</h1>
      <p className="mb-5 mt-0.5 text-xs text-muted-foreground">智能体在沟通过程中顺手整理</p>
      <h3 className="mb-1 mt-4 text-sm font-semibold">做好的系统在哪能看？</h3>
      <p className="text-[13.5px] text-foreground/80">做好后「系统」页就能直接点开用；正式对外用需要下单发布。</p>
      <h3 className="mb-1 mt-4 text-sm font-semibold">改需求要重新做一遍吗？</h3>
      <p className="text-[13.5px] text-foreground/80">不用。直接说要改什么，智能体只动相关部分，每轮改动都会留下版本。</p>
    </div>
  );
}

/* ================= 范式：文件 ================= */

const FILE_TREE = [
  { dir: "docs", files: ["需求文档.md"] },
  { dir: "src", files: ["App.tsx", "pages/Home.tsx", "components/FlowerCard.tsx", "components/Cart.tsx", "styles/theme.css", "lib/data.ts"] },
  { dir: "", files: ["index.html", "package.json"] },
];

const FILE_CONTENT: Record<string, string[]> = {
  "src/styles/theme.css": [
    ":root {",
    "  --brand: #16a34a;        /* 主色：青绿 */",
    "  --brand-soft: #f0fdf4;   /* 主色浅底 */",
    "  --text: #18181b;",
    "}",
    "",
    ".btn-primary {",
    "  background: var(--brand);",
    "  color: #fff;",
    "  border-radius: 999px;",
    "}",
  ],
  "docs/需求文档.md": ["# 巷口花店小程序 · 需求文档", "", "## 一、做什么", "给「巷口花店」做一个微信小程序……"],
};

export function FilesPane() {
  const [active, setActive] = React.useState<string>("src/styles/theme.css");
  const [openDirs, setOpenDirs] = React.useState<Record<string, boolean>>({ docs: true, src: true });
  const content = FILE_CONTENT[active] ?? [`// ${active}`, "// （原型：文件内容占位）"];
  return (
    <div className="flex min-h-0 flex-1">
      <div className="w-52 shrink-0 overflow-y-auto border-r p-2">
        <div className="px-2 pb-1.5 text-xs font-semibold text-muted-foreground">工作区文件</div>
        {FILE_TREE.map(({ dir, files }) => (
          <div key={dir || "root"}>
            {dir ? (
              <button
                className="flex w-full items-center gap-1.5 rounded-md px-2 py-1 text-[13px] font-medium hover:bg-muted/50"
                onClick={() => setOpenDirs((o) => ({ ...o, [dir]: !o[dir] }))}
              >
                <ChevronRight className={cn("size-3.5 transition-transform", openDirs[dir] && "rotate-90")} />
                {openDirs[dir] ? <FolderOpen className="size-3.5 text-muted-foreground" /> : <Folder className="size-3.5 text-muted-foreground" />}
                {dir}
              </button>
            ) : null}
            {(dir ? openDirs[dir] : true) &&
              files.map((f) => {
                const path = dir ? `${dir}/${f}` : f;
                return (
                  <button
                    key={path}
                    onClick={() => setActive(path)}
                    className={cn(
                      "flex w-full items-center gap-1.5 rounded-md py-1 pr-2 text-left font-mono text-xs",
                      dir ? "pl-8" : "pl-4",
                      active === path ? "bg-muted text-foreground" : "text-muted-foreground hover:bg-muted/50",
                    )}
                  >
                    <FileText className="size-3 shrink-0" />
                    <span className="truncate">{f}</span>
                  </button>
                );
              })}
          </div>
        ))}
      </div>
      <div className="min-h-0 flex-1 overflow-auto bg-muted/20 p-4">
        <div className="mb-2 font-mono text-xs text-muted-foreground">{active}</div>
        <pre className="font-mono text-xs leading-relaxed">
          {content.map((line, i) => (
            <div key={i} className="flex">
              <span className="w-8 shrink-0 select-none text-right pr-3 text-muted-foreground/50">{i + 1}</span>
              <span>{line}</span>
            </div>
          ))}
        </pre>
      </div>
    </div>
  );
}

/* ================= 范式：终端（dev 面示例，演示可扩展） ================= */

export function ShellPane() {
  return (
    <div className="flex min-h-0 flex-1 flex-col bg-zinc-950 p-3 font-mono text-xs text-zinc-300">
      <div className="space-y-1">
        <div><span className="text-green-500">$</span> pnpm dev</div>
        <div className="text-zinc-500">▲ 花店小程序 dev server 已启动</div>
        <div className="text-zinc-500">✓ 本地预览就绪  http://localhost:3000</div>
        <div><span className="text-green-500">$</span> git log --oneline -3</div>
        <div className="text-zinc-500">a1b2c3d 版本 5：接入微信支付</div>
        <div className="text-zinc-500">d4e5f6g 版本 4：会员充值</div>
        <div className="text-zinc-500">h7i8j9k 版本 3：配送说明全程配送</div>
      </div>
      <div className="mt-2 flex items-center gap-1.5">
        <span className="text-green-500">$</span>
        <span className="inline-block h-3.5 w-1.5 animate-pulse bg-zinc-400" />
      </div>
      <div className="mt-auto pt-2 text-[11px] text-zinc-600">
        （原型：终端是「未来 dev 面」范式的占位演示——同一注册表挂进来的，非技术用户面默认不挂）
      </div>
    </div>
  );
}

/* ================= 范式：订单（项目位，临时安放的信息位） ================= */

export function OrderPane() {
  return (
    <div className="flex min-h-0 flex-1 items-center justify-center p-6">
      <div className="w-full max-w-sm rounded-xl border p-5">
        <div className="flex items-center gap-2 text-sm font-semibold">
          <ReceiptText className="size-4" /> 当前订单
        </div>
        <div className="mt-3 space-y-2 text-[13px]">
          <div className="flex justify-between"><span className="text-muted-foreground">内容</span><span>花店小程序 · 正式上线</span></div>
          <div className="flex justify-between"><span className="text-muted-foreground">价格</span><span className="font-semibold">¥ 1,999</span></div>
          <div className="flex justify-between"><span className="text-muted-foreground">状态</span><span className="text-amber-600">待支付</span></div>
        </div>
        <Button className="mt-4 w-full" disabled>去支付（原型占位）</Button>
        <p className="mt-2 text-center text-[11px] text-muted-foreground">订单卡是「临时安放的信息位」，可挪位、可扩展</p>
      </div>
    </div>
  );
}

/* ================= 范式注册表（扩展故事的字面化） ================= */

export type Paradigm = {
  id: string;
  label: string;
  icon: React.ReactNode;
  /** 「+ 新标签页」菜单里的一句话说明。 */
  blurb: string;
  /** app 面默认挂载？false = 仅在「+」菜单里可加（dev 面向示例）。 */
  defaultOn: boolean;
  render: (props: { state: RunState; onDispatch: (ev: Ev) => void }) => React.ReactNode;
};

export const PARADIGMS: Paradigm[] = [
  {
    id: "preview", label: "系统", icon: <Monitor className="size-3.5" />, defaultOn: true,
    blurb: "做出来的系统，边看边用",
    render: ({ state, onDispatch }) => <PreviewPane state={state} onDispatch={onDispatch} />,
  },
  {
    id: "docs", label: "文档", icon: <FileText className="size-3.5" />, defaultOn: true,
    blurb: "需求文档与沟通落文档，随每轮更新",
    render: ({ state }) => <DocBrowserPane state={state} />,
  },
  {
    id: "files", label: "文件", icon: <Folder className="size-3.5" />, defaultOn: false,
    blurb: "工作区文件树与内容（进阶查看）",
    render: () => <FilesPane />,
  },
  {
    id: "order", label: "项目", icon: <ReceiptText className="size-3.5" />, defaultOn: false,
    blurb: "订单与购买（交易尾巴的信息位）",
    render: () => <OrderPane />,
  },
  {
    id: "shell", label: "终端", icon: <SquareTerminal className="size-3.5" />, defaultOn: false,
    blurb: "运行命令与日志（专业人员面示例）",
    render: () => <ShellPane />,
  },
  {
    id: "data", label: "数据", icon: <Database className="size-3.5" />, defaultOn: false,
    blurb: "业务数据表（未来范式占位）",
    render: () => (
      <Empty className="h-full">
        <EmptyHeader>
          <EmptyMedia variant="icon"><Database /></EmptyMedia>
          <EmptyTitle>数据范式占位</EmptyTitle>
          <EmptyDescription>注册即挂载——新面填入的演示</EmptyDescription>
        </EmptyHeader>
      </Empty>
    ),
  },
  {
    id: "settings", label: "设置", icon: <Settings className="size-3.5" />, defaultOn: false,
    blurb: "项目设置（未来范式占位）",
    render: () => (
      <Empty className="h-full">
        <EmptyHeader>
          <EmptyMedia variant="icon"><Settings /></EmptyMedia>
          <EmptyTitle>设置范式占位</EmptyTitle>
          <EmptyDescription>注册即挂载——新面填入的演示</EmptyDescription>
        </EmptyHeader>
      </Empty>
    ),
  },
];
