"use client";

import { BookMarked, Brain, Check, ChevronRight, CircleAlert, FilePen, FileSearch, Terminal } from "lucide-react";
import { useEffect, useRef, useState, type ReactNode } from "react";

import { AgentAvatar } from "@/components/agent/agent-avatar";
import { KnowledgeHitCard, type KnowledgeItem } from "@/components/agent/knowledge-hit";
import { segmentPatch, segmentText, segmentTool } from "@/lib/agent/segments";
import type { AgentRun, AgentStreamSegment } from "@/lib/store/agent-streams";
import { cn } from "@/lib/utils";

import { Badge } from "@/components/ui/badge";
import { Card, CardContent } from "@/components/ui/card";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
import { Spinner } from "@/components/ui/spinner";

/**
 * 消息流（spec 0001 §4.1，issue #40）：自上而下渲染 streams store 的 run 分段
 * （系统胶囊 / 用户右泡 / agent 段落 / 工具 chip / 思考折叠 / patch 摘要 / 知识
 * 命中卡 / 运行错误卡）。开发平台对话模式与需求端顾问对话共用同一流——差异（下任务 vs 补充
 * 需求输入、门卡文案、问答卡挂法）由场景层经 `footer` / `empty` / `waitSlot`
 * 插槽注入，`variant` 控制平台口径胶囊（dev）还是顾问消息（advisor，去胶囊）；
 * 本组件只读 store 不写业务文案。
 */
export function MessageFeed({
  run,
  footer,
  empty,
  waitSlot,
  variant = "dev",
}: {
  run: AgentRun | undefined;
  /** 嵌流底容器位（HITL 卡 / 门卡并列，spec 0001 §4.1），由场景层注入。 */
  footer?: ReactNode;
  /** 无 run 时的空态引导；缺省 = 开发平台口径。 */
  empty?: ReactNode;
  /** wait 分段插槽（issue #52）：琥珀胶囊下注入场景内容（需求端挂问答卡变体）；间距由插槽内容自理。 */
  waitSlot?: (waitId: string) => ReactNode;
  /** 场景变体：dev = 平台口径胶囊（等待你的处理 / 已分配角色 / 等待点已处理）；
   *  advisor = 需求端顾问对话——去这些 dev 胶囊，问题渲染成顾问消息 + 选项 chip。 */
  variant?: "dev" | "advisor";
}) {
  const bottomRef = useRef<HTMLDivElement>(null);
  const segmentCount = run?.segments.length ?? 0;
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ block: "end" });
  }, [segmentCount, run?.runId]);

  if (!run) {
    return (
      <div className="flex h-full min-h-0 flex-col">
        <div className="flex flex-1 flex-col items-center justify-center gap-2 p-6 text-sm text-muted-foreground">
          {empty ?? (
            <>
              <p>给智能体下一条任务，消息流会在这里展开</p>
              <p className="text-xs text-muted-foreground/70">
                任务被接受后，执行过程实时回流（文本 / 工具 / 补丁 / 知识命中）
              </p>
            </>
          )}
        </div>
        {footer ? <div className="space-y-3 p-4 pt-0">{footer}</div> : null}
      </div>
    );
  }

  return (
    <div className="space-y-3 p-4 pb-6">
      {/* 用户右泡（task-start 的 prompt） */}
      {run.prompt ? (
        <div className="flex justify-end">
          <div className="max-w-[85%] whitespace-pre-wrap rounded-xl bg-primary px-3 py-2 text-sm text-primary-foreground">
            {run.prompt}
          </div>
        </div>
      ) : null}

      {run.segments.map((segment) => (
        <SegmentView key={segment.id} segment={segment} waitSlot={waitSlot} variant={variant} />
      ))}

      {footer}

      <div ref={bottomRef} />
    </div>
  );
}

function SegmentView({
  segment,
  waitSlot,
  variant,
}: {
  segment: AgentStreamSegment;
  waitSlot?: (waitId: string) => ReactNode;
  variant: "dev" | "advisor";
}) {
  switch (segment.kind) {
    case "role":
      // 顾问对话去 dev 口径胶囊（「已分配角色」对用户无意义，顾问头像已表明说话方）
      return variant === "advisor" ? null : (
        <SystemCapsule>
          已分配角色 · <span className="font-medium">{segment.roleLabel}</span>
          <span className="opacity-70">（{segment.stage}）</span>
        </SystemCapsule>
      );
    case "knowledge":
      return <KnowledgeCard items={segment.items} />;
    case "text": {
      const text = segmentText(segment.data);
      return text ? <AgentParagraph text={text} /> : null;
    }
    case "reasoning": {
      const text = segmentText(segment.data);
      return text ? <ReasoningCollapse text={text} /> : null;
    }
    case "tool":
      return <ToolChip {...segmentTool(segment.data)} />;
    case "patch":
      return <PatchSummary {...segmentPatch(segment.data)} />;
    case "wait":
      // 顾问对话：问题 = 顾问消息（摘要即问题文本）+ 选项 chip（waitSlot 注入）；答后
      // 消息留存、chip 随 PENDING 消失。dev 保留琥珀胶囊 + 卡。
      if (variant === "advisor") {
        return (
          <div data-wait-id={segment.waitId} className="space-y-2">
            {segment.summary ? <AgentParagraph text={segment.summary} /> : null}
            {waitSlot ? <div className="pl-[2.125rem]">{waitSlot(segment.waitId)}</div> : null}
          </div>
        );
      }
      return (
        // data-wait-id：需求端顾问对话深链定位锚（issue #49）
        <div data-wait-id={segment.waitId}>
          <SystemCapsule className="border-amber-500/40 bg-amber-500/10 text-amber-700 dark:text-amber-400">
            <CircleAlert className="size-3" /> 等待你的处理 · {segment.summary}
          </SystemCapsule>
          {waitSlot ? waitSlot(segment.waitId) : null}
        </div>
      );
    case "wait-settled":
      // 顾问对话：答复已作为用户右泡写进流，这个 dev 胶囊冗余，去掉
      return variant === "advisor" ? null : (
        <SystemCapsule>等待点已处理 · {segment.outcome}</SystemCapsule>
      );
    case "user":
      return <UserBubble text={segment.text} />;
    case "error":
      return <RunErrorCard message={segment.message} />;
    case "finish":
      return <SystemCapsule>任务完成 · {segment.finish}</SystemCapsule>;
    case "step":
      // 步骤边界是直播模式的语义（§4.2），对话模式不呈现
      return null;
    case "passthrough":
      return <SystemCapsule>引擎事件 · {segment.type}</SystemCapsule>;
    default:
      return null;
  }
}

