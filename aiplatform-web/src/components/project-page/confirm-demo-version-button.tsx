"use client";

import { BadgeCheck } from "lucide-react";

import { Button } from "@/components/ui/button";

/**
 * 确认 Demo 版本（#335 门开动作——「确认此版本，准备签约」＝下单入口）：现壳
 * 最小呈现挂项目顶栏、与「确认下单」相邻成漏斗（确认版本 → 下单）；原型壳的
 * 确认卡归 Demo 体验页重建（#347）。仅纯呈现——确认动作本体归
 * useConfirmDemoVersion 接出，可见性判定归装配层。
 */
export function ConfirmDemoVersionButton({
  onConfirm,
  pending = false,
}: {
  onConfirm: () => void;
  pending?: boolean;
}) {
  return (
    <Button
      variant="outline"
      size="sm"
      className="h-7 text-xs"
      disabled={pending}
      onClick={onConfirm}
    >
      <BadgeCheck className="size-3.5" />
      确认此版本，准备签约
    </Button>
  );
}
