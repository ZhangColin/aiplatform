"use client";

/**
 * 原型共享：浮动切换条（非产品 UI，走查用）。
 * 左箭/右箭轮换设计方向（写 ?variant= 保持可分享），方向说明可点开；
 * 项目页附场景播放控制；首页/项目页互跳。
 */

import * as React from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { ChevronLeft, ChevronRight, Info, Play } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { cn } from "@/lib/utils";

import { SCENARIOS } from "./run-engine";
import type { RunEngine } from "./use-run-engine";

export type Variant = { key: string; name: string; idea: string };

export const VARIANTS: Variant[] = [
  {
    key: "workbench",
    name: "A · 工作台双栏",
    idea: "Replit/扣子式：左对话 + 右工作区 tab 簇（「+」可挂新面：文件/终端/订单/数据…）。扩展 = 工作区标签页。适合「边聊边看东西做出来」。",
  },
  {
    key: "chat-first",
    name: "B · 对话主角",
    idea: "Kimi/ChatGPT 式：对话居中当主角，工作区平时收起、有成果才从右侧滑出。扩展 = 左侧栏条目 + 滑出工作区 tab。对话沉浸感最强，但「边做边看」弱一档。",
  },
  {
    key: "nav-rail",
    name: "C · 导航栏多页",
    idea: "SaaS 式：左侧导航栏挂所有面（对话/系统/文档/文件/终端/订单），每个面一整页；任何页面都能呼出对话浮窗看过程。扩展 = 导航项。结构最规整，最像传统系统。",
  },
];

export function ProtoSwitcher({
  current,
  engine,
}: {
  current: string;
  engine?: RunEngine;
}) {
  const router = useRouter();
  const searchParams = useSearchParams();
  const idx = Math.max(0, VARIANTS.findIndex((v) => v.key === current));
  const variant = VARIANTS[idx];

  const go = React.useCallback(
    (nextIdx: number) => {
      const wrapped = (nextIdx + VARIANTS.length) % VARIANTS.length;
      const params = new URLSearchParams(searchParams.toString());
      params.set("variant", VARIANTS[wrapped].key);
      router.replace(`?${params.toString()}`);
    },
    [router, searchParams],
  );

  React.useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement;
      if (t.closest("input,textarea,[contenteditable]")) return;
      if (e.key === "ArrowLeft") go(idx - 1);
      if (e.key === "ArrowRight") go(idx + 1);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [go, idx]);

  return (
    <div className="fixed bottom-4 left-1/2 z-50 flex -translate-x-1/2 items-center gap-1 rounded-full border bg-zinc-900 px-2 py-1.5 text-zinc-100 shadow-xl">
      <span className="rounded bg-amber-500/20 px-1.5 py-0.5 text-[10px] font-bold text-amber-400">原型</span>
      <button onClick={() => go(idx - 1)} className="rounded-full p-1 hover:bg-zinc-700" aria-label="上一个方向">
        <ChevronLeft className="size-4" />
      </button>
      <Popover>
        <PopoverTrigger className="flex items-center gap-1 rounded-full px-2 py-1 text-xs font-semibold hover:bg-zinc-700">
          {variant.name} <Info className="size-3 text-zinc-400" />
        </PopoverTrigger>
        <PopoverContent className="w-80 text-[13px] leading-relaxed">{variant.idea}</PopoverContent>
      </Popover>
      <button onClick={() => go(idx + 1)} className="rounded-full p-1 hover:bg-zinc-700" aria-label="下一个方向">
        <ChevronRight className="size-4" />
      </button>
      <span className="mx-1 h-4 w-px bg-zinc-700" />
      <Link href={`/proto/platform?variant=${variant.key}`} className="rounded-full px-2 py-1 text-xs hover:bg-zinc-700">
        首页
      </Link>
      <Link href={`/proto/platform/project?variant=${variant.key}`} className="rounded-full px-2 py-1 text-xs hover:bg-zinc-700">
        项目页
      </Link>
      {engine ? (
        <>
          <span className="mx-1 h-4 w-px bg-zinc-700" />
          <select
            className="rounded bg-zinc-800 px-1.5 py-1 text-xs"
            value={engine.scenarioIdx}
            onChange={(e) => engine.selectScenario(Number(e.target.value))}
          >
            {SCENARIOS.map((sc, i) => (
              <option key={sc.id} value={i}>{sc.name}</option>
            ))}
          </select>
          <Button
            size="sm"
            className="h-7 px-2.5 text-xs"
            disabled={engine.playing}
            onClick={() => engine.play(engine.scenarioIdx)}
          >
            <Play className="size-3.5" /> 播放
          </Button>
          <div className="flex overflow-hidden rounded border border-zinc-700">
            {[1, 4].map((n) => (
              <button
                key={n}
                onClick={() => engine.setSpeed(n)}
                className={cn("px-1.5 py-1 text-[11px]", engine.speed === n ? "bg-zinc-700 text-zinc-100" : "text-zinc-400")}
              >
                {n}×
              </button>
            ))}
          </div>
        </>
      ) : null}
    </div>
  );
}
