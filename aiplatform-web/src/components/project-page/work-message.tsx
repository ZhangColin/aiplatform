"use client";

import { useEffect, useState } from "react";
import { FileCode2, Hammer, SquareTerminal, TriangleAlert, X } from "lucide-react";

import { Spinner } from "@/components/ui/spinner";
import { cn } from "@/lib/utils";
import type { WorkPart, WorkSlice, WorkSnapshot } from "@/lib/store/work-message";

/** 播报工具 → 图标（正本封闭表：write_file / edit_file / execute；表外兜底锤子）。 */
const TOOL_ICONS: Record<string, React.ReactNode> = {
  write_file: <FileCode2 className="size-3.5" />,
  edit_file: <FileCode2 className="size-3.5" />,
  execute: <SquareTerminal className="size-3.5" />,
};
const FALLBACK_TOOL_ICON = <Hammer className="size-3.5" />;

/**
 * 成功无痕投影（#230）：completed 动作不产生静态条目——滤除只在呈现层（store 部件
 * 流水仍收全量，「store 不裁事件」口径不变）；failed 动作留红行。直播中的
 * started/running 动作不进正文（#235 常驻活性行：直播行是唯一实时状态行，归卡片
 * 底部活性行——动作起灭不推挤正文，稳定判据＝不闪不跳）。脱轨信号部件不进正文
 * （#240 静态面无痕——活性行是唯一呈现位）。读类工具不播报的封闭表口径在服务端/
 * 桥（part-action 事件已过滤只读工具），本投影不涉。呈现面清场（#608）：解说文本
 * 部件退役，正文只余失败红行。
 */
export function presentWorkParts(parts: WorkPart[]): WorkPart[] {
  return parts.filter((part) => part.kind === "action" && part.state === "failed");
}

/**
 * 活性行变体族（#235 唯一实时状态行；输入＝store 原始部件，非正文投影）：
 * 末位动作失败＝红字变体（失败破例优先——停滚提示压过仍在跑的并发动作）；否则
 * 最近发起的 started/running 动作＝命令原值 label 滚动（story15 并发取最近发起的
 * 一条）；末位脱轨信号（其后无动作接管）＝脱轨变体（#240 机器语法吞段的人话留痕
 * ——吞段后恢复的真实动作优先，动作完成即信号消化）；动作间隙＝无字打字点。
 */
export type WorkActivity =
  | { kind: "failed" }
  | { kind: "derailed" }
  | { kind: "action"; part: Extract<WorkPart, { kind: "action" }> }
  | { kind: "idle" };

export function activityOf(parts: WorkPart[]): WorkActivity {
  const actions = parts.filter(
    (part): part is Extract<WorkPart, { kind: "action" }> => part.kind === "action",
  );
  if (actions.at(-1)?.state === "failed") return { kind: "failed" };
  const live = actions.findLast(
    (part) => part.state === "started" || part.state === "running",
  );
  if (live) return { kind: "action", part: live };
  // 末位信号晚于末位动作＝吞段后无动作接管（无动作时任何信号都算）：脱轨变体
  const lastActionAt = parts.findLastIndex((part) => part.kind === "action");
  const lastSignalAt = parts.findLastIndex((part) => part.kind === "signal");
  return lastSignalAt > lastActionAt ? { kind: "derailed" } : { kind: "idle" };
}

/**
 * 生长中的工作消息——直播进度卡（#225 呈现改版；形态同 #68 原型已验证
 * 形的演进；稳定判据＝不闪、不跳、不无意义震荡、高度随内容长〔#235 D3〕）：
 * 自上而下——①头部（#118 工作消息标题，缺省回落「正在做」）＋ run 级「已运行
 * mm:ss」跳动时钟（ADR-0010 窄修订：锚 run-start 信封 ts，收口定格）＋②正文
 * 失败痕（#230 成功无痕：成功动作不产生静态条目——静态面＝失败红行）＋③常驻
 * 活性行（#235：直播行＝唯一实时状态行，全程常驻不消失——动作在跑＝命令原值
 * label 滚动、间隙＝无字打字点、失败＝红字变体、脱轨＝琥珀色变体〔#240 机器语法
 * 吞段的人话留痕，静态面无痕〕；收口保留末行直到收尾卡入流，随后随定格沉没）。
 * 呈现面清场（#608，ADR-0057）：计划区两级、步骤清单、解说直播、「正在创建首页」
 * 渐进占位、轮次计数一并退役——过程流只余工具动作流（阶段三按新形态重建）。
 * 无标题回落「正在做」；思考与代码不播、无逐步耗时/百分比；run 开始即出现，
 * 成功收口原地定格留驻（#117：部件保留、只读，收尾卡随后入流——「过程上文、结果
 * 下卡」），失败定格（run-failed）流水留驻。定格时进行中动作不立即沉没（#235
 * 保留末行衔接），收尾卡入流后活性行退场——终形无成功残骸（story16「结束瞬间无
 * 界面跳变」承 #117 口径：卡片不清空不消失，失败痕即终形）。
 */
