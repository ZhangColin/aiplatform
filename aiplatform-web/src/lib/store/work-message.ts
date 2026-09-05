import { create } from "zustand";

/**
 * 工作消息 store（#81 事件模型迁移，SSE 相关 store——桥为唯一事件写入方，ADR 0003
 * 状态三分法）：按项目记当前编码 run 的**生长中的工作消息**（parts 契约的部件投影
 * ：解说文本部件 + 工具动作部件 + 步骤分组部件），run 收口定格。
 *
 * <p>run 开始即出现（run-start 携 executor 配置键）、随部件事件逐段生长；run-finish /
 * run-failed 定格（不再生长、时长停跳，凝聚物收尾卡归后续票）；下一场编码 run
 * （新 runId = 新一轮）重开新消息、旧消息不保留。静默重试不出用户面（#84：
 * run-start 一场恰一次、用户面 run 身份 = 首试 runId 全程不变）——生长中重来
 * 新 runId 属事件序异常（防御位忽略，不清锚闪空消息）。思考与代码不进部件
 * （服务端口径），本 store 无进度条语义。</p>
 *
 * <p><b>锚定判定</b>：部件事件全事件流恒挂（主智能体对话轮也产部件）——工作消息
 * 只锚编码 run。锚由 run-start(agent=executor) 落；重放缓冲淘汰了 run-start 时
 * 按 {@code coder-} 会话前缀补建（刷新回访续看进行中 run；上一轮已定格、新 run
 * 的 run-start 又被淘汰时同一口重锚；主智能体的 main- 会话不误建——#86 单会话
 * 收敛后前缀判定只此一处残留：编码 run 的会话是执行侧寻址事实，非角色判定）。</p>
 *
 * <p><b>重放幂等</b>：通道是带缓冲热流，重新挂载会重收近期事件——部件事件按 SSE
 * 完整事件 id 只收一次；run-start 同 runId 不清已长部件（重放先到 run-start、
 * 后到部件事件但已被 id 去重，清了就只剩空壳）。</p>
 */

/** 动作部件生命周期（正本 part-action 行：started / running / completed / failed）。 */
export type WorkActionState = "started" | "running" | "completed" | "failed";

/** 自检部件生命周期（正本 part-check 行：checking / passed / failed）。 */
export type WorkCheckState = "checking" | "passed" | "failed";

/** 确认部件生命周期（#83：permission-required → pending，permission-resolved / 作答 → 终态）。 */
export type WorkPermissionState = "pending" | "approved" | "denied";

/** 终态判定（completed / failed——时长定格、文案换终态色）。 */
function isTerminalState(state: WorkActionState): boolean {
  return state === "completed" || state === "failed";
}

/** 自检落定判定（passed / failed——「检查中」的唯一出路）。 */
function isCheckSettled(state: WorkCheckState): boolean {
  return state !== "checking";
}

/** 工作消息部件（part-* 事件 + 权限确认事件的投影）。 */
export type WorkPart =
  | { kind: "text"; id: string; text: string }
  | { kind: "step"; id: string; step: number }
  | {
      kind: "permission";
      /** React key（确认卡首见事件 id）。 */
      id: string;
      /** 作答锚（挂起事件 engineRef——权限作答通道的 REST 寻址腿）。 */
      engineRef: string;
      /** 待确认操作摘要（首工具命令文本，服务端截断保短）。 */
      summary: string;
      state: WorkPermissionState;
      /** 挂起时间戳（ms；事件信封 ts）。 */
      at: number;
    }
  | {
      kind: "action";
      /** React key（动作行首见事件 id——状态更新不改键，原位换装）。 */
      id: string;
      /** 动作锚（同一动作跨状态同值，更新原位命中判定）。 */
      toolCallId: string;
      toolName: string;
      state: WorkActionState;
      /** 动作对象短语（人话行，无时态——时态由 state 表达）。 */
      label: string;
      /** 动作起跑时间戳（ms；started 事件的信封 ts）。 */
      startedAt: number;
      /** 终态落定时间戳（时长 = endedAt - startedAt）。 */
      endedAt?: number;
    }
  | {
      /**
       * 自检部件（#85「正在检查系统 → ✅/❌」）：一场 run 至多一个——收口判据
       * 核验的呈现，跨状态原位换装（checking → passed/failed）。静默重试口径：
       * 尝试间核验未过不出 failed（重复 checking 幂等——用户面一次检查），
       * failed 仅末次未过（与 run-failed 同窗口）。终值即探活结果（startedAt/
       * endedAt 留痕，收尾卡统计行随 #88 消费）。
       */
      kind: "check";
      /** React key（首见 checking 事件 id——原位更新不改键）。 */
      id: string;
      state: WorkCheckState;
      /** 核验开始时间戳（ms；首条 checking 的信封 ts——重试不重置）。 */
      startedAt: number;
      /** 落定时间戳（passed/failed 的信封 ts）。 */
      endedAt?: number;
    };

