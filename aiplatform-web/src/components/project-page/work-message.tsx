"use client";

import { useState } from "react";
import { Check, ChevronDown, Clock, FileCode2, Hammer, ShieldCheck, ShieldQuestion, SquareTerminal, X } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { cn } from "@/lib/utils";
import { useAnswerPermission } from "@/hooks/use-answer-permission";
import type { WorkPart, WorkSlice, WorkSnapshot } from "@/lib/store/work-message";

/** 播报工具 → 图标（正本封闭表：write_file / edit_file / command；表外兜底锤子）。 */
const TOOL_ICONS: Record<string, React.ReactNode> = {
  write_file: <FileCode2 className="size-3.5" />,
  edit_file: <FileCode2 className="size-3.5" />,
  command: <SquareTerminal className="size-3.5" />,
};
const FALLBACK_TOOL_ICON = <Hammer className="size-3.5" />;

/**
 * 工作消息呈现段（#116 动作组折叠）：非动作部件（解说/确认/自检）独段；连续动作
 * 聚合为一段（≥2 才有折叠语义，单动作回落 single 段——不聚合）。
 */
export type WorkSegment =
  | { kind: "single"; part: WorkPart }
  | { kind: "actions"; actions: Extract<WorkPart, { kind: "action" }>[] };

/**
 * 部件序列 → 呈现段投影（#116）：连续动作部件聚合为「动作组」——纯呈现聚合，事件
 * 模型不动（store 部件流水不变）；非动作部件各自成段、把动作组切开（「解说短段 ↔
 * 动作组」交替竖流）。读类工具不播报的封闭表口径在服务端/桥（part-action 事件已
 * 过滤只读工具），本投影不涉。
 */
export function segmentWorkParts(parts: WorkPart[]): WorkSegment[] {
  const segments: WorkSegment[] = [];
  let actions: Extract<WorkPart, { kind: "action" }>[] = [];
  const flush = () => {
    if (actions.length === 0) return;
    segments.push(actions.length === 1 ? { kind: "single", part: actions[0] } : { kind: "actions", actions });
    actions = [];
  };
  for (const part of parts) {
    if (part.kind === "action") {
      actions.push(part);
    } else {
      flush();
      segments.push({ kind: "single", part });
    }
  }
  flush();
  return segments;
}

/**
 * 生长中的工作消息（#81 事件模型迁移，形态同 #68 原型已验证形）：对话区内一条
 * 随部件逐段生长的消息——解说文本部件（智能体用户语言解说）+ 动作组折叠行（#116
 * 连续动作默认折叠成一行、展开见单条动作状态）+ 权限确认卡（#83：需批准的工具
 * 操作，批准/拒绝即续跑——与问答卡分形态）+ 自检播报行（#85：收口判据核验「正在
 * 检查系统 → ✅/❌」）。思考与代码不播、无进度条/百分比；run 开始即出现（空部件
 * 也出「正在做」头部），成功收口原地定格留驻（#117：部件保留、只读，收尾卡随后
 * 入流——「过程上文、结果下卡」），失败定格（run-failed）流水留驻。步骤分组与过程
 * 耗时已退役（#115：无「第 N 步」分组头、无动作耗时与头部总时长——部件按序竖排，
 * 「解说短段 ↔ 动作组」交替竖流）。
 */
export function WorkMessage({ work, projectId }: { work: WorkSnapshot; projectId: string }) {
  const growing = !work.frozen;

  // 定格空壳不占位（run 零部件的退化态）：成功收口/run-failed 后部件均留驻
  // （#117），仅当本 run 从无部件时才整卡退场
  if (work.frozen && work.parts.length === 0) return null;

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
          {workHeading(work.slice)}
        </div>
      ) : null}
      {segmentWorkParts(work.parts).map((segment) =>
        segment.kind === "single" ? (
          <WorkPartRow
            key={segment.part.id}
            part={segment.part}
            frozen={work.frozen}
            projectId={projectId}
            runId={work.runId}
          />
        ) : (
          <ActionGroup key={segment.actions[0].id} actions={segment.actions} frozen={work.frozen} />
        ),
      )}
      {growing ? (
        <div className="mt-2 flex items-center gap-2 text-[13px] text-muted-foreground">
          <TypingDots /> 正在干活…
        </div>
      ) : null}
    </div>
  );
}

