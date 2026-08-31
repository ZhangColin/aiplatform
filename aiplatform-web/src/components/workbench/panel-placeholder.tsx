"use client";

import type { ReactNode } from "react";

import { cn } from "@/lib/utils";

/**
 * 工作台槽位空态占位（通用层，issue #17 两槽位壳）：指令区 / 成果区的内容占位
 * 共用同一形状——图标 / 标题可选，正文描述随面板。业务内容由后续切片（指令区
 * 对话 / 成果区文件·系统·项目）填，届时各面板替换本组件。
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
