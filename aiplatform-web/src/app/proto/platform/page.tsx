"use client";

/**
 * ============================================================================
 * 原 型 —— 平台整体 UI（#72）：首页，三个设计方向，?variant= 切换
 * ============================================================================
 */

import * as React from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import {
  ChevronRight,
  CircleUser,
  Clock3,
  FolderOpen,
  Home,
  LayoutTemplate,
  Plus,
  Settings,
  Sparkles,
} from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

import { Brand, Composer } from "../_shared/chat-parts";
import { ProtoSwitcher, VARIANTS } from "../_shared/switcher";

const PROJECTS = [
  { id: "flower", name: "巷口花店小程序", stage: "迭代中", updated: "2 小时前", emoji: "🌷" },
  { id: "groupon", name: "社区团购站", stage: "访谈中", updated: "昨天", emoji: "🛒" },
  { id: "yoga", name: "瑜伽馆预约", stage: "已发布", updated: "3 天前", emoji: "🧘" },
];

const EXAMPLES = ["帮我的花店做个能下单的小程序", "做一个餐厅扫码点单系统", "做个瑜伽馆课程预约页"];

function projectHref(variant: string) {
  return `/proto/platform/project?variant=${variant}`;
}

export default function PlatformHome() {
  return (
    <React.Suspense>
      <PlatformHomeInner />
    </React.Suspense>
  );
}

function PlatformHomeInner() {
  const variant = useSearchParams().get("variant") ?? VARIANTS[0].key;
  return (
    <>
      {variant === "chat-first" ? <HomeB /> : variant === "nav-rail" ? <HomeC /> : <HomeA />}
      <ProtoSwitcher current={variant} />
    </>
  );
}

/* ================= A · 工作台双栏：顶栏 + 居中 hero + 项目卡网格 ================= */

function HomeA() {
  return (
    <div className="flex h-svh flex-col">
      <header className="flex h-12 shrink-0 items-center gap-3 border-b px-4">
        <Brand />
        <nav className="ml-4 flex items-center gap-1 text-sm text-muted-foreground">
          <span className="rounded-md bg-muted px-2.5 py-1 font-medium text-foreground">首页</span>
          <span className="rounded-md px-2.5 py-1 hover:bg-muted/60">项目</span>
        </nav>
        <CircleUser className="ml-auto size-5 text-muted-foreground" />
      </header>
      <main className="min-h-0 flex-1 overflow-y-auto">
        <div className="mx-auto flex w-full max-w-3xl flex-col items-center px-4 pt-20 pb-24">
          <h1 className="text-3xl font-bold tracking-tight">一句话，开始做出你想要的东西</h1>
          <p className="mt-3 text-muted-foreground">平台陪你把需求聊清楚，做成能用的系统——想调整随时提</p>
          <Composer hero />
          <div className="mt-4 flex flex-wrap justify-center gap-2">
            {EXAMPLES.map((e) => (
              <button key={e} className="rounded-full border px-3 py-1.5 text-xs text-muted-foreground transition-colors hover:border-foreground/30 hover:text-foreground">
                {e}
              </button>
            ))}
          </div>
          <div className="mt-16 w-full">
            <div className="mb-3 flex items-center justify-between">
              <h2 className="text-sm font-semibold text-muted-foreground">最近的项目</h2>
              <Button size="xs" variant="ghost">查看全部 <ChevronRight className="size-3" /></Button>
            </div>
            <div className="grid grid-cols-3 gap-3">
              {PROJECTS.map((p) => (
                <Link key={p.id} href={projectHref("workbench")} className="group rounded-xl border p-4 transition-colors hover:border-foreground/25">
                  <div className="text-2xl">{p.emoji}</div>
                  <div className="mt-2 truncate text-sm font-medium">{p.name}</div>
                  <div className="mt-1 flex items-center gap-1.5 text-xs text-muted-foreground">
                    <Badge variant="secondary" className="px-1.5 py-0 text-[11px]">{p.stage}</Badge>
                    {p.updated}
                  </div>
                </Link>
              ))}
            </div>
          </div>
        </div>
      </main>
    </div>
  );
}

/* ================= B · 对话主角：左侧栏 + 居中对谈式入口 ================= */