/** 部件呈现：解说 = 正文段；权限确认 = 确认卡；自检 = 一句话播报行；动作 = 单行状态卡。 */
function WorkPartRow({
  part,
  frozen,
  projectId,
  runId,
}: {
  part: WorkPart;
  frozen: boolean;
  projectId: string;
  runId: string;
}) {
  if (part.kind === "text") {
    return <p className="py-1 text-sm leading-relaxed">{part.text}</p>;
  }
  if (part.kind === "permission") {
    return <PermissionRow part={part} frozen={frozen} projectId={projectId} runId={runId} />;
  }
  if (part.kind === "check") {
    return <CheckRow part={part} frozen={frozen} />;
  }
  return <ActionRow part={part} frozen={frozen} />;
}

/**
 * 自检播报行（#85，spec「正在检查系统 → ✅/❌」）：收口判据核验的一句话呈现——
 * 核验中转圈，落定原位换 ✅（检查通过）/❌（检查未过）；终值即探活结果（收尾卡
 * 统计行随 #88 消费）。定格截断的「检查中」（run 未进核验即终态的防御面）不再
 * 转圈、如实留「检查中」字样。
 */
function CheckRow({
  part,
  frozen,
}: {
  part: Extract<WorkPart, { kind: "check" }>;
  frozen: boolean;
}) {
  return (
    <div className="flex items-center gap-2 rounded-md px-1 py-1.5 text-sm">
      <span className="shrink-0 text-muted-foreground">
        <ShieldCheck className="size-3.5" />
      </span>
      {part.state === "passed" ? (
        <>
          <span className="min-w-0 flex-1 text-muted-foreground">检查通过</span>
          <Check className="size-3.5 shrink-0 text-green-600" strokeWidth={3} />
        </>
      ) : part.state === "failed" ? (
        <>
          <span className="min-w-0 flex-1 text-muted-foreground">检查未过</span>
          <X className="size-3.5 shrink-0 text-destructive" strokeWidth={3} />
        </>
      ) : (
        <>
          <span className={cn("min-w-0 flex-1", frozen && "text-muted-foreground")}>
            正在检查系统
          </span>
          {!frozen ? <Spinner className="size-3 shrink-0 text-muted-foreground" /> : null}
        </>
      )}
    </div>
  );
}

/**
 * 权限确认卡（#83，长在工作消息流内——与对话区问答卡分形态）：待批准操作摘要
 * （命令文本，等宽）+ 拒绝/批准两个动作，作答即续跑（乐观转终态，permission-
 * resolved 事件幂等双达；失败回滚重开）。已批/已拒转徽标定格；run 收口截断的
 * 待答卡如实呈现「未作答」（按钮退场——过期卡作答会被服务端 409 指路刷新）；
 * 超时（#112）转「已超时」定格并播报「等待批准超时，本轮已停止」（不可作答，
 * 按钮退场）。
 */
function PermissionRow({
  part,
  frozen,
  projectId,
  runId,
}: {
  part: Extract<WorkPart, { kind: "permission" }>;
  frozen: boolean;
  projectId: string;
  runId: string;
}) {
  const answerPermission = useAnswerPermission(projectId);
  const pending = part.state === "pending";
  const interactive = pending && !frozen;
  return (
    <div
      className={cn(
        "my-1.5 rounded-lg border px-3 py-2.5",
        interactive ? "border-amber-500/50 bg-amber-500/[0.06]" : "border-foreground/10",
      )}
    >
      <div className="flex items-center gap-2 text-[13px] font-medium">
        <ShieldQuestion className="size-4 shrink-0 text-amber-600" />
        需要您的确认
        {part.state === "approved" ? (
          <span className="ml-auto flex items-center gap-1 text-xs text-muted-foreground">
            <Check className="size-3.5 text-green-600" strokeWidth={3} /> 已批准
          </span>
        ) : part.state === "denied" ? (
          <span className="ml-auto flex items-center gap-1 text-xs text-destructive">
            <X className="size-3.5" strokeWidth={3} /> 已拒绝
          </span>
        ) : part.state === "timedout" ? (
          <span className="ml-auto flex items-center gap-1 text-xs text-muted-foreground">
            <Clock className="size-3.5" /> 已超时
          </span>
        ) : frozen ? (
          <span className="ml-auto text-xs text-muted-foreground">未作答</span>
        ) : null}
      </div>
      <p className="mt-1.5 break-all rounded bg-muted px-2 py-1.5 font-mono text-xs leading-relaxed">
        {part.summary}
      </p>
      {part.state === "timedout" ? (
        <p className="mt-1.5 text-xs text-muted-foreground">等待批准超时，本轮已停止</p>
      ) : null}
      {interactive ? (
        <div className="mt-2 flex justify-end gap-2">
          <Button
            size="sm"
            variant="outline"
            disabled={answerPermission.isPending}
            onClick={() =>
              answerPermission.mutate({ ref: part.engineRef, command: { runId, approved: false } })
            }
          >
            拒绝
          </Button>
          <Button
            size="sm"
            disabled={answerPermission.isPending}
            onClick={() =>
              answerPermission.mutate({ ref: part.engineRef, command: { runId, approved: true } })
            }
          >
            批准
          </Button>
        </div>
      ) : null}
    </div>
  );
}

