"use client";

import { useState, type ReactNode } from "react";
import {
  BookMarked,
  Brain,
  Check,
  ChevronRight,
  CircleAlert,
  FilePen,
  FileSearch,
  Flag,
  Terminal,
} from "lucide-react";

import { KnowledgeHitCard } from "@/components/agent/knowledge-hit";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Spinner } from "@/components/ui/spinner";
import {
  segmentPatch,
  segmentPatchDiff,
  segmentStep,
  segmentText,
  segmentTool,
} from "@/lib/agent/segments";
import { useSseStatus } from "@/lib/sse/provider";
import {
  latestProjectRun,
  useAgentStreamsStore,
  type AgentRun,
  type AgentStreamSegment,
} from "@/lib/store/agent-streams";
import type { SseStatus } from "@/lib/store/sse-status";
import { cn } from "@/lib/utils";

/**
 * 主面板「直播」tab（spec 0001 §4.2，issue #41）：无气泡的舞台时间线，事件为大块——
 * text 流式段落 / reasoning 折叠 / patch diff 行级块 / tool 卡 / step 边界 / role 段 /
 * knowledge 横幅（双卡网格）/ error / finish；未知透传类型（passthrough）兜底呈现。
 *
 * 纯读改动——分段已在 streams store 全量写入（桥是唯一写入方），本组件只读；
 * tab 切换不丢状态。知识命中卡词汇口径 = CONTEXT.md「知识命中（Knowledge Hit）」，
 * 来源项目名必须显示（跨项目命中是特性）。
 */

type KnowledgeSegment = Extract<AgentStreamSegment, { kind: "knowledge" }>;
type RoleSegment = Extract<AgentStreamSegment, { kind: "role" }>;
type TextSegment = Extract<AgentStreamSegment, { kind: "text" }>;
type ReasoningSegment = Extract<AgentStreamSegment, { kind: "reasoning" }>;
type ToolSegment = Extract<AgentStreamSegment, { kind: "tool" }>;
type PatchSegment = Extract<AgentStreamSegment, { kind: "patch" }>;
type StepSegment = Extract<AgentStreamSegment, { kind: "step" }>;
type WaitSegment = Extract<AgentStreamSegment, { kind: "wait" }>;
type WaitSettledSegment = Extract<AgentStreamSegment, { kind: "wait-settled" }>;
type ErrorSegment = Extract<AgentStreamSegment, { kind: "error" }>;
type FinishSegment = Extract<AgentStreamSegment, { kind: "finish" }>;
type PassthroughSegment = Extract<AgentStreamSegment, { kind: "passthrough" }>;

export function LivePanel({ projectId }: { projectId: string }) {
  const status = useSseStatus("agent");
  const run = useAgentStreamsStore((s) => latestProjectRun(s, projectId));

  return (
    <ScrollArea className="h-full min-h-0">
      <div className="mx-auto max-w-3xl space-y-4 p-6">
        <header className="flex items-start justify-between gap-2 border-b pb-3">
          <div className="space-y-1">
            <h2 className="text-lg font-semibold">直播</h2>
            <p className="text-xs text-muted-foreground">
              智能体干活的实时画面：文本 / 工具 / 补丁 / 知识命中按时间线展开
            </p>
          </div>
          <ConnectionIndicator status={status} />
        </header>

        {run ? (
          <StageTimeline run={run} />
        ) : (
          <p className="py-8 text-center text-sm text-muted-foreground">
            智能体开始干活后，执行过程会实时出现在这里
          </p>
        )}
      </div>
    </ScrollArea>
  );
}

/** 舞台时间线：任务开头（task-start prompt）+ 全部分段按到达序展开（「回看与围观」语义）。 */
export function StageTimeline({ run }: { run: AgentRun }) {
  return (
    <div className="space-y-4">
      {run.prompt && <TaskStartBlock prompt={run.prompt} />}
      {run.segments.map((segment) => (
        <SegmentBlock key={segment.id} segment={segment} />
      ))}
    </div>
  );
}

