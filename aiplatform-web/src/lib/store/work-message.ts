import { create } from "zustand";

/**
 * 工作消息 store（#81 事件模型迁移，SSE 相关 store——桥为唯一事件写入方，ADR 0003
 * 状态三分法）：按项目记当前编码 run 的**生长中的工作消息**（parts 契约的部件投影
 * ：解说文本部件 + 工具动作部件 + 步骤清单快照〔#236 part-plan——整表落 `plan`，
 * 不进 parts 流水〕），run 收口定格。
 *
 * <p>run 开始即出现（run-start 携 executor 配置键）、随部件事件逐段生长；run-finish /
 * run-failed 定格（不再生长）。成功收口（run-finish 携 closing，#88/#89
 * 收尾卡归对话流常驻）——工作消息<b>原地定格留驻</b>（#117：部件保留、只读、不再
 * 生长，成功收口不再清空，收尾卡随后入流形成「过程上文、结果下卡」纵序；过程明细
 * 仍不落库，刷新/回访后明细不现、收尾卡照常水合——定格留驻只管当次会话呈现）；
 * run-failed 定格流水留驻（恢复出口归生成面）；下一场编码 run（新 runId = 新一轮）
 * 重开新消息、旧消息不保留。静默重试不出用户面（#84：run-start 一场恰一次、用户面
 * run 身份 = 首试 runId 全程不变）——生长中重来新 runId 属事件序异常（防御位忽略，
 * 不清锚闪空消息）。思考与代码不进部件（服务端口径），本 store 无进度条语义。步骤
 * 分组与逐步耗时已退役（#115：部件按序竖排）；run 级「已运行」时钟随 #225 窄幅回归
 * （ADR-0010 修订：startedAt/endedAt 只锚 run-start / 收口事件信封 ts，不涉逐步耗时）。</p>
 *
 * <p><b>锚定判定</b>：部件事件全事件流恒挂（主智能体对话轮也产部件）——工作消息
 * 只锚编码 run。锚由 run-start(agent=executor) 落；断线补发窗口淘汰了 run-start 时
 * 按 {@code coder-} 会话前缀补建（重连续看进行中 run；上一轮已定格、新 run 的
 * run-start 又被淘汰时同一口重锚；主智能体的 main- 会话不误建——#86 单会话收敛后
 * 前缀判定只此一处残留：编码 run 的会话是执行侧寻址事实，非角色判定）。</p>
 *
 * <p><b>重放幂等</b>：断线补发窗口会重收已见部件事件——按 SSE 完整事件 id 只收
 * 一次；run-start 同 runId 不清已长部件（补发先到 run-start、后到部件事件但已被
 * id 去重，清了就只剩空壳）。</p>
 */

/** 动作部件生命周期（正本 part-action 行：started / running / completed / failed）。 */
export type WorkActionState = "started" | "running" | "completed" | "failed";

/** 自检部件生命周期（正本 part-check 行：checking / passed / failed）。 */
export type WorkCheckState = "checking" | "passed" | "failed";

/**
 * 步骤清单条目（#236 part-plan 快照的投影）：agent 自产计划的步骤——稳定 id
 * （跨快照不变）、用户语言标题、状态 ✓●○ 三态（不设 ✗——失败留痕归动作部件
 * 与收尾卡）。快照整表替换（不进 parts 流水——计划变化不单独播报、不留动作痕）。
 */
export type WorkPlanStep = {
  id: string;
  title: string;
  state: "pending" | "in_progress" | "completed";
};

/**
 * 工作消息头部切片进度（run-start 扩载 #118）：`title` 为用户语言标题（生成轨道
 * = 切片标题、阶段 0 = 「系统初始化」、更新 run = 「系统更新」）；`index`/`total`
 * 仅生成轨道切片携带（1-based——头部「{title}（{index}/{total}）」），缺省即无进度。
 */
export type WorkSlice = {
  title: string;
  index?: number;
  total?: number;
};