/**
 * 单行动作状态卡：图标 + 对象短语 + 状态（进行中转圈 / 完成打勾 / 失败「没做成」）。
 * 过程耗时已退役（#115：不显示动作时长）。定格后未终态的动作（run 收口截断的
 * 少数）不再转圈——如实留「进行中」字样不带终态标。
 */
function ActionRow({
  part,
  frozen,
}: {
  part: Extract<WorkPart, { kind: "action" }>;
  frozen: boolean;
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
        </span>
      ) : part.state === "failed" ? (
        <span className="flex shrink-0 items-center gap-1.5 text-xs text-destructive">
          <X className="size-3.5" strokeWidth={3} /> 没做成
        </span>
      ) : frozen ? (
        <span className="shrink-0 text-xs text-muted-foreground">进行中</span>
      ) : (
        <span className="flex shrink-0 items-center gap-1.5 text-xs text-muted-foreground">
          <Spinner className="size-3" /> 进行中
        </span>
      )}
    </div>
  );
}

/** 折叠组头部堆叠图标至多露几个（余下靠「N 个动作」计数表达——防长组图标溢出）。 */
const COLLAPSED_ICON_CAP = 3;

/**
 * 动作组折叠行（#116）：连续动作默认折叠为一行（堆叠图标 + 「N 个动作」），点击
 * 展开见单条动作（图标区分写文件/命令，状态原样保留）。只承接 ≥2 的连续动作组
 * （单动作在投影里回落 single 段走 ActionRow）——组键取首动作 id（首见事件 id
 * 稳定、状态更新不改键），生长中同组追加动作时折叠态不丢。折叠即控噪：动作流水
 * 收起为一行，用户想看明细再展开。
 */
function ActionGroup({
  actions,
  frozen,
}: {
  actions: Extract<WorkPart, { kind: "action" }>[];
  frozen: boolean;
}) {
  const [open, setOpen] = useState(false);
  return (
    <div>
      <button
        type="button"
        onClick={() => setOpen((value) => !value)}
        aria-expanded={open}
        className="flex w-full items-center gap-2 rounded-md px-1 py-1.5 text-sm transition-colors hover:bg-muted/50"
      >
        <span className="flex shrink-0 -space-x-1.5">
          {actions.slice(0, COLLAPSED_ICON_CAP).map((action) => (
            <span
              key={action.id}
              className="flex size-5 shrink-0 items-center justify-center rounded-full border border-background bg-muted text-muted-foreground"
            >
              {TOOL_ICONS[action.toolName] ?? FALLBACK_TOOL_ICON}
            </span>
          ))}
        </span>
        <span className="min-w-0 flex-1 text-left text-muted-foreground">{actions.length} 个动作</span>
        <ChevronDown
          className={cn("size-3.5 shrink-0 text-muted-foreground transition-transform", open && "rotate-180")}
        />
      </button>
      {open ? (
        <div className="pl-1">
          {actions.map((action) => (
            <ActionRow key={action.id} part={action} frozen={frozen} />
          ))}
        </div>
      ) : null}
    </div>
  );
}

/** 工作消息头部文案（#118）：切片标题 + 生成轨道进度；无切片信息回落「正在做」。 */
function workHeading(slice?: WorkSlice): string {
  if (!slice?.title) return "正在做";
  if (slice.index != null && slice.total != null) {
    return `${slice.title}（${slice.index}/${slice.total}）`;
  }
  return slice.title;
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

/** 收尾卡「用时」（用户语言，整秒）：「5 秒」「1 分 03 秒」。 */
export function formatDuration(ms: number): string {
  const sec = Math.max(0, Math.round(ms / 1000));
  if (sec < 60) return `${sec} 秒`;
  return `${Math.floor(sec / 60)} 分 ${String(sec % 60).padStart(2, "0")} 秒`;
}
