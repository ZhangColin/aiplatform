"use client";

import { useEffect, useRef, useState } from "react";
import { Check, ChevronDown, FileCode2, Hammer, ShieldCheck, SquareTerminal, X } from "lucide-react";

import { Spinner } from "@/components/ui/spinner";
import { cn } from "@/lib/utils";
import type { GenerationSegmentFact } from "@/lib/projects/detail";
import type { WorkPart, WorkPlanStep, WorkSlice, WorkSnapshot } from "@/lib/store/work-message";

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
 * 底部活性行——动作起灭不推挤正文，稳定判据＝不闪不跳）。读类工具不播报的封闭表
 * 口径在服务端/桥（part-action 事件已过滤只读工具），本投影不涉。
 */
export function presentWorkParts(parts: WorkPart[]): WorkPart[] {
  return parts.filter((part) => part.kind !== "action" || part.state === "failed");
}

/**
 * 活性行三态（#235 唯一实时状态行；输入＝store 原始部件，非正文投影）：
 * 末位动作失败＝红字变体（失败破例优先——停滚提示压过仍在跑的并发动作）；否则
 * 最近发起的 started/running 动作＝命令原值 label 滚动（story15 并发取最近发起的
 * 一条）；动作间隙（无在跑动作）＝无字打字点。
 */
export type WorkActivity =
  | { kind: "failed" }
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
  return live ? { kind: "action", part: live } : { kind: "idle" };
}

/** 失败痕判定（#225 失败破例的部件级口径：failed 红行不埋进坍缩、常驻展开红显）。 */
function isFailedPart(part: WorkPart): boolean {
  return part.kind === "action" && part.state === "failed";
}

/** 混合坍缩投影（#225 正文三区；#230 动作组退役后以部件为段）：更早区（坍缩）+ 失败破例面 + 尾部活动区。 */
export type WorkBody = {
  /** 更早区全量（原序）：未展开时解说段藏进「⋯ 更早 N 项」、失败红行提为破例面；展开回看时全量竖流（事件不裁剪）。 */
  earlier: WorkPart[];
  /** 坍缩行计数（更早区解说/自检段数——失败红行不进坍缩行）。 */
  collapsedCount: number;
  /** 尾部活动区：最近失败红行（锚）+ ≤2 句前展解说 + 其后自检恒留、解说 ≤2 句；直播动作不在此（归活性行）。 */
  tail: WorkPart[];
};

/** 尾部解说配额（「最近一两句解说」两侧同限：动作前引入 ≤2 句、动作后收尾 ≤2 句）。 */
const TAIL_TEXT_QUOTA = 2;

/**
 * 呈现部件 → 混合坍缩分区（#225 纯呈现聚合；#230 输入改为成功无痕投影后的部件；
 * #235 起直播动作不进正文——锚点即最近失败红行）：尾部 = 最后一个失败红行（无则
 * 纯解说取末两段）+ 其后至多 2 句解说（自检行恒留）+ 其前 ≤2 个连续解说段（「最近
 * 一两句解说」常驻可见、卡片高度有界——收口前的多句交接叙事折进更早区）；其余进
 * 更早区坍缩（完整回看时全量原序展开，事件不裁剪）。更早区中的失败红行由渲染层
 * 提为破例面（常驻展开红显）。
 */
export function splitWorkBody(parts: WorkPart[]): WorkBody {
  if (parts.length === 0) {
    return { earlier: [], collapsedCount: 0, tail: [] };
  }
  const anchor = parts.findLastIndex((part) => part.kind === "action");
  const tail: WorkPart[] = [];
  if (anchor < 0) {
    // 纯解说：尾部取最后两段
    tail.push(...parts.slice(Math.max(0, parts.length - TAIL_TEXT_QUOTA)));
  } else {
    // 前展 ≤2 个紧邻解说段（最近一两句解说随尾部常驻——解说引入当前动作的语境）
    for (let i = anchor - 1; tail.length < TAIL_TEXT_QUOTA && i >= 0; i--) {
      if (parts[i].kind === "text") {
        tail.unshift(parts[i]);
      } else {
        break;
      }
    }
    tail.push(parts[anchor]);
    // 其后自检恒留、解说至多最近 2 句（超配额的居中句折进更早区）
    let textsKept = 0;
    const trailing: WorkPart[] = [];
    for (let i = parts.length - 1; i > anchor; i--) {
      const part = parts[i];
      if (part.kind === "text" && textsKept >= TAIL_TEXT_QUOTA) continue;
      if (part.kind === "text") textsKept++;
      trailing.unshift(part);
    }
    tail.push(...trailing);
  }
  const inTail = new Set(tail);
  const earlier = parts.filter((part) => !inTail.has(part));
  return {
    earlier,
    collapsedCount: earlier.filter((part) => !isFailedPart(part)).length,
    tail,
  };
}