/** 工作消息部件（part-* 事件的投影）。 */
export type WorkPart =
  | { kind: "text"; id: string; source?: string; text: string }
  | {
      kind: "action";
      /** React key（动作行首见事件 id——状态更新不改键，原位换装）。 */
      id: string;
      /** 来源归属（#95 委派位：子智能体名；执行体缺省——分角色播的依据）。 */
      source?: string;
      /** 动作锚（同一动作跨状态同值，更新原位命中判定）。 */
      toolCallId: string;
      toolName: string;
      state: WorkActionState;
      /** 动作对象短语（人话行，无时态——时态由 state 表达；execute = 命令原文首行，#228 直播行 live tail）。 */
      label: string;
      /**
       * 失败留痕（#229）：仅 failed 携带——错误/stderr 首行截断（服务端已截，
       * 与 label 各管各的额度）；失败红行渲染 label＋error，排障不进容器即可
       * 初判原因。其余态缺省。
       */
      error?: string;
    }
  | {
      /**
       * 自检部件（#85「正在检查系统 → ✅/❌」）：一场 run 至多一个——收口判据
       * 核验的呈现，跨状态原位换装（checking → passed/failed）。静默重试口径：
       * 尝试间核验未过不出 failed（重复 checking 幂等——用户面一次检查），
       * failed 仅末次未过（与 run-failed 同窗口）。终值即探活结果。
       */
      kind: "check";
      /** React key（首见 checking 事件 id——原位更新不改键）。 */
      id: string;
      state: WorkCheckState;
    };

/** 部件事件的最小关联（信封公共字段 + 事件 id）。 */
export type PartEventRef = {
  runId: string;
  /** 会话标识（补建锚的 coder- 前缀判定；run-start 后的事件恒携带）。 */
  sessionId?: string;
  /** SSE 完整事件 id（重放去重锚 + 部件 React key）。 */
  eventId: string;
  /** 来源归属（#95 委派位：子智能体名；执行体缺省）。 */
  source?: string;
};

/** 桥侧部件输入（store 负责落 id / 原位更新）。 */
export type WorkPartInput =
  | { kind: "text"; text: string }
  | { kind: "check"; state: WorkCheckState }
  | {
      kind: "action";
      toolCallId: string;
      toolName: string;
      state: WorkActionState;
      label: string;
      /** 失败留痕（#229）：仅 failed 事件的 payload 携带（错误/stderr 首行截断）。 */
      error?: string;
    };

type ProjectWork = {
  /** 工作消息锚定的 run（新 runId 即重开——下一场 run；静默重试不换新锚，#84）。 */
  runId: string;
  /** 收口定格（run-finish / run-failed）：部件不进。 */
  frozen: boolean;
  parts: WorkPart[];
  /** 已收部件事件的 SSE id（重放去重锚，有界）。 */
  seenEventIds: string[];
  /** 工作消息头部切片进度（#118 run-start 扩载；run-start 被淘汰的补建路径缺省）。 */
  slice?: WorkSlice;
  /**
   * run 级步骤清单（#236 part-plan 全量快照的落态）：整表替换（执行中 agent
   * 再调 update_plan 即新快照盖旧表——已收口步骤不可变＝提示词纪律，平台不校验
   * 不合并）；不进 parts 流水（不走动作行、不留动作痕、不单独播报）。不落库
   * （当次会话定格留驻，刷新不回显）；无快照（agent 不调）= 缺省不显示。
   */
  plan?: WorkPlanStep[];
  /**
   * run 级时钟起锚（#225，ADR-0010 窄修订）：run-start 事件信封 ts 的 epoch ms
   * ——前端「已运行 mm:ss」的唯一锚（活性锚定真实事件，缺锚不渲染时钟）。run-start
   * 被淘汰的补建路径缺省（不伪造起点）。
   */
  startedAt?: number;
  /** 时钟定锚（run-finish / run-failed 事件信封 ts 的 epoch ms；首次定格为准）。 */
  endedAt?: number;
};

/** 工作消息呈现快照（ProjectWork 去重放簿记——UI 消费面单一来源）。 */
export type WorkSnapshot = Omit<ProjectWork, "seenEventIds">;

