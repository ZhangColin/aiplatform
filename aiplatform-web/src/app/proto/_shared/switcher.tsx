"use client";

/**
 * 原型共享：底部原型条（非产品 UI，走查用）。
 * 场景播放控制 + 首页/项目页互跳 + 设计说明。
 */

import * as React from "react";
import Link from "next/link";
import { Info, Play } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { cn } from "@/lib/utils";

import { SCENARIOS } from "./run-engine";
import type { RunEngine } from "./use-run-engine";

const DESIGN_NOTES = [
  "方向：对话主角式（已定）。首页侧栏可展开/收起；项目页对话居中，工作区呼出滑出。",
  "发送框：大圆角容器 + 附件 chip 行（可删可加）+ 回形针物料区 + 圆形发送键，对齐 Lovable/Replit 形态。",
  "工作区：tab 条即标题条，「+ 新标签页」挂范式（系统/文档/文件/数据/项目/终端/设置，注册即挂载）。",
  "预览工具栏：地址胶囊 + 桌面/手机切换（Kimi 式）；文件树带 M/U 改动角标（扣子式）。",
  "形状规则：容器 2xl、控件 lg、发送键/chip 圆形；色彩规则：蓝主色锁全站，绿=完成语义，琥珀=「当时/更新」语义。",
  "动效全部有因：工作区滑入（状态切换）、按钮按压（触觉反馈）、打字点（受理空窗）、骨架屏（加载态）。",
];

export function ProtoBar({ engine }: { engine?: RunEngine }) {
  return (
    <div className="fixed bottom-4 left-1/2 z-50 flex -translate-x-1/2 items-center gap-1 rounded-full border bg-zinc-900 px-2 py-1.5 text-zinc-100 shadow-xl">
      <span className="rounded bg-amber-500/20 px-1.5 py-0.5 text-[10px] font-bold text-amber-400">原型</span>
      <Popover>
        <PopoverTrigger className="flex items-center gap-1 rounded-full px-2 py-1 text-xs hover:bg-zinc-700">
          设计说明 <Info className="size-3 text-zinc-400" />
        </PopoverTrigger>
        <PopoverContent className="w-96 text-[13px] leading-relaxed">
          <ul className="list-disc space-y-1.5 pl-4">
            {DESIGN_NOTES.map((n) => <li key={n}>{n}</li>)}
          </ul>
        </PopoverContent>
      </Popover>
      <span className="mx-1 h-4 w-px bg-zinc-700" />
      <Link href="/proto/platform" className="rounded-full px-2 py-1 text-xs hover:bg-zinc-700">首页</Link>
      <Link href="/proto/platform/project" className="rounded-full px-2 py-1 text-xs hover:bg-zinc-700">项目页</Link>
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
