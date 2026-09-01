"use client";

import { PackageCheck } from "lucide-react";

import { Button } from "@/components/ui/button";

/**
 * 确认下单（#26 迭代环①）：指令区输入条上方常驻按钮，随首次生成完成出现、
 * 零迭代可点（满意就下单，流程不被迭代拉长）。可见性规则归纯函数
 * confirmOrderVisible（装配层判定后决定是否注入本组件）；下单动作本体归
 * 交易环①（#28）接出——本组件只管呈现与点击回调。
 */
export function ConfirmOrderButton({ onConfirm }: { onConfirm: () => void }) {
  return (
    <div className="mb-2 flex justify-center">
      <Button size="sm" className="rounded-full text-xs" onClick={onConfirm}>
        <PackageCheck className="size-3.5" />
        确认下单
      </Button>
    </div>
  );
}
