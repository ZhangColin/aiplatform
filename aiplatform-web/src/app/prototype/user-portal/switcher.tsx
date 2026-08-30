// PROTOTYPE（throwaway）—— T5 需求端门户 UX 原型浮动切换条
// 三个结构性差异的变体共用；生产构建整体隐藏。
"use client"

import * as React from "react"
import { useRouter, useSearchParams } from "next/navigation"
import { ChevronLeft, ChevronRight } from "lucide-react"

import { Button } from "@/components/ui/button"

export const VARIANTS = [
  { key: "A", name: "对话优先（CC 首页模式 · 一句话开工）" },
  { key: "B", name: "项目台（订单跟踪式 · 表单建项目）" },
  { key: "C", name: "跟着走向导（待办优先 · 引导式开场）" },
  { key: "D", name: "融合壳（T4 主 Layout + 用户工作台 · 定稿候选）" },
] as const

export function PrototypeSwitcher({ current }: { current: string }) {
  const router = useRouter()
  const searchParams = useSearchParams()
  const index = Math.max(
    0,
    VARIANTS.findIndex((v) => v.key === current)
  )

  const go = React.useCallback(
    (next: number) => {
      const variant = VARIANTS[(next + VARIANTS.length) % VARIANTS.length]
      const params = new URLSearchParams(searchParams.toString())
      params.set("variant", variant.key)
      router.replace(`/prototype/user-portal?${params.toString()}`, { scroll: false })
    },
    [router, searchParams]
  )

  React.useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const el = e.target as HTMLElement | null
      const tag = el?.tagName
      if (tag === "INPUT" || tag === "TEXTAREA" || el?.isContentEditable) return
      if (e.key === "ArrowLeft") go(index - 1)
      if (e.key === "ArrowRight") go(index + 1)
    }
    window.addEventListener("keydown", onKey)
    return () => window.removeEventListener("keydown", onKey)
  }, [go, index])

  const v = VARIANTS[index]

  return (
    <div className="fixed bottom-4 left-1/2 z-50 flex -translate-x-1/2 items-center gap-1 rounded-full border border-foreground/20 bg-foreground px-2 py-1 text-background shadow-lg print:hidden">
      <span className="hidden px-2 text-[11px] font-medium tracking-wide opacity-70 sm:inline">
        PROTOTYPE
      </span>
      <Button
        size="icon-sm"
        variant="ghost"
        className="text-background hover:bg-background/15 hover:text-background"
        aria-label="上一个变体"
        onClick={() => go(index - 1)}
      >
        <ChevronLeft />
      </Button>
      <span className="min-w-44 text-center text-sm font-medium">
        {v.key} — {v.name}
      </span>
      <Button
        size="icon-sm"
        variant="ghost"
        className="text-background hover:bg-background/15 hover:text-background"
        aria-label="下一个变体"
        onClick={() => go(index + 1)}
      >
        <ChevronRight />
      </Button>
    </div>
  )
}