/** 分段 → 独立渲染块（spec 0001 §4.2 大块；每种分段一个块，未知透传走兜底）。 */
function SegmentBlock({ segment }: { segment: AgentStreamSegment }) {
  switch (segment.kind) {
    case "role":
      return <RoleBlock segment={segment} />;
    case "knowledge":
      return <KnowledgeHitsBlock segment={segment} />;
    case "text":
      return <TextBlock segment={segment} />;
    case "reasoning":
      return <ReasoningBlock segment={segment} />;
    case "tool":
      return <ToolBlock segment={segment} />;
    case "patch":
      return <PatchBlock segment={segment} />;
    case "step":
      return <StepBoundary segment={segment} />;
    case "wait":
      return <WaitBlock segment={segment} />;
    case "wait-settled":
      return <WaitSettledBlock segment={segment} />;
    case "error":
      return <ErrorBlock segment={segment} />;
    case "finish":
      return <FinishBlock segment={segment} />;
    case "passthrough":
      return <PassthroughBlock segment={segment} />;
    default:
      return null;
  }
}

/** 连接状态小指示（ADR 0003 呈现最小化：仅工作台 agent 流区给指示，通知通道静默）。 */
function ConnectionIndicator({ status }: { status: SseStatus }) {
  const meta = {
    connected: { dot: "bg-emerald-500", text: "已连接" },
    connecting: { dot: "animate-pulse bg-amber-500", text: "连接中" },
    offline: { dot: "animate-pulse bg-red-500", text: "已断开 · 重连中" },
  }[status];
  return (
    <span className="flex shrink-0 items-center gap-1.5 pt-1 text-xs text-muted-foreground">
      <span className={cn("size-1.5 rounded-full", meta.dot)} />
      {meta.text}
    </span>
  );
}

// ── 分段渲染块 ─────────────────────────────────────────────────────────────

/** 任务开头（task-start prompt）：时间线首块，声明本次运行要干的事。 */
export function TaskStartBlock({ prompt }: { prompt: string }) {
  return (
    <div className="flex items-center gap-3">
      <span className="shrink-0 rounded-md bg-primary/15 px-2 py-1 text-xs font-medium text-primary">
        任务开始
      </span>
      <span className="text-sm text-muted-foreground">{prompt}</span>
    </div>
  );
}

/** 角色段（role-assigned）：角色卡分配一行，非气泡大块。 */
export function RoleBlock({ segment }: { segment: RoleSegment }) {
  return (
    <div className="rounded-md border bg-muted/30 px-3 py-2 text-xs text-muted-foreground">
      已分配角色 ·{" "}
      <span className="font-medium text-foreground">{segment.roleLabel}</span>
      {segment.stage && <span className="opacity-70">（{segment.stage}）</span>}
      {segment.engine && <span className="ml-1 opacity-70">· {segment.engine}</span>}
    </div>
  );
}

/** text 流式段落：无气泡字幕式，agent 的话直接成段。 */
export function TextBlock({ segment }: { segment: TextSegment }) {
  const text = segmentText(segment.data);
  if (!text) return null;
  return <p className="whitespace-pre-wrap text-base leading-relaxed">{text}</p>;
}

/** reasoning 折叠：思考过程收进可展开块（默认收起）。 */
export function ReasoningBlock({ segment }: { segment: ReasoningSegment }) {
  const text = segmentText(segment.data);
  if (!text) return null;
  return <ReasoningCollapse text={text} />;
}

function ReasoningCollapse({ text }: { text: string }) {
  const [open, setOpen] = useState(false);
  return (
    <Collapsible open={open} onOpenChange={setOpen}>
      <CollapsibleTrigger className="flex items-center gap-1.5 rounded-md px-1 py-0.5 text-xs text-muted-foreground hover:text-foreground">
        <Brain className="size-3.5" />
        思考过程
        <ChevronRight className={cn("size-3 transition-transform", open && "rotate-90")} />
      </CollapsibleTrigger>
      <CollapsibleContent>
        <p className="mt-1 whitespace-pre-wrap border-l-2 pl-3 text-xs leading-relaxed text-muted-foreground">
          {text}
        </p>
      </CollapsibleContent>
    </Collapsible>
  );
}