// ── 基础块 ────────────────────────────────────────────────────────────────

function SystemCapsule({ children, className }: { children: React.ReactNode; className?: string }) {
  return (
    <div className="flex justify-center">
      <Badge
        variant="secondary"
        className={cn("inline-flex max-w-full gap-1 font-normal", className)}
      >
        {children}
      </Badge>
    </div>
  );
}

/** 用户右泡（HITL 答复 / 与 run.prompt 同款）。 */
function UserBubble({ text }: { text: string }) {
  return (
    <div className="flex justify-end">
      <div className="max-w-[85%] whitespace-pre-wrap rounded-xl bg-primary px-3 py-2 text-sm text-primary-foreground">
        {text}
      </div>
    </div>
  );
}

function AgentParagraph({ text }: { text: string }) {
  // 顾问消息去灰底，改「头像 + 正文」的文档式左对齐（Replit 式，Q4）——聊天机器人
  // 的灰泡换成 agent 消息体，读起来更顺。
  return (
    <div className="flex max-w-[92%] items-start gap-2.5">
      <AgentAvatar className="mt-0.5" />
      <div className="min-w-0 whitespace-pre-wrap text-sm leading-relaxed">{text}</div>
    </div>
  );
}

/**
 * 运行错误卡（issue #61）：error 帧的用户可见呈现——起跑即死也补建了 stub run，
 * 这里是用户唯一的失败信号。主文案说人话（后端原文常是英文技术细节），原文以
 * 等宽小字保留供反馈截图；引导语指向「再发一次即重试」（补充需求输入会续跑）。
 */
function RunErrorCard({ message }: { message: string }) {
  return (
    <div
      role="alert"
      className="max-w-[92%] rounded-xl border border-destructive/40 bg-destructive/5 px-3 py-2.5 text-sm"
    >
      <p className="flex items-center gap-1.5 font-medium text-destructive">
        <CircleAlert className="size-4 shrink-0" />
        运行遇到问题，暂时没能继续
      </p>
      {message ? (
        <p className="mt-1.5 break-all font-mono text-xs leading-relaxed text-muted-foreground">
          {message}
        </p>
      ) : null}
      <p className="mt-1.5 text-xs text-muted-foreground">
        稍后把内容再发一次即可重试；反复出现请连同上方报错原文反馈给我们
      </p>
    </div>
  );
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

/** 工具 chip（进行中 spinner / 已执行 ✓，spec 0001 §5）。 */
function ToolChip({ name, arg, status }: { name: string; arg: string; status: "running" | "done" }) {
  const Icon = name === "bash" ? Terminal : name === "edit" ? FilePen : FileSearch;
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1.5 rounded-md border px-2 py-1 font-mono text-xs",
        status === "running"
          ? "border-primary/40 bg-primary/5 text-primary"
          : "border-border bg-muted/50 text-muted-foreground",
      )}
    >
      <Icon className="size-3.5 shrink-0" />
      <span className="font-sans font-medium">{name}</span>
      {arg && <span className="max-w-56 truncate opacity-80">{arg}</span>}
      {status === "running" ? <Spinner className="size-3" /> : <Check className="size-3.5" />}
    </span>
  );
}

function PatchSummary({
  path,
  added,
  removed,
  summary,
}: {
  path: string;
  added: number;
  removed: number;
  summary: string;
}) {
  return (
    <div className="rounded-lg border bg-muted/30 px-3 py-2 font-mono text-xs">
      <div className="flex items-center gap-2">
        <FilePen className="size-3.5 text-muted-foreground" />
        <span className="font-sans">{path || "补丁"}</span>
        <span className="ml-auto font-sans text-emerald-600 dark:text-emerald-400">+{added}</span>
        <span className="font-sans text-red-600 dark:text-red-400">−{removed}</span>
      </div>
      {summary && <p className="mt-1 font-sans text-muted-foreground">{summary}</p>}
    </div>
  );
}

/** 知识命中卡容器（spec 0001 §5：来源 + 所属项目 + chunk 摘要；条目卡走共享 KnowledgeHitCard）。 */
function KnowledgeCard({ items }: { items: KnowledgeItem[] }) {
  return (
    <Card className="border-indigo-500/30 bg-indigo-500/5 py-2">
      <CardContent className="px-3">
        <p className="flex items-center gap-1.5 text-xs font-medium text-indigo-700 dark:text-indigo-300">
          <BookMarked className="size-3.5" />
          平台知识命中（{items.length} 条 · 检索注入）
        </p>
        <ul className="mt-1.5 space-y-1.5">
          {items.map((item, i) => (
            <KnowledgeHitCard key={i} item={item} />
          ))}
        </ul>
      </CardContent>
    </Card>
  );
}