function HomeB() {
  return (
    <div className="flex h-svh">
      <aside className="flex w-56 shrink-0 flex-col border-r bg-muted/30 p-2.5">
        <div className="px-1.5 py-1"><Brand /></div>
        <Button className="mt-3 w-full justify-start" size="sm">
          <Plus className="size-4" /> 新建项目
        </Button>
        <div className="mt-4 px-2 text-xs font-semibold text-muted-foreground">能做这些</div>
        {["做系统", "做页面", "写文档"].map((m, i) => (
          <button key={m} className={cn("mt-0.5 flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm", i === 0 ? "bg-muted font-medium" : "text-muted-foreground hover:bg-muted/60")}>
            <Sparkles className="size-3.5" /> {m}
            {i > 0 && <span className="ml-auto text-[10px] text-muted-foreground/60">敬请期待</span>}
          </button>
        ))}
        <div className="mt-4 px-2 text-xs font-semibold text-muted-foreground">历史项目</div>
        {PROJECTS.map((p) => (
          <Link key={p.id} href={projectHref("chat-first")} className="mt-0.5 flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm text-muted-foreground hover:bg-muted/60">
            <span>{p.emoji}</span>
            <span className="min-w-0 flex-1 truncate">{p.name}</span>
          </Link>
        ))}
        <div className="mt-auto flex items-center gap-2 rounded-md px-2 py-1.5 text-sm text-muted-foreground">
          <CircleUser className="size-4" /> 我的账号
        </div>
      </aside>
      <main className="flex min-w-0 flex-1 flex-col items-center overflow-y-auto px-6 pt-24 pb-24">
        <div className="w-full max-w-2xl">
          <h1 className="text-center text-2xl font-bold tracking-tight">想做什么，直接说</h1>
          <p className="mt-2 text-center text-sm text-muted-foreground">聊清楚需求，看着它一点点变成能用的系统</p>
          <div className="mt-6"><Composer hero /></div>
          <div className="mt-8 grid grid-cols-3 gap-3">
            {[
              { e: "🌷", t: "花店小程序", d: "展示鲜花、在线下单" },
              { e: "🍜", t: "餐厅点单", d: "扫码看菜单、下单" },
              { e: "🧘", t: "预约系统", d: "课程表、在线预约" },
            ].map((c) => (
              <button key={c.t} className="rounded-xl border p-4 text-left transition-colors hover:border-foreground/25">
                <div className="text-xl">{c.e}</div>
                <div className="mt-1.5 text-sm font-medium">{c.t}</div>
                <div className="mt-0.5 text-xs text-muted-foreground">{c.d}</div>
              </button>
            ))}
          </div>
          <p className="mt-6 text-center text-xs text-muted-foreground">点模板一句话开工，或直接在输入框里描述你的想法</p>
        </div>
      </main>
    </div>
  );
}

/* ================= C · 导航栏多页：左导航 + 内容页 ================= */

function HomeC() {
  return (
    <div className="flex h-svh">
      <aside className="flex w-52 shrink-0 flex-col border-r p-2.5">
        <div className="px-1.5 py-1"><Brand /></div>
        <nav className="mt-3 space-y-0.5">
          {[
            { icon: <Home className="size-4" />, label: "首页", active: true },
            { icon: <FolderOpen className="size-4" />, label: "我的项目", active: false },
            { icon: <LayoutTemplate className="size-4" />, label: "模板", active: false, soon: true },
            { icon: <Settings className="size-4" />, label: "设置", active: false, soon: true },
          ].map((n) => (
            <button key={n.label} className={cn("flex w-full items-center gap-2.5 rounded-md px-2.5 py-2 text-sm", n.active ? "bg-muted font-medium" : "text-muted-foreground hover:bg-muted/60")}>
              {n.icon} {n.label}
              {n.soon && <span className="ml-auto text-[10px] text-muted-foreground/60">敬请期待</span>}
            </button>
          ))}
        </nav>
        <div className="mt-auto flex items-center gap-2 rounded-md px-2.5 py-2 text-sm text-muted-foreground">
          <CircleUser className="size-4" /> 我的账号
        </div>
      </aside>
      <main className="min-w-0 flex-1 overflow-y-auto">
        <div className="border-b bg-muted/20 px-8 py-8">
          <h1 className="text-xl font-bold">下午好，接着做，或者开一个新的</h1>
          <div className="mt-4 max-w-2xl"><Composer hero /></div>
        </div>
        <div className="px-8 py-6 pb-24">
          <h2 className="mb-3 text-sm font-semibold text-muted-foreground">我的项目</h2>
          <div className="divide-y rounded-xl border">
            {PROJECTS.map((p) => (
              <Link key={p.id} href={projectHref("nav-rail")} className="flex items-center gap-4 px-4 py-3.5 transition-colors hover:bg-muted/40">
                <span className="text-xl">{p.emoji}</span>
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-sm font-medium">{p.name}</span>
                </span>
                <Badge variant="secondary">{p.stage}</Badge>
                <span className="flex items-center gap-1 text-xs text-muted-foreground"><Clock3 className="size-3" />{p.updated}</span>
                <ChevronRight className="size-4 text-muted-foreground" />
              </Link>
            ))}
            <button className="flex w-full items-center gap-2 px-4 py-3 text-sm text-muted-foreground hover:bg-muted/40">
              <Plus className="size-4" /> 新建项目
            </button>
          </div>
        </div>
      </main>
    </div>
  );
}
