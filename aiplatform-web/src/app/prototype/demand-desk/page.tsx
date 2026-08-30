// PROTOTYPE（throwaway）—— wayfinder #6 需求端界面形态原型入口
// 第二轮（2026-08-30 用户反馈）：壳沿用现有工作台布局，槽位按新业务重排，
// 单变体渲染（第一轮 A/B/C 三壳已比较出局）。生产构建渲染为空（防误上线）。
"use client"

import { VariantD } from "./variant-d"

export default function PrototypeDemandDeskPage() {
  if (process.env.NODE_ENV === "production") return null

  return (
    <div className="relative h-svh">
      <VariantD />
      <span className="fixed top-2 right-3 z-50 rounded-full bg-foreground px-2.5 py-1 text-[11px] font-medium tracking-wide text-background shadow-lg print:hidden">
        PROTOTYPE
      </span>
    </div>
  )
}
