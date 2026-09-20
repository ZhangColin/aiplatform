"use client";

import { useEffect, useRef, useState } from "react";
import { Check, ChevronDown, FileCode2, Hammer, ShieldCheck, SquareTerminal, X } from "lucide-react";

import { Spinner } from "@/components/ui/spinner";
import { cn } from "@/lib/utils";
import type { GenerationSegmentFact } from "@/lib/projects/detail";
import type { WorkPart, WorkSlice, WorkSnapshot } from "@/lib/store/work-message";

/** 播报工具 → 图标（正本封闭表：write_file / edit_file / execute；表外兜底锤子）。 */
const TOOL_ICONS: Record<string, React.ReactNode> = {
  write_file: <FileCode2 className="size-3.5" />,
  edit_file: <FileCode2 className="size-3.5" />,
  execute: <SquareTerminal className="size-3.5" />,
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

/** 段内是否含失败动作（#225 失败破例的判定：任一动作 failed 即破例）。 */
export function segmentHasFailure(segment: WorkSegment): boolean {
  if (segment.kind === "single") {
    return segment.part.kind === "action" && segment.part.state === "failed";
  }
  return segment.actions.some((action) => action.state === "failed");
}

/** 混合坍缩投影（#225 正文三区）：更早区（坍缩）+ 失败破例面 + 尾部活动区。 */
export type WorkBody = {
  /** 更早区全量（原序）：未展开时非失败段藏进「⋯ 更早 N 项」、失败段提为破例面；展开回看时全量竖流（事件不裁剪）。 */
  earlier: WorkSegment[];
  /** 坍缩行计数（更早区非失败段数——失败段不进坍缩行）。 */
  collapsedCount: number;
  /** 尾部活动区：最近动作组（生长中展开，#225 story8）+ 最近一两句解说 + 收尾自检。 */
  tail: WorkSegment[];
};

/** 尾部解说配额（「最近一两句解说」两侧同限：动作前引入 ≤2 句、动作后收尾 ≤2 句）。 */
const TAIL_TEXT_QUOTA = 2;

/**
 * 呈现段 → 混合坍缩分区（#225 纯呈现聚合，store 部件流水不动）：
 * 尾部 = 最后一个动作承载段 + 其后至多 2 句解说（自检行恒留）+ 其前 ≤2 个连续
 * 解说段（「最近动作组＋当前动作行＋最近一两句解说」常驻可见、卡片高度有界——
 * 收口前的多句交接叙事折进更早区）；其余进更早区坍缩（含超配额的居中解说——
 * 完整回看时全量原序展开，事件不裁剪）。无动作承载段（纯解说）= 尾部取最后
 * 两段。更早区中含失败动作的段由渲染层提为破例面（常驻展开红显）。
 */
export function splitWorkBody(segments: WorkSegment[]): WorkBody {
  if (segments.length === 0) {
    return { earlier: [], collapsedCount: 0, tail: [] };
  }
  let anchor = -1;
  for (let i = segments.length - 1; i >= 0; i--) {
    const segment = segments[i];
    const bearsAction =
      segment.kind === "actions" || (segment.kind === "single" && segment.part.kind === "action");
    if (bearsAction) {
      anchor = i;
      break;
    }
  }
  const tail: WorkSegment[] = [];
  if (anchor < 0) {
    // 纯解说：尾部取最后两段
    tail.push(...segments.slice(Math.max(0, segments.length - TAIL_TEXT_QUOTA)));
  } else {
    // 前展 ≤2 个紧邻解说段（最近一两句解说随尾部常驻——解说引入当前动作的语境）
    for (let i = anchor - 1; tail.length < TAIL_TEXT_QUOTA && i >= 0; i--) {
      const candidate = segments[i];
      if (candidate.kind === "single" && candidate.part.kind === "text") {
        tail.unshift(candidate);
      } else {
        break;
      }
    }
    tail.push(segments[anchor]);
    // 其后自检恒留、解说至多最近 2 句（超配额的居中句折进更早区）
    let textsKept = 0;
    const trailing: WorkSegment[] = [];
    for (let i = segments.length - 1; i > anchor; i--) {
      const segment = segments[i];
      const isText = segment.kind === "single" && segment.part.kind === "text";
      if (isText && textsKept >= TAIL_TEXT_QUOTA) continue;
      if (isText) textsKept++;
      trailing.unshift(segment);
    }
    tail.push(...trailing);
  }
  const inTail = new Set(tail);
  const earlier = segments.filter((segment) => !inTail.has(segment));
  return {
    earlier,
    collapsedCount: earlier.filter((segment) => !segmentHasFailure(segment)).length,
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
 * 生长中的工作消息——恒定高度直播进度卡（#225 呈现改版；形态同 #68 原型已验证
 * 形的演进）：自上而下三段——①计划区（生成轨道片清单常驻：✓ 已收口 / ● 当前〔
 * run-start 切片序驱动〕/ ○ 待跑 / ✗ 失败，REST 只读透出）＋②头部活性（#118
 * 切片标题 + run 级「已运行 mm:ss」跳动时钟〔ADR-0010 窄修订：锚 run-start 信封
 * ts，收口定格〕）＋③正文混合坍缩（尾部活动区常驻、更早内容坍缩为「⋯ 更早 N 项」
 * 点击回看；失败破例：含失败动作的组不埋进坍缩、常驻展开红显）。零散维护需求
 * （无切片上下文）不渲染计划区、不伪造计划；无切片标题回落「正在做」。思考与
 * 代码不播、无逐步耗时/百分比；run 开始即出现，成功收口原地定格留驻（#117：
 * 部件保留、只读，收尾卡随后入流——「过程上文、结果下卡」），失败定格（run-failed）
 * 流水留驻。定格时生长中的尾部组回落折叠——决定五「正常收工维持折叠」优先
 * （story12 的「无界面跳变」承 #117 口径：卡片不清空不消失，坍缩即终形）。
 * 动作组折叠归 #116（≥2 连续动作折叠一行）。
 */
export function WorkMessage({
  work,
  plan,
}: {
  work: WorkSnapshot;
  /** 生成轨道片清单（#225 计划区，REST 详情透出；缺省 = 无现行计划）。 */
  plan?: GenerationSegmentFact[] | null;
}) {
  const growing = !work.frozen;

  // 定格空壳不占位（run 零部件的退化态）：成功收口/run-failed 后部件均留驻
  // （#117），仅当本 run 从无部件时才整卡退场
  if (work.frozen && work.parts.length === 0) return null;

  const currentOrd = planCurrentOrd(work.slice, plan);
  const showPlan = !!plan && plan.length > 0 && currentOrd != null;
  const body = splitWorkBody(segmentWorkParts(work.parts));
  const failed = latestActionOf(work.parts)?.state === "failed";

  return (
    // 无角色标签（界面只有一个「它」）；生长中带轻浮层感，定格回落为普通卡片
    <div
      className={cn(
        "w-full rounded-xl border p-3",
        growing && "border-foreground/15 shadow-[0_0_0_3px_var(--color-muted)]",
      )}
    >
      {showPlan ? <PlanArea plan={plan} currentOrd={growing ? currentOrd : undefined} /> : null}
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
      {growing ? (
        failed ? (
          // 失败破例（#225 story10）：滚动行停滚并转红色——不再误以为正常推进
          <div className="mt-2 flex items-center gap-2 text-[13px] text-destructive">
            <X className="size-3.5 shrink-0" /> 刚才的动作没做成，正在处理
          </div>
        ) : (
          <div className="mt-2 flex items-center gap-2 text-[13px] text-muted-foreground">
            <TypingDots /> 正在干活…
          </div>
        )
      ) : null}
    </div>
  );
}

/** 正文三区渲染（#225 混合坍缩）：更早行（可展开）→ 失败破例面 → 尾部活动区。 */
function WorkBodyRows({ body, frozen }: { body: WorkBody; frozen: boolean }) {
  const [earlierOpen, setEarlierOpen] = useState(false);
  const surfaced = body.earlier.filter(segmentHasFailure);
  return (
    <>
      {earlierOpen ? (
        // 展开回看：更早区全量原序竖流（story5/17 完整过程——含失败段，事件不裁剪）
        body.earlier.map((segment) => (
          <WorkSegmentRow
            key={segmentKey(segment)}
            segment={segment}
            frozen={frozen}
            forceOpen={segmentHasFailure(segment)}
          />
        ))
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
        ? surfaced.map((segment) => (
            <WorkSegmentRow key={segmentKey(segment)} segment={segment} frozen={frozen} forceOpen />
          ))
        : null}
      {body.tail.map((segment) => (
        <WorkSegmentRow
          key={segmentKey(segment)}
          segment={segment}
          frozen={frozen}
          forceOpen={!frozen}
        />
      ))}
    </>
  );
}

/** 呈现段 → 行（single 直行；动作组走折叠行，forceOpen = 生长中尾部/失败破例）。 */
function WorkSegmentRow({
  segment,
  frozen,
  forceOpen = false,
}: {
  segment: WorkSegment;
  frozen: boolean;
  forceOpen?: boolean;
}) {
  if (segment.kind === "single") {
    return <WorkPartRow part={segment.part} frozen={frozen} />;
  }
  return <ActionGroup actions={segment.actions} frozen={frozen} forceOpen={forceOpen} />;
}

/** 段 React key（组取首动作 id——首见事件 id 稳定、状态更新不改键）。 */
function segmentKey(segment: WorkSegment): string {
  return segment.kind === "single" ? segment.part.id : segment.actions[0].id;
}

/** 部件呈现：解说 = 正文段；自检 = 一句话播报行；动作 = 单行状态卡。 */
function WorkPartRow({ part, frozen }: { part: WorkPart; frozen: boolean }) {
  if (part.kind === "text") {
    return <p className="py-1 text-sm leading-relaxed">{part.text}</p>;
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
 * 单行动作状态卡：图标 + 对象短语 + 状态（进行中转圈 / 完成打勾 / 失败「没做成」）。
 * 逐步耗时已退役（#115/ADR-0010：不显示动作时长）。定格后未终态的动作（run 收口
 * 截断的少数）不再转圈——如实留「进行中」字样不带终态标。
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
      {/* 单行截断（#228）：长命令优雅截断不换行撑高——卡片宽度恒定（#225 story8）； */}
      <span className={cn("min-w-0 flex-1 truncate", terminal && "text-muted-foreground")}>
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
 * 动作组折叠行（#116；#225 增破例语义）：连续动作默认折叠为一行（堆叠图标 +
 * 「N 个动作」），点击展开见单条动作。forceOpen = 生长中的尾部组（story8「正在
 * 进行的动作组保持展开」）或失败破例组（story9「事故不被折叠埋掉」）——常驻展开，
 * 用户点击不收力；正常收工（frozen）回落折叠（「做完的」一眼可分）。组内含失败
 * 动作时折叠头带红色状态行计数。组键取首动作 id（首见事件 id 稳定、状态更新不改
 * 键），生长中同组追加动作时折叠态不丢。
 */
function ActionGroup({
  actions,
  frozen,
  forceOpen = false,
}: {
  actions: Extract<WorkPart, { kind: "action" }>[];
  frozen: boolean;
  forceOpen?: boolean;
}) {
  const [userOpen, setUserOpen] = useState(false);
  const open = forceOpen || userOpen;
  const failedCount = actions.filter((action) => action.state === "failed").length;
  return (
    <div>
      <button
        type="button"
        onClick={() => setUserOpen((value) => !value)}
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
        <span
          className={cn(
            "min-w-0 flex-1 text-left text-muted-foreground",
            failedCount > 0 && "text-destructive",
          )}
        >
          {actions.length} 个动作
          {failedCount > 0 ? `（${failedCount} 项没做成）` : ""}
        </span>
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

/** 计划区行高预算（恒定高度：清单超出行数内滚，当前片滚入视口）。 */
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

/** 最新动作部件（尾部活动/滚动行的判定输入；无动作 = undefined）。 */
function latestActionOf(parts: WorkPart[]): Extract<WorkPart, { kind: "action" }> | undefined {
  for (let i = parts.length - 1; i >= 0; i--) {
    const part = parts[i];
    if (part.kind === "action") return part;
  }
  return undefined;
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
