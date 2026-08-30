// PROTOTYPE（throwaway）—— T4 (#6) 开发平台工作台 UX 原型入口
// A / B / C 三个结构迥异的变体；?variant=A|B|C 切换（浮动条或 ←→ 键）。
// 生产构建整体渲染为空（防误上线）。
"use client"

import { Suspense } from "react"
import { useSearchParams } from "next/navigation"

import { PrototypeSwitcher, VARIANTS } from "./prototype-switcher"
import { VariantA } from "./variant-a"
import { VariantB } from "./variant-b"
import { VariantC } from "./variant-c"
import { VariantD } from "./variant-d"

function Inner() {
  const searchParams = useSearchParams()
  const raw = searchParams.get("variant") ?? "A"
  const variant = VARIANTS.some((v) => v.key === raw) ? raw : "A"

  return (
    <>
      {variant === "B" ? (
        <VariantB />
      ) : variant === "C" ? (
        <VariantC />
      ) : variant === "D" ? (
        <VariantD />
      ) : (
        <VariantA />
      )}
      <PrototypeSwitcher current={variant} />
    </>
  )
}

export default function PrototypeWorkbenchPage() {
  if (process.env.NODE_ENV === "production") return null

  return (
    <Suspense fallback={null}>
      <Inner />
    </Suspense>
  )
}
