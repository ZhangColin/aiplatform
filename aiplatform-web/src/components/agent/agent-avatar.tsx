import { Bot } from "lucide-react";

import { cn } from "@/lib/utils";

/**
 * agent 侧头像（Replit 式消息呈现，Q4）：顾问 / 智能体消息左对齐时的小圆头像。
 * 通用层共用——消息流 agent 段落（message-feed）与需求端内联问答（wait-card advisor）
 * 都取此头像，避免两处各画一个不一致的圆。
 */
export function AgentAvatar({ className }: { className?: string }) {
  return (
    <div
      className={cn(
        "flex size-6 shrink-0 items-center justify-center rounded-full bg-muted",
        className,
      )}
    >
      <Bot className="size-3.5 text-muted-foreground" />
    </div>
  );
}
