"use client";

import type { ReactNode } from "react";

import { cn } from "@/lib/utils";

/**
 * 工作台面板空态占位（通用层，issue #37「只落壳」）：Agent 区三模式与开发平台
 * 右栏的内容占位共用同一形状——图标 / 标题可选，正文描述随面板。业务内容由后续
 * 票（#40 对话 / #41 直播 / #43 门卡 / #44 待处理）填，届时各面板替换本组件。
 */
export function PanelPlaceholder({
  icon,
  title,
  children,
}: {
  icon?: ReactNode;
  title?: ReactNode;
  children: ReactNode;
}) {
  return (
    <div className="flex h-full min-h-0 items-center justify-center p-6">
      <div
        className={cn(
          "flex max-w-[16rem] flex-col items-center gap-2 text-center text-sm text-muted-foreground",
        )}
      >
        {icon ? <span className="[&_svg]:size-6 [&_svg]:opacity-50">{icon}</span> : null}
        {title ? <p className="font-medium text-foreground">{title}</p> : null}
        <p>{children}</p>
      </div>
    </div>
  );
}
