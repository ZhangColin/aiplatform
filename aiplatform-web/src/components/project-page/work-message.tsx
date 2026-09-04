"use client";

import { Check, FileCode2, Hammer, SquareTerminal, X } from "lucide-react";
import { useEffect, useState } from "react";

import { Separator } from "@/components/ui/separator";
import { Spinner } from "@/components/ui/spinner";
import { cn } from "@/lib/utils";
import type { WorkPart, WorkSnapshot } from "@/lib/store/work-message";

/** 播报工具 → 图标（正本封闭表：write_file / edit_file / command；表外兜底锤子）。 */
const TOOL_ICONS: Record<string, React.ReactNode> = {
  write_file: <FileCode2 className="size-3.5" />,
  edit_file: <FileCode2 className="size-3.5" />,
  command: <SquareTerminal className="size-3.5" />,
};
const FALLBACK_TOOL_ICON = <Hammer className="size-3.5" />;

/**
 * 生长中的工作消息（#81 事件模型迁移，形态同 #68 原型已验证形）：对话区内一条
 * 随部件逐段生长的消息——解说文本部件（智能体用户语言解说）+ 单行动作状态卡
 * （图标 + 对象 + 进行中/完成/失败 + 时长）+ 步骤分组头（「第 N 步」）。思考与
 * 代码不播、无进度条/百分比；run 开始即出现（空部件也出「正在做」头部），
 * 收口定格（头部与打字点退场、时长停跳，部件留驻凝聚物收尾卡前的定格态）。
 * 计时 tick 归组件局部（UI 关注，非流状态——同 run-elapsed 先例）。
 */
export function WorkMessage({ work }: { work: WorkSnapshot }) {
  const growing = !work.frozen;
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!growing) return;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [growing, work.runId]);

  // 定格且无部件（run 起跑即死）：空壳不占位
  if (work.frozen && work.parts.length === 0) return null;
  // 未终态动作的时长冻结锚：定格时刻（定格后不再随 tick 走）
  const tickStop = work.frozen ? (work.frozenAt ?? now) : now;

  return (
    // 无角色标签（界面只有一个「它」）；生长中带轻浮层感，定格回落为普通卡片
    <div
      className={cn(
        "w-full rounded-xl border p-3",
        growing && "border-foreground/15 shadow-[0_0_0_3px_var(--color-muted)]",
      )}
    >
      {growing ? (
        <div className="mb-1 flex items-center gap-2 text-[13px] font-medium">
          <WorkingDot />
          正在做
          <span className="ml-auto font-mono text-xs tabular-nums text-muted-foreground">
            {formatElapsed(now - work.startedAt)}
          </span>
        </div>
      ) : null}
      {work.parts.map((part) => (
        <WorkPartRow key={part.id} part={part} frozen={work.frozen} tickStop={tickStop} />
      ))}
      {growing ? (
        <div className="mt-2 flex items-center gap-2 text-[13px] text-muted-foreground">
          <TypingDots /> 正在干活…
        </div>
      ) : null}
    </div>
  );
}

/** 部件呈现：解说 = 正文段；步骤 = 分组头；动作 = 单行状态卡。 */
function WorkPartRow({
  part,
  frozen,
  tickStop,
}: {
  part: WorkPart;
  frozen: boolean;
  tickStop: number;
}) {
  if (part.kind === "text") {
    return <p className="py-1 text-sm leading-relaxed">{part.text}</p>;
  }
  if (part.kind === "step") {
    return (
      <div className="mb-1 mt-3 flex items-center gap-2 text-[13px] font-medium text-muted-foreground">
        第 {part.step} 步
        <Separator className="flex-1" />
      </div>
    );
  }
  return <ActionRow part={part} frozen={frozen} tickStop={tickStop} />;
}

/**
 * 单行动作状态卡：图标 + 对象短语 + 状态（进行中转圈 / 完成打勾带时长 / 失败
 * 「没做成」带时长——试了多久如实可读）。定格后未终态的动作（run 收口截断的
 * 少数）不再转圈——时长停在定格时刻、不带终态标。
 */
function ActionRow({
  part,
  frozen,
  tickStop,
}: {
  part: Extract<WorkPart, { kind: "action" }>;
  frozen: boolean;
  tickStop: number;
}) {
  const terminal = part.state === "completed" || part.state === "failed";
  return (
    <div className="flex items-center gap-2 rounded-md px-1 py-1.5 text-sm">
      <span className="shrink-0 text-muted-foreground">
        {TOOL_ICONS[part.toolName] ?? FALLBACK_TOOL_ICON}
      </span>
      <span className={cn("min-w-0 flex-1", terminal && "text-muted-foreground")}>
        {part.label}
      </span>
      {part.state === "completed" ? (
        <span className="flex shrink-0 items-center gap-1.5 text-xs text-muted-foreground">
          <Check className="size-3.5 text-green-600" strokeWidth={3} />
          {formatDuration((part.endedAt ?? tickStop) - part.startedAt)}
        </span>
      ) : part.state === "failed" ? (
        <span className="flex shrink-0 items-center gap-1.5 text-xs text-destructive">
          <X className="size-3.5" strokeWidth={3} /> 没做成{" "}
          {formatDuration((part.endedAt ?? tickStop) - part.startedAt)}
        </span>
      ) : frozen ? (
        <span className="shrink-0 text-xs text-muted-foreground">
          {formatDuration(tickStop - part.startedAt)}
        </span>
      ) : (
        <span className="flex shrink-0 items-center gap-1.5 text-xs text-muted-foreground">
          <Spinner className="size-3" /> 进行中
        </span>
      )}
    </div>
  );
}

/** 进行中脉冲点（形态同 #68 原型工作消息头部）。 */
function WorkingDot() {
  return (
    <span className="relative flex size-2 shrink-0">
      <span className="absolute inline-flex size-full animate-ping rounded-full bg-foreground/50" />
      <span className="relative inline-flex size-2 rounded-full bg-foreground/70" />
    </span>
  );
}

function TypingDots() {
  return (
    <span className="inline-flex gap-1">
      {[0, 1, 2].map((i) => (
        <span
          key={i}
          className="size-1.5 animate-pulse rounded-full bg-muted-foreground/60"
          style={{ animationDelay: `${i * 0.2}s` }}
        />
      ))}
    </span>
  );
}

/** 动作时长（用户语言，整秒）：「5 秒」「1 分 03 秒」。 */
export function formatDuration(ms: number): string {
  const sec = Math.max(0, Math.round(ms / 1000));
  if (sec < 60) return `${sec} 秒`;
  return `${Math.floor(sec / 60)} 分 ${String(sec % 60).padStart(2, "0")} 秒`;
}

/** 头部总时长（等宽对齐）：m:ss。 */
export function formatElapsed(ms: number): string {
  const sec = Math.max(0, Math.floor(ms / 1000));
  return `${Math.floor(sec / 60)}:${String(sec % 60).padStart(2, "0")}`;
}
