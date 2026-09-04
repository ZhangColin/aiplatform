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
  "方向：对话主角式（已定）。首页侧栏可展开/收起；项目页对话居中，工作区呼出滑出、宽度可拖（Resizable）。",
  "发送框（Lovable 形态）：立体卡片（ring+分层阴影）+ 附件 chip 行 + 类型下拉（做系统/做页面/写文档）+ 语音位 + 圆形发送键。",
  "个人菜单（Lovable 式左下浮出）：账户 + 主题切换（浅色/深色/跟随系统，真生效）+ 退出登录。",
  "预览底部浮动工具条（Lovable 式）：选择组件/直接改文字/画笔圈选/评论，评论模式可真的点图钉。形态提案，实现归 #73。",
  "层次与色调：暖白底（stone-50）+ 卡片浮起（border+shadow）+ 极淡主色顶晕；深色模式全套可用。",
  "字号阶梯：页面主标 4xl/3xl、区段 14 semibold、正文 14、元数据 12，全站一致。",
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
