"use client";

/**
 * ============================================================================
 * 原 型 —— 平台整体 UI（#72）：首页（对话主角式，已定方向）
 * ============================================================================
 * 侧栏可展开/收起；中央 = 立体大输入框（附件/类型下拉/语音/发送）+
 * 示例 chips + 模板卡 + 最近项目。克制的底色渐变（Lovable 式，不喧宾夺主）。
 * ============================================================================
 */

import * as React from "react";
import Link from "next/link";
import { CalendarCheck, ChevronRight, Clock3, Store, UtensilsCrossed } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";

import { Composer } from "../_shared/chat-parts";
import { PROTO_PROJECTS, ProjectAvatar, ProtoSidebar } from "../_shared/sidebar";
import { ProtoBar } from "../_shared/switcher";

const EXAMPLES = ["帮我的花店做个能下单的小程序", "做一个餐厅扫码点单系统", "做个瑜伽馆课程预约页"];

const TEMPLATES = [
  { icon: <Store className="size-5" />, tint: "bg-rose-100 text-rose-600 dark:bg-rose-950 dark:text-rose-300", t: "花店小程序", d: "展示鲜花、在线下单" },
  { icon: <UtensilsCrossed className="size-5" />, tint: "bg-amber-100 text-amber-600 dark:bg-amber-950 dark:text-amber-300", t: "餐厅点单", d: "扫码看菜单、下单" },
  { icon: <CalendarCheck className="size-5" />, tint: "bg-emerald-100 text-emerald-600 dark:bg-emerald-950 dark:text-emerald-300", t: "预约系统", d: "课程表、在线预约" },
];

export default function PlatformHome() {
  return (
    <div className="flex h-svh bg-[#fafaf9] dark:bg-background">
      <ProtoSidebar
        active="home"
        homeHref="/proto/platform"
        projectHref={() => "/proto/platform/project"}
      />
      {/* 克制的底色层次：顶部一线极淡的主色晕，向下即隐（不喧宾夺主） */}
      <main className="relative min-w-0 flex-1 overflow-y-auto">
        <div className="pointer-events-none absolute inset-x-0 top-0 h-72 bg-[radial-gradient(60%_100%_at_50%_0%,var(--color-primary)_0%,transparent_100%)] opacity-[0.05]" />
        <div className="relative mx-auto flex w-full max-w-2xl flex-col px-6 pb-24 pt-16">
          <h1 className="text-center text-3xl font-bold tracking-tight md:text-4xl">想做什么，直接说</h1>
          <p className="mt-3 text-center text-[15px] text-muted-foreground">
            聊清楚需求，看着它一点点变成能用的系统
          </p>
          <div className="mt-6">
            <Composer hero />
          </div>
          <div className="mt-4 flex flex-wrap justify-center gap-2">
            {EXAMPLES.map((e) => (
              <button
                key={e}
                className="rounded-full border bg-background px-3.5 py-1.5 text-xs text-muted-foreground transition-all hover:border-primary/40 hover:text-foreground active:scale-95"
              >
                {e}
              </button>
            ))}
          </div>
          <div className="mt-12 grid grid-cols-3 gap-3">
            {TEMPLATES.map((c) => (
              <button
                key={c.t}
                className="group rounded-2xl border bg-background p-4 text-left shadow-sm transition-all hover:-translate-y-0.5 hover:border-foreground/20 hover:shadow-md active:translate-y-0"
              >
                <span className={`inline-flex rounded-xl p-2 ${c.tint}`}>{c.icon}</span>
                <div className="mt-2.5 text-sm font-semibold">{c.t}</div>
                <div className="mt-0.5 text-xs text-muted-foreground">{c.d}</div>
              </button>
            ))}
          </div>
          <p className="mt-5 text-center text-xs text-muted-foreground">
            点模板一句话开工，或直接在输入框里描述你的想法
          </p>

          {/* 最近的项目（Lovable 式列表卡） */}
          <div className="mt-14">
            <div className="mb-3 flex items-center justify-between">
              <h2 className="text-sm font-semibold">最近的项目</h2>
              <Button size="xs" variant="ghost" className="text-muted-foreground">
                查看全部 <ChevronRight className="size-3" />
              </Button>
            </div>
            <div className="grid grid-cols-3 gap-3">
              {PROTO_PROJECTS.map((p) => (
                <Link
                  key={p.id}
                  href="/proto/platform/project"
                  className="group rounded-2xl border bg-background p-4 shadow-sm transition-all hover:-translate-y-0.5 hover:border-foreground/20 hover:shadow-md"
                >
                  <ProjectAvatar name={p.name} tint={p.tint} />
                  <div className="mt-2.5 truncate text-sm font-semibold">{p.name}</div>
                  <div className="mt-1.5 flex items-center gap-1.5 text-xs text-muted-foreground">
                    <Badge variant="secondary" className="px-1.5 py-0 text-[11px]">{p.stage}</Badge>
                    <Clock3 className="size-3" /> {p.updated}
                  </div>
                </Link>
              ))}
            </div>
          </div>
        </div>
      </main>
      <ProtoBar />
    </div>
  );
}
