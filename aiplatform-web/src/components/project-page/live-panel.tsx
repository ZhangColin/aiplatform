"use client";

import { useEffect, useRef, useState } from "react";
import { Hammer, PanelLeftClose, PanelLeftOpen, RefreshCw } from "lucide-react";

import { Button } from "@/components/ui/button";
import { coderStatusOf, retryMessageOf, useGenerationStore } from "@/lib/store/generation";
import { liveSegmentsOf, useLiveStore, type LiveSegment } from "@/lib/store/live";

/** 重试话术的本地回落（帧丢失防御位；正本随 task-retrying 帧下发）。 */
const FALLBACK_RETRY_MESSAGE = "遇到问题，正在重试";

/**
 * 直播侧栏（#23 生成环②，CONTEXT.md「直播」）：成果区右侧可收展栏，面向客户的
 * 解说广播——编码 run 进行中呈现（lg+ 右侧栏 / 窄屏顶部条），段 = live-* 帧投影
 * （智能体自述 + 动作摘要行 + 步骤分隔）；思考与代码不播（服务端口径）。
 *
 * <p><b>随 run 生命周期</b>：run 起跑（含刷新回页重建）自动展开、收口/终态自动
 * 收起，收起即逝、无历史回看（!inFlight 即不渲染）；用户手动收展保留至下一自动
 * 事件（渲染期派生态调整，同 outputsTab 自动切模式先例）。重试中播帧内话术。
 * 段自动滚到最新。</p>
 */
export function LiveRail({ projectId }: { projectId: string }) {
  const status = useGenerationStore((s) => coderStatusOf(s, projectId));
  const retryMessage = useGenerationStore((s) => retryMessageOf(s, projectId));
  const segments = useLiveStore((s) => liveSegmentsOf(s, projectId));

  const inFlight = status === "running" || status === "retrying";
  const [expanded, setExpanded] = useState(true);
  // run 起跑自动展开 / 结束自动收起（用户手动收展保留至下一自动事件）
  const [seenInFlight, setSeenInFlight] = useState(inFlight);
  if (inFlight !== seenInFlight) {
    setSeenInFlight(inFlight);
    setExpanded(inFlight);
  }
  if (!inFlight) return null; // 收起即逝：run 结束不再回看

  // 两断点共用面板体（lg 右侧栏 / 窄屏顶部条）
  const body = (
    <LiveRailBody
      segments={segments}
      retrying={status === "retrying"}
      retryMessage={retryMessage}
      onToggle={() => setExpanded(false)}
      toggleIcon={<PanelLeftClose className="size-4" />}
      toggleLabel="收起直播"
    />
  );

  return (
    <>
      {/* lg+：右侧栏（展开面板 / 收起窄条） */}
      {expanded ? (
        <aside className="hidden min-h-0 w-72 shrink-0 flex-col border-l lg:flex">{body}</aside>
      ) : (
        <button
          type="button"
          onClick={() => setExpanded(true)}
          aria-label="展开直播"
          className="hidden w-10 shrink-0 flex-col items-center gap-3 border-l py-3 text-muted-foreground transition-colors hover:text-foreground lg:flex"
        >
          <LiveDot />
          <span className="text-xs [writing-mode:vertical-rl]">直播</span>
          <PanelLeftOpen className="size-4" />
        </button>
      )}

      {/* <lg：顶部条（收起细条 / 展开面板） */}
      {expanded ? (
        <section className="flex h-56 min-h-0 shrink-0 flex-col border-b lg:hidden">{body}</section>
      ) : (
        <button
          type="button"
          onClick={() => setExpanded(true)}
          className="flex h-10 shrink-0 items-center gap-2 border-b px-3 text-muted-foreground lg:hidden"
        >
          <LiveDot />
          <span className="text-xs font-medium">直播</span>
          <span className="min-w-0 flex-1 truncate text-xs">
            {latestLine(segments) ?? "智能体开始工作后，这里会逐段说明在做什么"}
          </span>
          <PanelLeftOpen className="size-4" />
        </button>
      )}
    </>
  );
}

/** 直播面板体（lg 右侧栏与窄屏顶部面板共用）：头 + 段落流 + 重试行。 */
function LiveRailBody({
  segments,
  retrying,
  retryMessage,
  onToggle,
  toggleIcon,
  toggleLabel,
}: {
  segments: LiveSegment[];
  retrying: boolean;
  retryMessage?: string;
  onToggle: () => void;
  toggleIcon: React.ReactNode;
  toggleLabel: string;
}) {
  const scroller = useRef<HTMLDivElement>(null);
  // 段有进即滚到最新（直播语义：看的永远是最新的解说）
  useEffect(() => {
    const el = scroller.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [segments.length]);

  return (
    <>
      <header className="flex h-10 shrink-0 items-center gap-2 border-b px-3">
        <LiveDot />
        <span className="text-xs font-semibold">直播</span>
        <Button
          variant="ghost"
          size="icon"
          className="ml-auto size-7"
          onClick={onToggle}
          aria-label={toggleLabel}
        >
          {toggleIcon}
        </Button>
      </header>

      {retrying && (
        <p className="flex items-center gap-1.5 border-b bg-amber-500/10 px-3 py-2 text-xs text-amber-700 dark:text-amber-400">
          <RefreshCw className="size-3.5 shrink-0 animate-spin" />
          {retryMessage ?? FALLBACK_RETRY_MESSAGE}
        </p>
      )}

      <div ref={scroller} className="min-h-0 flex-1 space-y-2.5 overflow-y-auto p-3">
        {segments.length === 0 ? (
          <p className="text-xs leading-relaxed text-muted-foreground">
            智能体开始工作后，这里会逐段说明在做什么
          </p>
        ) : (
          segments.map((segment) => <LiveLine key={segment.id} segment={segment} />)
        )}
      </div>
    </>
  );
}

/** 直播段呈现：自述 = 气泡；动作 = 工具行；步骤 = 分隔小字。 */
function LiveLine({ segment }: { segment: LiveSegment }) {
  if (segment.kind === "step") {
    return (
      <div className="flex items-center gap-2 py-0.5 text-[10px] text-muted-foreground/70">
        <span className="h-px flex-1 bg-border" />
        第 {segment.step} 步
        <span className="h-px flex-1 bg-border" />
      </div>
    );
  }
  if (segment.kind === "action") {
    return (
      <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
        <Hammer className="size-3 shrink-0" />
        {segment.action}
      </p>
    );
  }
  return <p className="rounded-lg bg-muted px-2.5 py-1.5 text-xs leading-relaxed">{segment.text}</p>;
}

/** LIVE 脉冲点（进行中标识，顶栏 ProjectPageRunStatus 同款形态）。 */
function LiveDot() {
  return (
    <span className="relative flex size-2 shrink-0">
      <span className="absolute inline-flex size-full animate-ping rounded-full bg-red-500 opacity-60" />
      <span className="relative inline-flex size-2 rounded-full bg-red-500" />
    </span>
  );
}

/** 最新一行文案（窄屏收起条预览）：自述/动作取文，步骤取「第 N 步」。 */
function latestLine(segments: LiveSegment[]): string | undefined {
  const last = segments[segments.length - 1];
  if (!last) return undefined;
  if (last.kind === "step") return `第 ${last.step} 步`;
  return last.kind === "text" ? last.text : last.action;
}