export function WorkMessage({
  work,
  closingArrived = false,
}: {
  work: WorkSnapshot;
  /**
   * 本 run 收尾卡已入流（#235 活性行沉没锚：同 runId closing 消息已在对话流——
   * 「过程上文、结果下卡」接力完成，活性行随定格沉没；未入流 = 定格后保留末行，
   * run-failed 无收尾卡即留驻终形）。缺省 false（装配层未供给时保守保留）。
   */
  closingArrived?: boolean;
}) {
  const growing = !work.frozen;

  // 定格空壳不占位（run 零部件的退化态）：成功收口/run-failed 后部件均留驻
  // （#117），仅当本 run 从无部件时才整卡退场
  if (work.frozen && work.parts.length === 0) return null;

  const failed = presentWorkParts(work.parts);
  const activity = activityOf(work.parts);
  // 活性行常驻（#235）：生长中恒在；定格后保留末行直到收尾卡入流（衔接窗无跳变）
  const showActivity = growing || !closingArrived;

  return (
    // 无角色标签（界面只有一个「它」）；生长中带轻浮层感，定格回落为普通卡片
    <div
      className={cn(
        "w-full rounded-xl border p-3",
        growing && "border-foreground/15 shadow-[0_0_0_3px_var(--color-muted)]",
      )}
    >
      <div className="flex items-center gap-2 text-[13px] font-medium">
        {growing ? <WorkingDot /> : null}
        {/* 定格保标题与冻结时钟（story12 结束瞬间无跳变）；无标题的定格
            不回落「正在做」（静止的卡不该自称正在做） */}
        {growing || work.slice?.title ? (
          <span className="min-w-0 truncate">{workHeading(work.slice)}</span>
        ) : null}
        <ElapsedClock startedAt={work.startedAt} endedAt={work.endedAt} active={growing} />
      </div>
      {failed.map((part) => (
        <WorkPartRow key={part.id} part={part} />
      ))}
      {showActivity ? <ActivityLine activity={activity} live={growing} /> : null}
    </div>
  );
}

/**
 * 常驻活性行（#235 唯一实时状态行）：变体族同槽换装——动作在跑＝命令原值 label
 * 滚动（#228 剥壳／定宽截断语义在服务端，本行逐字渲染＋单行 truncate 兜底）；
 * 间隙＝无字打字点（「正在干活…」字样行已退役）；失败＝红字变体（#225 story10
 * 「刚才的动作没做成，正在处理」语义沿用）；脱轨＝琥珀色变体（#240 机器语法吞段
 * 的人话留痕——一句定型文案，不携带原文、不出命令滚动行、不转圈：脱轨＝什么都没
 * 跑，不伪造「在执行」）。各变体同高（py-1.5 + 20px 行高预算），换装不跳行。
 * 定格保留末行时静态呈现（live=false：不转圈、不跳动——定格卡不自称在跑）。
 */
function ActivityLine({
  activity,
  live,
}: {
  activity: WorkActivity;
  live: boolean;
}) {
  if (activity.kind === "failed") {
    // 失败破例（#225 story10）：滚动行停滚并转红色——不再误以为正常推进
    return (
      <div className="mt-1 flex items-center gap-2 px-1 py-1.5 text-sm text-destructive">
        <X className="size-3.5 shrink-0" /> 刚才的动作没做成，正在处理
      </div>
    );
  }
  if (activity.kind === "derailed") {
    // 脱轨变体（#240）：琥珀色与失败红字区分（抖了一下 ≠ 动作失败）——静态面
    // 无痕，本行是唯一呈现位；定格保留末行时静态呈现（无动画，同失败变体口径）
    return (
      <div className="mt-1 flex items-center gap-2 px-1 py-1.5 text-sm text-amber-600">
        <TriangleAlert className="size-3.5 shrink-0" /> 刚才模型发射异常，正在调整
      </div>
    );
  }
  if (activity.kind === "action") {
    const { part } = activity;
    return (
      <div className="mt-1 flex items-center gap-2 px-1 py-1.5 text-sm">
        <span className="shrink-0 text-muted-foreground">
          {TOOL_ICONS[part.toolName] ?? FALLBACK_TOOL_ICON}
        </span>
        <span className={cn("min-w-0 flex-1 truncate", !live && "text-muted-foreground")}>
          {part.label}
        </span>
        {live ? (
          <span className="flex shrink-0 items-center gap-1.5 text-xs text-muted-foreground">
            <Spinner className="size-3" /> 进行中
          </span>
        ) : null}
      </div>
    );
  }
  // 间隙＝无字打字点（h-5 对齐三态行高，换装不跳）
  return (
    <div className="mt-1 flex items-center px-1 py-1.5">
      <span className="flex h-5 items-center">
        <TypingDots live={live} />
      </span>
    </div>
  );
}