export type WorkMessageState = {
  works: Record<string, ProjectWork>;
  /** 编码 run 起跑（run-start agent=executor）：新 runId 重开，同 runId 幂等；携
   * 工作消息头部切片进度（#118，缺省 = 无标题回落「正在做」）与 run 级时钟起锚
   * （#225 run-start 信封 ts 的 epoch ms，缺省 = 无锚不渲染时钟）。 */
  startWork: (projectId: string, runId: string, slice?: WorkSlice, startedAt?: number) => void;
  /** 部件事件入消息（动作按 toolCallId 原位更新；锚定与定格守卫见实现）。 */
  notePart: (projectId: string, ref: PartEventRef, input: WorkPartInput) => void;
  /**
   * 步骤清单快照入消息（#236 part-plan）：整表替换 `plan`（快照语义——不进
   * parts 流水、不产生播报部件）；锚定/定格/重放去重守卫与 notePart 同款。
   * 空表 = 无效载荷忽略（服务端不出空快照，畸形载荷不清好表）。
   */
  notePlan: (projectId: string, ref: PartEventRef, steps: WorkPlanStep[]) => void;
  /**
   * run 收口定格（run-finish / run-failed）；非锚定 run / 已定格忽略。工作消息
   * 原地定格留驻（#117）：部件保留、只读、不再生长，成功收口（收尾卡归 chat
   * store 对话流、随后入流）不再清空——「过程上文、结果下卡」；run-failed 同样
   * 留驻（现状）。定格即终态，去重簿记随消息保留（定格后 notePart 早退，不再
   * 消费）。endedAt = 收口事件信封 ts（时钟定格锚，#225；重放再定格首次为准）。
   */
  freezeWork: (projectId: string, runId: string, endedAt?: number) => void;
};

/** 部件数软上限（重放缓冲 ~1000 事件的投影，内存有界）。 */
const MAX_PARTS = 300;
/** 事件 id 去重集软上限。 */
const MAX_IDS = 1000;

/** 编码会话前缀（后端角色 × 项目命名约定：coder-{projectId}）。 */
const CODER_SESSION_PREFIX = "coder-";

function appendCapped(list: string[], id: string): string[] {
  const next = [...list, id];
  return next.length > MAX_IDS ? next.slice(next.length - MAX_IDS) : next;
}

/**
 * 锚定与定格守卫（部件/计划快照共用，notePart / notePlan 的同款前段）：
 * 锚不在或异 runId 时仅编码会话补建/重锚（主智能体的部件不建工作消息——对话面走
 * text 增量气泡，部件与其并行双发射；生长中的锚 + 异 runId = 事件序异常，静默重试
 * 不换新锚〔#84〕，防御位忽略——清锚会闪空消息）；定格不进（收口后无增量）；
 * 重放按 SSE 事件 id 去重。守卫未过 / 去重命中返回 undefined（无变更）。
 */
function openedWork(work: ProjectWork | undefined, ref: PartEventRef): ProjectWork | undefined {
  let current = work;
  if (current === undefined || current.runId !== ref.runId) {
    if (current !== undefined && !current.frozen) return undefined;
    if (!ref.sessionId?.startsWith(CODER_SESSION_PREFIX)) return undefined;
    current = { runId: ref.runId, frozen: false, parts: [], seenEventIds: [] };
  }
  if (current.frozen) return undefined; // 定格不进部件（收口后无增量）
  if (current.seenEventIds.includes(ref.eventId)) return undefined; // 重放去重
  return { ...current, seenEventIds: appendCapped(current.seenEventIds, ref.eventId) };
}

/** 部件应用（动作/自检原位更新；返回原数组引用即无变更）。 */
function applyPart(work: ProjectWork, ref: PartEventRef, input: WorkPartInput): ProjectWork {
  if (input.kind === "check") {
    // 一场 run 至多一个自检部件：跨状态原位换装。静默重试的重复 checking 幂等
    // （同态不改引用——不闪换）
    const existing = work.parts.find(
      (part): part is Extract<WorkPart, { kind: "check" }> => part.kind === "check",
    );
    if (existing) {
      if (existing.state === input.state) return work;
      const updated: Extract<WorkPart, { kind: "check" }> = {
        ...existing,
        state: input.state,
      };
      return { ...work, parts: work.parts.map((part) => (part === existing ? updated : part)) };
    }
    return {
      ...work,
      parts: capParts([
        ...work.parts,
        { kind: "check", id: ref.eventId, state: input.state },
      ]),
    };
  }
  if (input.kind === "action") {
    const existing = work.parts.find(
      (part): part is Extract<WorkPart, { kind: "action" }> =>
        part.kind === "action" && part.toolCallId === input.toolCallId,
    );
    let parts: WorkPart[];
    if (existing) {
      const updated: Extract<WorkPart, { kind: "action" }> = {
        ...existing,
        state: input.state,
        label: input.label,
        error: input.error,
      };
      parts = work.parts.map((part) => (part === existing ? updated : part));
    } else {
      parts = [
        ...work.parts,
        {
          kind: "action",
          id: ref.eventId,
          source: ref.source,
          toolCallId: input.toolCallId,
          toolName: input.toolName,
          state: input.state,
          label: input.label,
          error: input.error,
        },
      ];
    }
    return { ...work, parts: capParts(parts) };
  }
  const part: WorkPart = { kind: "text", id: ref.eventId, source: ref.source, text: input.text };
  return { ...work, parts: capParts([...work.parts, part]) };
}

