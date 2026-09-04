"use client";

/**
 * 原型共享：页面范式库 + 范式注册表。
 * 「支持不断填入扩展」的字面化：每种面（预览/文档/文件/终端/订单/数据/设置）
 * 是一个自包含范式组件，注册进 PARADIGMS 即可被工作区 tab 簇挂载。
 */

import * as React from "react";
import {
  Braces,
  ChevronRight,
  CreditCard,
  Database,
  Eye,
  FileCode2,
  FileJson2,
  FileText,
  Flower2,
  Folder,
  FolderOpen,
  Lock,
  Monitor,
  Package,
  ReceiptText,
  RefreshCw,
  RotateCcw,
  Settings,
  Smartphone,
  SquareTerminal,
  Truck,
} from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from "@/components/ui/empty";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { cn } from "@/lib/utils";

import type { Ev, RunState } from "./run-engine";

/* ================= 范式：系统预览 ================= */

export function PreviewPane({ state, onDispatch }: { state: RunState; onDispatch: (ev: Ev) => void }) {
  const [device, setDevice] = React.useState<"desktop" | "mobile">("desktop");
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
          <Button size="sm" className="h-7 text-xs transition-transform active:scale-95" onClick={() => onDispatch({ t: "rollback" })}>
            <RotateCcw className="size-3.5" /> 回滚到此
          </Button>
          <Button size="sm" variant="outline" className="h-7 bg-background text-xs transition-transform active:scale-95" onClick={() => onDispatch({ t: "back-to-current" })}>
            返回当前
          </Button>
        </div>
      ) : null}
      {/* 浏览器工具栏（Kimi 式：地址 + 设备切换 + 刷新） */}
      <div className="flex h-10 shrink-0 items-center gap-2 border-b bg-muted/40 px-3">
        <RefreshCw className="size-3.5 text-muted-foreground" />
        <div className="mx-auto flex w-full max-w-md items-center gap-1.5 rounded-full border bg-background px-3 py-1 text-xs text-muted-foreground">
          <Lock className="size-3" /> preview·巷口花店.做系统.app
        </div>
        <ToggleGroup
          value={[device]}
          onValueChange={(v) => v.length && setDevice(v[0] as "desktop" | "mobile")}
          className="gap-0"
        >
          <ToggleGroupItem value="desktop" aria-label="桌面预览" className="h-7 px-2 data-pressed:bg-background data-pressed:shadow-sm">
            <Monitor className="size-3.5" />
          </ToggleGroupItem>
          <ToggleGroupItem value="mobile" aria-label="手机预览" className="h-7 px-2 data-pressed:bg-background data-pressed:shadow-sm">
            <Smartphone className="size-3.5" />
          </ToggleGroupItem>
        </ToggleGroup>
      </div>
      <div className={cn("min-h-0 flex-1 overflow-y-auto", device === "mobile" && "flex justify-center bg-muted/30 p-4")}>
        {stage === 0 ? (
          <Empty className="h-full">
            <EmptyHeader>
              <EmptyMedia variant="icon"><Flower2 /></EmptyMedia>
              <EmptyTitle>系统还没有做出来</EmptyTitle>
              <EmptyDescription>开工后，这里会一点点长出你的花店小程序</EmptyDescription>
            </EmptyHeader>
          </Empty>
        ) : (
          <div className={cn(device === "mobile" && "h-fit w-[390px] overflow-hidden rounded-2xl border bg-background shadow-sm")}>
            {stage === 1 ? (
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
      <div className="w-48 shrink-0 border-r bg-muted/20 p-2">
        <div className="px-2 pb-1.5 text-xs font-semibold text-muted-foreground">项目文档</div>
        {DOCS.map((d) => (
          <button
            key={d.id}
            onClick={() => setActive(d.id)}
            className={cn(
              "flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-[13px] transition-colors",
              active === d.id ? "bg-background font-medium shadow-sm" : "text-muted-foreground hover:bg-background/60",
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
      <h1 className="text-lg font-semibold tracking-tight">巷口花店小程序 · 需求文档</h1>
      <p className="mb-5 mt-0.5 text-xs text-muted-foreground">由访谈整理，随每轮修改更新</p>
      <h3 className="mb-1 mt-4 text-sm font-semibold">一、做什么</h3>
      <p className="text-[13.5px] leading-relaxed text-foreground/80">给「巷口花店」做一个微信小程序：客人能浏览鲜花、下单付款，店主能收到订单。</p>
      <h3 className="mb-1 mt-4 text-sm font-semibold">二、整体风格</h3>
      <p className="text-[13.5px] leading-relaxed text-foreground/80">
        {doc.pink ? <Fresh>整体配色为粉色系，温馨柔和。</Fresh> : "整体配色为绿色系，清新自然。"}
      </p>
      <h3 className="mb-1 mt-4 text-sm font-semibold">三、配送说明</h3>
      <p className="text-[13.5px] leading-relaxed text-foreground/80">
        {doc.citywide ? <Fresh>全城配送。</Fresh> : "门店 3 公里内配送。"}
      </p>
      {doc.member ? (
        <>
          <h3 className="mb-1 mt-4 text-sm font-semibold">四、会员充值</h3>
          <p className="text-[13.5px] leading-relaxed text-foreground/80">
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
      <h1 className="text-lg font-semibold tracking-tight">常见问题</h1>
      <p className="mb-5 mt-0.5 text-xs text-muted-foreground">智能体在沟通过程中顺手整理</p>
      <h3 className="mb-1 mt-4 text-sm font-semibold">做好的系统在哪能看？</h3>
      <p className="text-[13.5px] leading-relaxed text-foreground/80">做好后「系统」页就能直接点开用；正式对外用需要下单发布。</p>
      <h3 className="mb-1 mt-4 text-sm font-semibold">改需求要重新做一遍吗？</h3>
      <p className="text-[13.5px] leading-relaxed text-foreground/80">不用。直接说要改什么，智能体只动相关部分，每轮改动都会留下版本。</p>
    </div>
  );
}

/* ================= 范式：文件（扣子式：树 + M/U 状态角标 + 内容） ================= */

type TreeFile = { path: string; status?: "M" | "U" };
const FILE_DIRS: { dir: string; files: TreeFile[] }[] = [
  { dir: "docs", files: [{ path: "需求文档.md", status: "M" }] },
  {
    dir: "src",
    files: [
      { path: "App.tsx", status: "U" },
      { path: "pages/Home.tsx", status: "U" },
      { path: "components/FlowerCard.tsx", status: "M" },
      { path: "components/Cart.tsx", status: "M" },
      { path: "styles/theme.css", status: "M" },
      { path: "lib/data.ts", status: "U" },
    ],
  },
  { dir: "", files: [{ path: "index.html" }, { path: "package.json" }] },
];

const FILE_CONTENT: Record<string, string[]> = {
  "src/styles/theme.css": [
    ":root {",
    "  --brand: #db2777;        /* 主色：粉 */",
    "  --brand-soft: #fdf2f8;   /* 主色浅底 */",
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

function fileIcon(path: string) {
  if (path.endsWith(".css")) return <Braces className="size-3 shrink-0 text-sky-600" />;
  if (path.endsWith(".tsx")) return <FileCode2 className="size-3 shrink-0 text-blue-600" />;
  if (path.endsWith(".json")) return <FileJson2 className="size-3 shrink-0 text-amber-600" />;
  return <FileText className="size-3 shrink-0 text-muted-foreground" />;
}

export function FilesPane() {
  const [active, setActive] = React.useState("src/styles/theme.css");
  const [openDirs, setOpenDirs] = React.useState<Record<string, boolean>>({ docs: true, src: true });
  const activeStatus = FILE_DIRS.flatMap((d) => d.files.map((f) => ({ ...f, full: d.dir ? `${d.dir}/${f.path}` : f.path }))).find((f) => f.full === active)?.status;
  const content = FILE_CONTENT[active] ?? [`// ${active}`, "//（原型：文件内容占位）"];
  return (
    <div className="flex min-h-0 flex-1">
      <div className="w-56 shrink-0 overflow-y-auto border-r bg-muted/20 p-2">
        <div className="flex items-center px-2 pb-1.5 text-xs font-semibold text-muted-foreground">
          工作区文件
          <span className="ml-auto font-normal">自动提交</span>
        </div>
        {FILE_DIRS.map(({ dir, files }) => (
          <div key={dir || "root"}>
            {dir ? (
              <button
                className="flex w-full items-center gap-1.5 rounded-md px-2 py-1 text-[13px] font-medium transition-colors hover:bg-background/60"
                onClick={() => setOpenDirs((o) => ({ ...o, [dir]: !o[dir] }))}
              >
                <ChevronRight className={cn("size-3.5 transition-transform", openDirs[dir] && "rotate-90")} />
                {openDirs[dir] ? <FolderOpen className="size-3.5 text-muted-foreground" /> : <Folder className="size-3.5 text-muted-foreground" />}
                {dir}
              </button>
            ) : null}
            {(dir ? openDirs[dir] : true) &&
              files.map((f) => {
                const path = dir ? `${dir}/${f.path}` : f.path;
                return (
                  <button
                    key={path}
                    onClick={() => setActive(path)}
                    className={cn(
                      "flex w-full items-center gap-1.5 rounded-md py-1 pr-2 text-left font-mono text-xs transition-colors",
                      dir ? "pl-8" : "pl-4",
                      active === path ? "bg-background text-foreground shadow-sm" : "text-muted-foreground hover:bg-background/60",
                    )}
                  >
                    {fileIcon(f.path)}
                    <span className="min-w-0 flex-1 truncate">{f.path}</span>
                    {f.status === "M" ? <span className="text-[10px] font-bold text-amber-600">M</span> : null}
                    {f.status === "U" ? <span className="text-[10px] font-bold text-green-600">U</span> : null}
                  </button>
                );
              })}
          </div>
        ))}
      </div>
      <div className="flex min-h-0 flex-1 flex-col">
        <div className="flex h-9 shrink-0 items-center gap-2 border-b px-4">
          <span className="font-mono text-xs text-muted-foreground">{active}</span>
          {activeStatus === "M" ? <Badge variant="outline" className="border-amber-500/40 text-amber-700">本轮修改</Badge> : null}
          {activeStatus === "U" ? <Badge variant="outline" className="border-green-600/40 text-green-700">本轮新增</Badge> : null}
        </div>
        <div className="min-h-0 flex-1 overflow-auto bg-muted/20 p-4">
          <pre className="font-mono text-xs leading-relaxed">
            {content.map((line, i) => (
              <div key={i} className="flex">
                <span className="w-8 shrink-0 select-none pr-3 text-right text-muted-foreground/50">{i + 1}</span>
                <span>{line}</span>
              </div>
            ))}
          </pre>
        </div>
      </div>
    </div>
  );
}

/* ================= 范式：终端（dev 面示例） ================= */

const SHELL_LINES: { tone: "cmd" | "dim" | "ok" | "warn"; text: string }[] = [
  { tone: "cmd", text: "pnpm dev" },
  { tone: "dim", text: "▲ 花店小程序 dev server 已启动" },
  { tone: "ok", text: "✓ 本地预览就绪  http://localhost:3000" },
  { tone: "cmd", text: "pnpm test" },
  { tone: "dim", text: "运行 6 个用例…" },
  { tone: "ok", text: "✓ 6 个全部通过（下单 / 余额 / 配送）" },
  { tone: "cmd", text: "git log --oneline -3" },
  { tone: "dim", text: "a1b2c3d 版本 5：接入微信支付" },
  { tone: "dim", text: "d4e5f6g 版本 4：会员充值" },
  { tone: "warn", text: "h7i8j9k 版本 3：配送说明全程配送" },
];

export function ShellPane() {
  return (
    <div className="flex min-h-0 flex-1 flex-col bg-zinc-950">
      <div className="flex h-9 shrink-0 items-center gap-2 border-b border-zinc-800 px-3">
        <SquareTerminal className="size-3.5 text-zinc-500" />
        <span className="text-xs text-zinc-400">沙箱终端</span>
        <span className="ml-auto flex items-center gap-1.5 text-[11px] text-zinc-500">
          <span className="size-1.5 animate-pulse rounded-full bg-green-500" /> 运行中
        </span>
      </div>
      <div className="min-h-0 flex-1 space-y-1 overflow-y-auto p-3 font-mono text-xs">
        {SHELL_LINES.map((l, i) => (
          <div key={i} className={cn(
            l.tone === "dim" && "text-zinc-500",
            l.tone === "ok" && "text-green-400",
            l.tone === "warn" && "text-amber-400",
            l.tone === "cmd" && "text-zinc-200",
          )}>
            {l.tone === "cmd" ? <span className="mr-1.5 text-cyan-400">$</span> : null}
            {l.text}
          </div>
        ))}
        <div className="flex items-center gap-1.5 text-zinc-200">
          <span className="text-cyan-400">$</span>
          <span className="inline-block h-3.5 w-1.5 animate-pulse bg-zinc-400" />
        </div>
      </div>
      <div className="shrink-0 border-t border-zinc-800 px-3 py-1.5 text-[11px] text-zinc-600">
        原型：终端是「未来 dev 面」范式的演示，经同一注册表挂载；非技术用户面默认不挂
      </div>
    </div>
  );
}

/* ================= 范式：订单（项目位） ================= */

const ORDER_STEPS = ["已创建", "待支付", "已支付", "交付上线"];

export function OrderPane() {
  const current = 1;
  return (
    <div className="flex min-h-0 flex-1 items-start justify-center overflow-y-auto p-6">
      <div className="w-full max-w-md">
        <div className="rounded-2xl border p-5 shadow-sm">
          <div className="flex items-center gap-2">
            <ReceiptText className="size-4 text-muted-foreground" />
            <span className="text-sm font-semibold">花店小程序 · 正式上线</span>
            <Badge className="ml-auto border-amber-500/40 bg-amber-500/10 text-amber-700">待支付</Badge>
          </div>
          {/* 状态步进 */}
          <div className="mt-4 flex items-center">
            {ORDER_STEPS.map((s, i) => (
              <React.Fragment key={s}>
                <div className="flex flex-col items-center gap-1">
                  <span className={cn(
                    "flex size-5 items-center justify-center rounded-full text-[10px] font-bold",
                    i < current ? "bg-green-600 text-white" : i === current ? "bg-amber-500 text-white" : "bg-muted text-muted-foreground",
                  )}>
                    {i + 1}
                  </span>
                  <span className={cn("text-[11px]", i === current ? "font-medium text-foreground" : "text-muted-foreground")}>{s}</span>
                </div>
                {i < ORDER_STEPS.length - 1 ? <div className={cn("mx-1 mb-4 h-px flex-1", i < current ? "bg-green-600" : "bg-border")} /> : null}
              </React.Fragment>
            ))}
          </div>
          <Separator className="my-4" />
          <div className="space-y-2 text-[13px]">
            <div className="flex justify-between"><span className="text-muted-foreground">系统制作</span><span>¥ 1,599</span></div>
            <div className="flex justify-between"><span className="text-muted-foreground">首年托管与发布</span><span>¥ 400</span></div>
            <div className="flex justify-between border-t pt-2 text-sm font-semibold"><span>合计</span><span>¥ 1,999</span></div>
          </div>
          <Button className="mt-4 w-full transition-transform active:scale-[0.98]">
            <CreditCard className="size-4" /> 去支付（原型占位）
          </Button>
          <p className="mt-2 text-center text-[11px] text-muted-foreground">支付后平台安排发布上线，全程可在对话里追问进度</p>
        </div>
        <p className="mt-3 text-center text-[11px] text-muted-foreground/70">订单卡是「临时安放的信息位」，可挪位、可扩展</p>
      </div>
    </div>
  );
}

/* ================= 范式：数据（业务数据表） ================= */

const PRODUCT_ROWS = [
  { name: "粉玫瑰", price: "¥68", stock: 32, status: "在售" },
  { name: "向日葵", price: "¥45", stock: 18, status: "在售" },
  { name: "洋桔梗", price: "¥52", stock: 0, status: "售罄" },
  { name: "郁金香", price: "¥58", stock: 25, status: "在售" },
  { name: "满天星", price: "¥36", stock: 41, status: "在售" },
];

export function DataPane() {
  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="flex h-11 shrink-0 items-center gap-2 border-b px-4">
        <Package className="size-4 text-muted-foreground" />
        <span className="text-sm font-medium">商品数据</span>
        <span className="text-xs text-muted-foreground">{PRODUCT_ROWS.length} 条</span>
        <span className="ml-auto text-[11px] text-muted-foreground">系统在用的数据，可查看（编辑是未来增强）</span>
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto p-4">
        <div className="overflow-hidden rounded-xl border">
          <Table>
            <TableHeader>
              <TableRow className="bg-muted/40">
                <TableHead>名称</TableHead>
                <TableHead>价格</TableHead>
                <TableHead>库存</TableHead>
                <TableHead className="text-right">状态</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {PRODUCT_ROWS.map((r) => (
                <TableRow key={r.name}>
                  <TableCell className="font-medium">{r.name}</TableCell>
                  <TableCell className="tabular-nums">{r.price}</TableCell>
                  <TableCell className="tabular-nums">{r.stock}</TableCell>
                  <TableCell className="text-right">
                    <Badge variant="secondary" className={cn(r.status === "售罄" && "bg-muted text-muted-foreground")}>
                      {r.status}
                    </Badge>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      </div>
    </div>
  );
}

/* ================= 范式：设置 ================= */

export function SettingsPane() {
  return (
    <div className="min-h-0 flex-1 overflow-y-auto">
      <div className="mx-auto max-w-lg space-y-6 px-6 py-6">
        <div>
          <h2 className="text-sm font-semibold">项目设置</h2>
          <p className="mt-0.5 text-xs text-muted-foreground">改这里不影响系统本身；系统内容在对话里改</p>
        </div>
        <div className="space-y-2">
          <Label htmlFor="pname">项目名</Label>
          <Input id="pname" defaultValue="巷口花店小程序" />
          <p className="text-xs text-muted-foreground">给自己看的名字，不出现在做出来的系统里</p>
        </div>
        <div className="flex items-center justify-between rounded-xl border p-4">
          <div>
          <div className="text-sm font-medium">完成后通知我</div>
          <div className="mt-0.5 text-xs text-muted-foreground">每轮做完时发一条通知</div>
          </div>
          <Switch defaultChecked />
        </div>
        <div className="flex items-center justify-between rounded-xl border p-4">
          <div>
            <div className="text-sm font-medium">允许平台参考本项目改进服务</div>
            <div className="mt-0.5 text-xs text-muted-foreground">匿名使用，不含你的业务数据</div>
          </div>
          <Switch />
        </div>
        <Separator />
        <div className="rounded-xl border border-destructive/30 p-4">
          <div className="text-sm font-medium text-destructive">归档项目</div>
          <p className="mt-0.5 text-xs text-muted-foreground">归档后系统停更，对话只读；随时可恢复</p>
          <Button variant="outline" size="sm" className="mt-3 border-destructive/40 text-destructive hover:bg-destructive/5" disabled>
            <Truck className="size-3.5" /> 归档（原型占位）
          </Button>
        </div>
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
  /** 默认挂载？false = 仅在「+」菜单里可加。 */
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
    blurb: "工作区文件树与改动标记",
    render: () => <FilesPane />,
  },
  {
    id: "data", label: "数据", icon: <Database className="size-3.5" />, defaultOn: false,
    blurb: "系统在用的业务数据（只读）",
    render: () => <DataPane />,
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
    id: "settings", label: "设置", icon: <Settings className="size-3.5" />, defaultOn: false,
    blurb: "项目名、通知与归档",
    render: () => <SettingsPane />,
  },
];