/**
 * 计划区当前片序（#225）：run-start 切片序号（1-based = 片序）直取——高亮跟真实
 * run 事件走、不查表猜在途；无序号的 titled run 只认阶段 0（标题与 ord 0 片行描述
 * 同源同串），更新 run（「系统更新」）不匹配 → 无当前片（卡片不渲染计划区）。
 */
export function planCurrentOrd(
  slice: WorkSlice | undefined,
  plan: GenerationSegmentFact[] | null | undefined,
): number | undefined {
  if (!slice || !plan || plan.length === 0) return undefined;
  if (slice.index != null) return slice.index;
  return plan[0]?.description === slice.title ? 0 : undefined;
}

/**
 * 生长中的工作消息——直播进度卡（#225 呈现改版；形态同 #68 原型已验证
 * 形的演进；稳定判据＝不闪、不跳、不无意义震荡、高度随内容长〔#235 D3，
 * 「恒定高度」说法退役〕）：自上而下——①计划区（生成轨道片清单常驻：✓ 已收口 / ● 当前〔
 * run-start 切片序驱动〕/ ○ 待跑 / ✗ 失败，REST 只读透出；＋run 级步骤清单
 * 〔#236 part-plan 快照：agent 自产计划，✓●○ 不设 ✗——更新轨 run 卡的主承载、
 * 生成轨与切片级两级同卡共存（#237：切片级在上、步骤级在下——更新轨无切片
 * 上下文即只显步骤级）〕）＋②头部活性（#118
 * 切片标题 + run 级「已运行 mm:ss」跳动时钟〔ADR-0010 窄修订：锚 run-start 信封
 * ts，收口定格〕）＋③正文混合坍缩（#230 成功无痕：动作组折叠退役、成功动作不产
 * 生静态条目——静态面＝解说段＋失败红行；尾部活动区常驻最近一两句解说，更早内
 * 容坍缩为「⋯ 更早 N 项」点击回看；失败破例：失败红行不埋进坍缩、常驻展开红显）
 * ＋④常驻活性行（#235：直播行＝唯一实时状态行，全程常驻不消失——动作在跑＝
 * 命令原值 label 滚动、间隙＝无字打字点、失败＝红字变体；收口保留末行直到收尾卡
 * 入流，随后随定格沉没。稳定判据＝不闪、不跳、不无意义震荡）。零散维护需求（无
 * 切片上下文）不渲染计划区、不伪造计划；agent 不产步骤清单则无清单区域（解说
 * 兜底，#236）；无切片标题回落「正在做」。思考与代码不
 * 播、无逐步耗时/百分比；run 开始即出现，成功收口原地定格留驻（#117：部件保留、
 * 只读，收尾卡随后入流——「过程上文、结果下卡」；步骤清单随定格留驻最后快照，
 * 刷新不回显——不落库口径），失败定格（run-failed）流水
 * 留驻。定格时进行中动作不立即沉没（#235 保留末行衔接），收尾卡入流后活性行退场——
 * 终形无成功残骸（story16「结束瞬间无界面跳变」承 #117 口径：卡片不清空不消失，
 * 叙事＋失败痕即终形）。
 */
export function WorkMessage({
  work,
  plan,
  closingArrived = false,
}: {
  work: WorkSnapshot;
  /** 生成轨道片清单（#225 计划区，REST 详情透出；缺省 = 无现行计划）。 */
  plan?: GenerationSegmentFact[] | null;
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

  const currentOrd = planCurrentOrd(work.slice, plan);
  const showPlan = !!plan && plan.length > 0 && currentOrd != null;
  const body = splitWorkBody(presentWorkParts(work.parts));
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
      {showPlan ? <PlanArea plan={plan} currentOrd={growing ? currentOrd : undefined} /> : null}
      {work.plan && work.plan.length > 0 ? (
        <StepsArea steps={work.plan} growing={growing} />
      ) : null}
      <div className="flex items-center gap-2 text-[13px] font-medium">
        {growing ? <WorkingDot /> : null}
        {/* 定格保标题与冻结时钟（story12 结束瞬间无跳变）；无切片上下文的定格
            不回落「正在做」（静止的卡不该自称正在做） */}
        {growing || work.slice?.title ? (
          <span className="min-w-0 truncate">{workHeading(work.slice)}</span>
        ) : null}
        <ElapsedClock startedAt={work.startedAt} endedAt={work.endedAt} active={growing} />
      </div>
      <WorkBodyRows body={body} frozen={work.frozen} />
      {showActivity ? <ActivityLine activity={activity} live={growing} /> : null}
    </div>
  );
}