/** 部件事件的最小关联（信封公共字段 + 事件 id + 信封 ts）。 */
export type PartEventRef = {
  runId: string;
  /** 会话标识（补建锚的 coder- 前缀判定；run-start 后的事件恒携带）。 */
  sessionId?: string;
  /** SSE 完整事件 id（重放去重锚 + 部件 React key）。 */
  eventId: string;
  /** 信封 ts（ms）——时长与起跑锚。 */
  at: number;
};

/** 桥侧部件输入（store 负责落 id / 时长 / 原位更新）。 */
export type WorkPartInput =
  | { kind: "text"; text: string }
  | { kind: "step"; step: number }
  | { kind: "permission"; engineRef: string; summary: string }
  | { kind: "check"; state: WorkCheckState }
  | {
      kind: "action";
      toolCallId: string;
      toolName: string;
      state: WorkActionState;
      label: string;
    };

type ProjectWork = {
  /** 工作消息锚定的 run（新 runId 即重开——下一场 run；静默重试不换新锚，#84）。 */
  runId: string;
  /** run 起跑时间戳（头部总时长锚；补建锚取首部件 ts）。 */
  startedAt: number;
  /** 收口定格（run-finish / run-failed）：部件不进、时长停跳。 */
  frozen: boolean;
  /** 定格时间戳（未终态动作的时长冻结锚）。 */
  frozenAt?: number;
  parts: WorkPart[];
  /** 已收部件事件的 SSE id（重放去重锚，有界）。 */
  seenEventIds: string[];
};

/** 工作消息呈现快照（ProjectWork 去重放簿记——UI 消费面单一来源）。 */
export type WorkSnapshot = Omit<ProjectWork, "seenEventIds">;