function capParts(parts: WorkPart[]): WorkPart[] {
  return parts.length > MAX_PARTS ? parts.slice(parts.length - MAX_PARTS) : parts;
}

type SetFn = (partial: Partial<WorkMessageState>) => void;

function updateWork(
  set: SetFn,
  projectId: string,
  mutate: (work: ProjectWork | undefined) => ProjectWork | undefined,
): void {
  const state = useWorkMessageStore.getState();
  const current = state.works[projectId];
  const next = mutate(current);
  // undefined = 无变更（守卫未过 / 去重命中）
  if (next === undefined || next === current) return;
  set({ works: { ...state.works, [projectId]: next } });
}

export const useWorkMessageStore = create<WorkMessageState>((set) => ({
  works: {},

  startWork: (projectId, runId, slice, startedAt) =>
    updateWork(set, projectId, (work) => {
      // 同 runId 幂等（重放）：已长部件保留，不重开
      if (work?.runId === runId) return work;
      return { runId, frozen: false, parts: [], seenEventIds: [], slice, startedAt };
    }),

  notePart: (projectId, ref, input) =>
    updateWork(set, projectId, (work) => {
      const opened = openedWork(work, ref);
      return opened ? applyPart(opened, ref, input) : work;
    }),

  notePlan: (projectId, ref, steps) =>
    updateWork(set, projectId, (work) => {
      const opened = openedWork(work, ref);
      // 空表 = 无效载荷忽略（服务端不出空快照）
      return opened && steps.length > 0 ? { ...opened, plan: steps } : work;
    }),

  freezeWork: (projectId, runId, endedAt) =>
    updateWork(set, projectId, (work) => {
      if (work?.runId !== runId || work.frozen) return work;
      // 原地定格留驻（#117）：部件保留、只读、不再生长（成功收口不再清空——收尾卡
      // 归 chat store 随后入流；run-failed 同样留驻），去重簿记随消息保留；
      // endedAt 时钟定锚（#225，首次定格为准——重放再定格忽略）
      return { ...work, frozen: true, endedAt };
    }),
}));

/** 空部件共享常量（selector 引用稳定——`?? []` 每次新引用会让订阅面无限重渲染）。 */
const NO_PARTS: WorkPart[] = [];

/** 项目当前工作消息部件（未见 = 空数组，引用稳定）。 */
export function workPartsOf(
  state: Pick<WorkMessageState, "works">,
  projectId: string,
): WorkPart[] {
  return state.works[projectId]?.parts ?? NO_PARTS;
}

const PLAN_STEP_STATES: ReadonlySet<string> = new Set(["pending", "in_progress", "completed"]);

/**
 * part-plan 载荷 steps → 消费口径（#236，toWorkClosing 先例——线上数据容错归一）：
 * 缺 id/标题的条目剔除、未知状态回落 pending（多跑向安全）；全空 = 空表（桥侧
 * 忽略，不清好表）。服务端内核已归一，此处兜底不改语义。
 */
export function toWorkPlanSteps(raw: unknown): WorkPlanStep[] {
  if (!Array.isArray(raw)) return [];
  return raw.flatMap((entry): WorkPlanStep[] => {
    if (typeof entry !== "object" || entry === null) return [];
    const { id, title, state } = entry as Record<string, unknown>;
    if (typeof id !== "string" || !id.trim() || typeof title !== "string" || !title.trim()) {
      return [];
    }
    return [{
      id,
      title,
      state: typeof state === "string" && PLAN_STEP_STATES.has(state)
        ? (state as WorkPlanStep["state"])
        : "pending",
    }];
  });
}