/**
 * 常驻活性行（#235 唯一实时状态行）：三态同槽换装——动作在跑＝命令原值 label
 * 滚动（#228 剥壳／定宽截断语义在服务端，本行逐字渲染＋单行 truncate 兜底）；
 * 间隙＝无字打字点（「正在干活…」字样行已退役）；失败＝红字变体（#225 story10
 * 「刚才的动作没做成，正在处理」语义沿用）。三态同高（py-1.5 + 20px 行高预算），
 * 换装不跳行。定格保留末行时静态呈现（live=false：不转圈、不跳动——定格卡不
 * 自称在跑）。
 */
function ActivityLine({ activity, live }: { activity: WorkActivity; live: boolean }) {
  if (activity.kind === "failed") {
    // 失败破例（#225 story10）：滚动行停滚并转红色——不再误以为正常推进
    return (
      <div className="mt-1 flex items-center gap-2 px-1 py-1.5 text-sm text-destructive">
        <X className="size-3.5 shrink-0" /> 刚才的动作没做成，正在处理
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

/** 正文三区渲染（#225 混合坍缩 + #230 成功无痕）：更早行（可展开）→ 失败破例面 → 尾部活动区。 */
function WorkBodyRows({ body, frozen }: { body: WorkBody; frozen: boolean }) {
  const [earlierOpen, setEarlierOpen] = useState(false);
  const surfaced = body.earlier.filter(isFailedPart);
  return (
    <>
      {earlierOpen ? (
        // 展开回看：更早区全量原序竖流（story4/5/6——组成＝解说段＋失败痕，事件不裁剪）
        body.earlier.map((part) => <WorkPartRow key={part.id} part={part} frozen={frozen} />)
      ) : body.collapsedCount > 0 ? (
        <button
          type="button"
          onClick={() => setEarlierOpen(true)}
          className="flex w-full items-center gap-2 rounded-md px-1 py-1.5 text-[13px] text-muted-foreground transition-colors hover:bg-muted/50"
        >
          <span className="shrink-0 tracking-widest">⋯</span>
          更早 {body.collapsedCount} 项
          <ChevronDown className="size-3.5 shrink-0" />
        </button>
      ) : null}
      {/* 失败破例面（#225 story9）：未展开时也不埋进坍缩行——常驻展开红显 */}
      {!earlierOpen
        ? surfaced.map((part) => <WorkPartRow key={part.id} part={part} frozen={frozen} />)
        : null}
      {body.tail.map((part) => (
        <WorkPartRow key={part.id} part={part} frozen={frozen} />
      ))}
    </>
  );
}

/** 部件呈现：解说 = 正文段；自检 = 一句话播报行；动作 = 失败红行（成功无痕 #230；进行中动作归活性行 #235，不进正文）。 */
function WorkPartRow({ part, frozen }: { part: WorkPart; frozen: boolean }) {
  if (part.kind === "text") {
    return <p className="py-1 text-sm leading-relaxed">{part.text}</p>;
  }
  if (part.kind === "check") {
    return <CheckRow part={part} frozen={frozen} />;
  }
  return <ActionRow part={part} />;
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

/** 计划区行高预算（清单超出行数内滚，当前片滚入视口——计划区不撑高卡片）。 */
const PLAN_MAX_ROWS = 3;
/** 计划区单行像素高（行距 26px，+2 为容器边框）。 */
const PLAN_ROW_PX = 26;

/**
 * 计划区（#225）：整份切片计划清单常驻——✓ 已收口 / ● 当前（高亮）/ ○ 待跑 /
 * ✗ 失败（片状态 =「最近一次尝试的结局」，REST 轨道表只读透出）；● 当前片由
 * run-start 切片序号驱动（growing 才标——收口后状态归 REST）。清单长于视口时
 * 内部滚动（卡片高度恒定），当前片自动滚入。
 */
function PlanArea({
  plan,
  currentOrd,
}: {
  plan: GenerationSegmentFact[];
  currentOrd?: number;
}) {
  const currentRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    // 当前片滚入视口（当前片常在清单尾部，不滚会藏）
    currentRef.current?.scrollIntoView?.({ block: "nearest" });
  }, [currentOrd]);
  return (
    <div
      className="mb-1.5 divide-y divide-border/60 rounded-lg border border-border/60 bg-muted/30"
      style={{ maxHeight: `${PLAN_MAX_ROWS * PLAN_ROW_PX + 2}px`, overflowY: "auto" }}
    >
      {plan.map((segment) => {
        const current = currentOrd != null && segment.ord === currentOrd;
        return (
          <div
            key={segment.ord}
            ref={current ? currentRef : undefined}
            className={cn(
              "flex items-center gap-2 px-2 py-1 text-[13px]",
              current ? "bg-primary/10 font-medium text-foreground" : "text-muted-foreground",
            )}
          >
            <SegmentMark status={segment.status} current={current} />
            <span className="min-w-0 flex-1 truncate">{segment.description}</span>
            {current ? <span className="shrink-0 text-[11px] text-primary">进行中</span> : null}
          </div>
        );
      })}
    </div>
  );
}

/** 计划区片状态标：✓ 已收口 / ● 当前 / ○ 待跑 / ✗ 失败。 */
function SegmentMark({
  status,
  current,
}: {
  status: GenerationSegmentFact["status"];
  current: boolean;
}) {
  if (current) {
    return <span className="shrink-0 text-primary">●</span>;
  }
  if (status === "closed") {
    return <Check className="size-3.5 shrink-0 text-green-600" strokeWidth={3} />;
  }
  if (status === "failed") {
    return <X className="size-3.5 shrink-0 text-destructive" strokeWidth={3} />;
  }
  return <span className="shrink-0 text-muted-foreground/60">○</span>;
}

/** 步骤清单行数预算（同计划区口径：清单超出内滚，当前步滚入视口——不撑高卡片）。 */
const STEPS_MAX_ROWS = 3;

/**
 * 步骤清单区（#236 run 级步骤清单）：agent 自产计划的全量快照渲染——✓ 完成 /
 * ● 当前 / ○ 待做（不设 ✗——失败留痕归动作部件与收尾卡）；快照式就地整表更新
 * （React key = 稳定 id，未变行不重挂）。定格留驻最后快照，「进行中」徽标随定格
 * 退场（静止的卡不自称在跑，同切片级定格去高亮口径）。
 */
function StepsArea({ steps, growing }: { steps: WorkPlanStep[]; growing: boolean }) {
  const currentRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    // 当前步滚入视口（长清单内滚，当前步常在尾部不滚会藏）
    currentRef.current?.scrollIntoView?.({ block: "nearest" });
  }, [steps]);
  return (
    <div
      className="mb-1.5 divide-y divide-border/60 rounded-lg border border-border/60 bg-muted/30"
      style={{ maxHeight: `${STEPS_MAX_ROWS * PLAN_ROW_PX + 2}px`, overflowY: "auto" }}
    >
      {steps.map((step) => {
        const current = growing && step.state === "in_progress";
        return (
          <div
            key={step.id}
            ref={current ? currentRef : undefined}
            className={cn(
              "flex items-center gap-2 px-2 py-1 text-[13px]",
              current ? "bg-primary/10 font-medium text-foreground" : "text-muted-foreground",
            )}
          >
            <StepMark state={step.state} />
            <span className="min-w-0 flex-1 truncate">{step.title}</span>
            {current ? <span className="shrink-0 text-[11px] text-primary">进行中</span> : null}
          </div>
        );
      })}
    </div>
  );
}

/** 步骤状态标（✓●○ 三态）：状态来自快照自报（忘推进如实滞留，平台不推断补偿）。 */
function StepMark({ state }: { state: WorkPlanStep["state"] }) {
  if (state === "completed") {
    return <Check className="size-3.5 shrink-0 text-green-600" strokeWidth={3} />;
  }
  if (state === "in_progress") {
    return <span className="shrink-0 text-primary">●</span>;
  }
  return <span className="shrink-0 text-muted-foreground/60">○</span>;
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