export type WorkMessageState = {
  works: Record<string, ProjectWork>;
  /** 编码 run 起跑（run-start agent=executor）：新 runId 重开，同 runId 幂等。 */
  startWork: (projectId: string, runId: string, at: number) => void;
  /** 部件事件入消息（动作按 toolCallId 原位更新；锚定与定格守卫见实现）。 */
  notePart: (projectId: string, ref: PartEventRef, input: WorkPartInput) => void;
  /**
   * 确认卡状态落定（#83）：permission-resolved 事件与作答乐观更新双写口——同值
   * 幂等（事件与乐观双到达不闪换；回滚传 "pending"）。无该 engineRef 的确认部件
   * 时忽略（重放缺口 / 异项目）。
   */
  resolvePermission: (projectId: string, engineRef: string, state: WorkPermissionState) => void;
  /** run 收口定格（run-finish / run-failed）；非锚定 run / 已定格忽略。 */
  freezeWork: (projectId: string, runId: string, at: number) => void;
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

/** 部件应用（动作/自检原位更新；返回原数组引用即无变更）。 */
function applyPart(work: ProjectWork, ref: PartEventRef, input: WorkPartInput): ProjectWork {
  if (input.kind === "check") {
    // 一场 run 至多一个自检部件：跨状态原位换装。静默重试的重复 checking 幂等
    // （同态不改引用——不闪换、不重置起跑锚）；落定（passed/failed）记 endedAt
    const existing = work.parts.find(
      (part): part is Extract<WorkPart, { kind: "check" }> => part.kind === "check",
    );
    if (existing) {
      if (existing.state === input.state) return work;
      const updated: Extract<WorkPart, { kind: "check" }> = {
        ...existing,
        state: input.state,
        endedAt: isCheckSettled(input.state) ? ref.at : existing.endedAt,
      };
      return { ...work, parts: work.parts.map((part) => (part === existing ? updated : part)) };
    }
    return {
      ...work,
      parts: capParts([
        ...work.parts,
        {
          kind: "check",
          id: ref.eventId,
          state: input.state,
          startedAt: ref.at,
          endedAt: isCheckSettled(input.state) ? ref.at : undefined,
        },
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
        endedAt: isTerminalState(input.state) ? ref.at : existing.endedAt,
      };
      parts = work.parts.map((part) => (part === existing ? updated : part));
    } else {
      parts = [
        ...work.parts,
        {
          kind: "action",
          id: ref.eventId,
          toolCallId: input.toolCallId,
          toolName: input.toolName,
          state: input.state,
          label: input.label,
          startedAt: ref.at,
          endedAt: isTerminalState(input.state) ? ref.at : undefined,
        },
      ];
    }
    return { ...work, parts: capParts(parts) };
  }
  const part: WorkPart =
    input.kind === "text"
      ? { kind: "text", id: ref.eventId, text: input.text }
      : input.kind === "permission"
        ? {
            kind: "permission",
            id: ref.eventId,
            engineRef: input.engineRef,
            summary: input.summary,
            state: "pending",
            at: ref.at,
          }
        : { kind: "step", id: ref.eventId, step: input.step };
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

  startWork: (projectId, runId, at) =>
    updateWork(set, projectId, (work) => {
      // 同 runId 幂等（重放）：已长部件与起跑锚都保留，不重开
      if (work?.runId === runId) return work;
      return { runId, startedAt: at, frozen: false, parts: [], seenEventIds: [] };
    }),

  notePart: (projectId, ref, input) =>
    updateWork(set, projectId, (work) => {
      // 锚不在或已定格（重放缺 run-start / 上一场定格后新 run 已开工）：仅编码
      // 会话补建/重锚——主智能体的部件不建工作消息（对话面走 text 增量气泡，部件
      // 与其并行双发射）。生长中的锚 + 异 runId = 事件序异常（静默重试不换新锚，
      // #84——有序流不至，防御位忽略；清锚会闪空消息）
      if (work === undefined || work.runId !== ref.runId) {
        if (work !== undefined && !work.frozen) return work;
        if (!ref.sessionId?.startsWith(CODER_SESSION_PREFIX)) return work;
        work = { runId: ref.runId, startedAt: ref.at, frozen: false, parts: [], seenEventIds: [] };
      }
      if (work.frozen) return work; // 定格不进部件（收口后无增量）
      if (work.seenEventIds.includes(ref.eventId)) return work; // 重放去重
      return applyPart({ ...work, seenEventIds: appendCapped(work.seenEventIds, ref.eventId) }, ref, input);
    }),

  resolvePermission: (projectId, engineRef, state) =>
    updateWork(set, projectId, (work) => {
      if (work === undefined) return work;
      const target = work.parts.find(
        (part): part is Extract<WorkPart, { kind: "permission" }> =>
          part.kind === "permission" && part.engineRef === engineRef,
      );
      if (!target || target.state === state) return work;
      const parts = work.parts.map((part) =>
        part === target ? { ...target, state } : part,
      );
      return { ...work, parts };
    }),

  freezeWork: (projectId, runId, at) =>
    updateWork(set, projectId, (work) => {
      if (work?.runId !== runId || work.frozen) return work;
      return { ...work, frozen: true, frozenAt: at };
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