/** tool 卡（spec 0001 §5 元素）：icon + 名称 + 入参截断 + spinner→✓。 */
export function ToolBlock({ segment }: { segment: ToolSegment }) {
  const { name, arg, status } = segmentTool(segment.data);
  const Icon = name === "bash" ? Terminal : name === "edit" ? FilePen : FileSearch;
  return (
    <div
      className={cn(
        "flex max-w-lg items-center gap-3 rounded-lg border px-3.5 py-2.5",
        status === "running" ? "border-primary/50 bg-primary/5" : "border-border bg-muted/40",
      )}
    >
      <Icon className="size-4 shrink-0 text-muted-foreground" />
      <div className="min-w-0">
        <p className="text-sm font-medium">{name || "工具"}</p>
        {arg && <p className="truncate font-mono text-xs text-muted-foreground">{arg}</p>}
      </div>
      {status === "running" ? (
        <span className="ml-auto flex shrink-0 items-center gap-1.5 text-xs text-primary">
          <Spinner className="size-3.5" /> 执行中
        </span>
      ) : (
        <Check className="ml-auto size-4 shrink-0 text-muted-foreground" />
      )}
    </div>
  );
}

/** patch diff 行级块：头部（path + 增删计数）+ 行级 +/- 染色 + 摘要行。 */
export function PatchBlock({ segment }: { segment: PatchSegment }) {
  const { path, added, removed, summary } = segmentPatch(segment.data);
  const lines = segmentPatchDiff(segment.data);
  return (
    <div className="overflow-hidden rounded-lg border">
      <div className="flex items-center gap-2 border-b bg-muted/50 px-3.5 py-2 text-xs">
        <FilePen className="size-3.5 shrink-0 text-muted-foreground" />
        <span className="min-w-0 truncate font-medium">{path || "补丁"}</span>
        <span className="ml-auto shrink-0 text-emerald-600 dark:text-emerald-400">+{added}</span>
        <span className="shrink-0 text-red-600 dark:text-red-400">−{removed}</span>
      </div>
      {lines.length > 0 && (
        <div className="overflow-x-auto bg-muted/20 py-1 font-mono text-xs leading-6">
          {lines.map((line, i) => (
            <div
              key={i}
              className={cn(
                "px-3.5",
                line.kind === "add" && "bg-emerald-500/10 text-emerald-700 dark:text-emerald-400",
                line.kind === "remove" && "bg-red-500/10 text-red-700 dark:text-red-400",
              )}
            >
              <span className="mr-2 inline-block w-2 select-none opacity-60">
                {line.kind === "add" ? "+" : line.kind === "remove" ? "−" : " "}
              </span>
              {line.text}
            </div>
          ))}
        </div>
      )}
      {summary && (
        <p className="border-t bg-muted/30 px-3.5 py-1.5 text-xs text-muted-foreground">{summary}</p>
      )}
    </div>
  );
}

/** step 边界：开始/完成两条横线分隔，承载步骤名。 */
export function StepBoundary({ segment }: { segment: StepSegment }) {
  const name = segmentStep(segment.data);
  const starting = segment.phase === "start";
  return (
    <div className="flex items-center gap-3 py-1">
      <div className="h-px flex-1 bg-border" />
      <span
        className={cn(
          "flex items-center gap-1.5 text-xs font-medium",
          starting ? "text-muted-foreground" : "text-emerald-600 dark:text-emerald-400",
        )}
      >
        <Flag className="size-3.5" />
        {starting ? "开始" : "完成"} · {name || "步骤"}
      </span>
      <div className="h-px flex-1 bg-border" />
    </div>
  );
}

