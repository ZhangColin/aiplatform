"use client";

/**
 * ============================================================================
 * 原 型 —— 平台整体 UI（#72）：首页（对话主角式，已定方向）
 * ============================================================================
 * 左侧栏可展开（本页默认展开）↔ 收起（icon rail，项目页默认）；中央 =
 * 一句话大输入框（附件 chip 化）+ 示例 chips + 模板卡。
 * ============================================================================
 */

import * as React from "react";
import { Store, UtensilsCrossed, CalendarCheck } from "lucide-react";

import { Composer } from "../_shared/chat-parts";
import { ProtoSidebar } from "../_shared/sidebar";
import { ProtoBar } from "../_shared/switcher";

const EXAMPLES = ["帮我的花店做个能下单的小程序", "做一个餐厅扫码点单系统", "做个瑜伽馆课程预约页"];

const TEMPLATES = [
  { icon: <Store className="size-5" />, tint: "bg-rose-100 text-rose-600", t: "花店小程序", d: "展示鲜花、在线下单" },
  { icon: <UtensilsCrossed className="size-5" />, tint: "bg-amber-100 text-amber-600", t: "餐厅点单", d: "扫码看菜单、下单" },
  { icon: <CalendarCheck className="size-5" />, tint: "bg-emerald-100 text-emerald-600", t: "预约系统", d: "课程表、在线预约" },
];

export default function PlatformHome() {
  return (
    <div className="flex h-svh">
      <ProtoSidebar
        active="home"
        homeHref="/proto/platform"
        projectHref={() => "/proto/platform/project"}
      />
      <main className="flex min-w-0 flex-1 flex-col items-center overflow-y-auto px-6 pb-24 pt-16">
        <div className="w-full max-w-2xl">
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
                className="rounded-full border px-3.5 py-1.5 text-xs text-muted-foreground transition-all hover:border-primary/40 hover:text-foreground active:scale-95"
              >
                {e}
              </button>
            ))}
          </div>
          <div className="mt-12 grid grid-cols-3 gap-3">
            {TEMPLATES.map((c) => (
              <button
                key={c.t}
                className="group rounded-2xl border p-4 text-left transition-all hover:-translate-y-0.5 hover:border-foreground/20 hover:shadow-md active:translate-y-0"
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
        </div>
      </main>
      <ProtoBar />
    </div>
  );
}