/** 部件呈现：动作 = 失败红行（成功无痕 #230；进行中动作归活性行 #235，不进正文）。 */
function WorkPartRow({ part }: { part: WorkPart }) {
  if (part.kind === "signal") {
    // 防御位（#240 静态面无痕）：正文投影已滤除信号——漏进即不渲染，活性行是唯一呈现位
    return null;
  }
  return <ActionRow part={part} />;
}

/**
 * 失败红行（正文静态面唯一的动作条目，#230 成功无痕 + #229 失败留痕）：label＋
 * 「没做成」＋错误副行（排障不进容器即可初判原因；高度有界对失败破例让位〔#225
 * 破例语义：事故不被埋掉〕）。进行中动作不进正文——直播归常驻活性行（#235）。
 * 逐步耗时已退役（#115/ADR-0010：不显示动作时长）。
 */
function ActionRow({ part }: { part: Extract<WorkPart, { kind: "action" }> }) {
  return (
    <div>
      <div className="flex items-center gap-2 rounded-md px-1 py-1.5 text-sm">
        <span className="shrink-0 text-muted-foreground">
          {TOOL_ICONS[part.toolName] ?? FALLBACK_TOOL_ICON}
        </span>
        {/* 单行截断（#228）：长命令优雅截断不换行撑高——卡片宽度恒定（#225 story8）； */}
        <span className="min-w-0 flex-1 truncate text-muted-foreground">{part.label}</span>
        <span className="flex shrink-0 items-center gap-1.5 text-xs text-destructive">
          <X className="size-3.5" strokeWidth={3} /> 没做成
        </span>
      </div>
      {part.error ? (
        // 错误副行：与 label 同行宽截断（服务端已首行截断，此处行内样式兜底），
        // 左缩进与 label 起点对齐（px-1 + 图标 14px + gap-2）
        <div className="px-1 pb-1.5 pl-[22px] text-xs leading-relaxed text-destructive/90">
          <span className="block truncate">{part.error}</span>
        </div>
      ) : null}
    </div>
  );
}

/**
 * run 级已运行时钟（#225，ADR-0010 窄修订）：以 run-start 事件信封 ts 为锚
 * （startedAt）、前端每秒渲染；run-finish / run-failed 定格（endedAt；缺收口 ts
 * 的异常信封停在末拍——不越收口继续跳）。缺锚（run-start 被淘汰的补建路径）
 * 不渲染——活性信号锚定真实事件，不伪造起点。
 */
function ElapsedClock({
  startedAt,
  endedAt,
  active,
}: {
  startedAt?: number;
  endedAt?: number;
  active: boolean;
}) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!active || startedAt == null) return;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [active, startedAt]);
  if (startedAt == null) return null;
  return (
    <span className="ml-auto shrink-0 font-mono text-xs tabular-nums text-muted-foreground">
      已运行 {formatClock((endedAt ?? now) - startedAt)}
    </span>
  );
}

/** 时钟格式（mm:ss，负值防御归零）。 */
export function formatClock(ms: number): string {
  const sec = Math.max(0, Math.floor(ms / 1000));
  return `${String(Math.floor(sec / 60)).padStart(2, "0")}:${String(sec % 60).padStart(2, "0")}`;
}

/** 工作消息头部文案（#118）：用户语言标题；无标题上下文回落「正在做」。 */
function workHeading(slice?: WorkSlice): string {
  return slice?.title || "正在做";
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

/** 打字点活动指示（#235 无字间隙态；live=false＝定格保留末行的静态呈现——定格卡不跳动）。 */
function TypingDots({ live }: { live: boolean }) {
  return (
    <span className="inline-flex gap-1">
      {[0, 1, 2].map((i) => (
        <span
          key={i}
          className={cn("size-1.5 rounded-full bg-muted-foreground/60", live && "animate-pulse")}
          style={live ? { animationDelay: `${i * 0.2}s` } : undefined}
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