/** 知识命中横幅（spec 0001 §5 元素：来源 + 所属项目 + chunk 摘要两行；双卡网格）。 */
export function KnowledgeHitsBlock({ segment }: { segment: KnowledgeSegment }) {
  return (
    <section className="rounded-lg border border-indigo-500/30 bg-indigo-500/5 px-4 py-3">
      <p className="flex items-center gap-2 text-sm font-medium text-indigo-700 dark:text-indigo-300">
        <BookMarked className="size-4" />
        沉淀助手 · 知识命中 {segment.items.length} 条历史沉淀
      </p>
      <ul className="mt-2 grid gap-2 sm:grid-cols-2">
        {segment.items.map((item, i) => (
          <KnowledgeHitCard key={i} item={item} />
        ))}
      </ul>
    </section>
  );
}

type StatusTone = "muted" | "amber" | "destructive" | "emerald";

const STATUS_TONES: Record<StatusTone, string> = {
  muted: "border-border bg-muted/40 text-muted-foreground",
  amber: "border-amber-500/40 bg-amber-500/10 text-amber-700 dark:text-amber-400",
  destructive: "border-destructive/40 bg-destructive/10 text-destructive",
  emerald: "border-emerald-500/40 bg-emerald-500/10 text-emerald-700 dark:text-emerald-400",
};

/** 状态块骨架（wait / wait-settled / error / finish 共用）：icon + 文案，tone 定配色。 */
function StatusBanner({
  icon,
  tone,
  children,
}: {
  icon: ReactNode;
  tone: StatusTone;
  children: ReactNode;
}) {
  return (
    <div className={cn("flex items-start gap-2 rounded-md border px-3 py-2 text-sm", STATUS_TONES[tone])}>
      <span className="mt-0.5 shrink-0 [&>svg]:size-4">{icon}</span>
      <span>{children}</span>
    </div>
  );
}

/** 等待点（wait-raised）：事项待处理的醒目块。 */
export function WaitBlock({ segment }: { segment: WaitSegment }) {
  return (
    <StatusBanner icon={<CircleAlert />} tone="amber">
      等待你的处理 · {segment.summary}
    </StatusBanner>
  );
}

/** 等待点关闭（wait-settled）：回到自动运行的轻量块。 */
export function WaitSettledBlock({ segment }: { segment: WaitSettledSegment }) {
  return (
    <StatusBanner icon={<Check />} tone="muted">
      等待点已处理 · {segment.outcome}
    </StatusBanner>
  );
}

/** 错误（error）：运行失败的醒目块。 */
export function ErrorBlock({ segment }: { segment: ErrorSegment }) {
  return (
    <StatusBanner icon={<CircleAlert />} tone="destructive">
      {segment.message || "运行出错"}
    </StatusBanner>
  );
}

/** 完成（task-finish）：本次运行结束块。 */
export function FinishBlock({ segment }: { segment: FinishSegment }) {
  return (
    <StatusBanner icon={<Check />} tone="emerald">
      任务完成{segment.finish ? ` · ${segment.finish}` : ""}
    </StatusBanner>
  );
}

/** 未知透传类型（passthrough）兜底：type + 载荷预览，保证不丢事件也不崩。 */
export function PassthroughBlock({ segment }: { segment: PassthroughSegment }) {
  return (
    <div className="rounded-md border border-dashed px-3 py-2 text-xs text-muted-foreground">
      <p className="font-medium">引擎事件 · {segment.type}</p>
      <pre className="mt-1 line-clamp-4 overflow-hidden whitespace-pre-wrap font-mono">
        {previewData(segment.data)}
      </pre>
    </div>
  );
}

/** 透传载荷 → 单行文本（字符串直取，对象 JSON 序列化，异常兜底）。 */
function previewData(data: unknown): string {
  if (data == null) return "";
  if (typeof data === "string") return data;
  try {
    return JSON.stringify(data);
  } catch {
    return String(data);
  }
}
