import { create } from "zustand";

/**
 * 工作消息 store（#81 事件模型迁移，SSE 相关 store——桥为唯一事件写入方，ADR 0003
 * 状态三分法）：按项目记当前编码 run 的**生长中的工作消息**（parts 契约的部件投影
 * ：工具动作部件 + 脱轨信号部件〔#240 part-signal——活性行脱轨变体的推导源，静态
 * 面滤除在呈现层〕）。呈现面清场（#608，ADR-0057 模型文本不直播）：解说文本部件、
 * 自检播报部件、步骤清单快照随发射源退役，store 不再收这三类。run 收口定格。
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

/**
 * 脱轨信号值（正本 part-signal 词表，封闭集 v1 唯一值 #240）：derailed = 模型以
 * 机器语法直接发射、引擎未识别为工具调用（什么都没跑）——吞段的人话留痕，呈现
 * 归活性行脱轨变体（定型文案在组件），静态面无痕。
 */
export type WorkSignalKind = "derailed";

/**
 * 工作消息头部（run-start 扩载 #118）：`title` 为用户语言标题（生成轨道
 * =「系统初始化」、更新 run =「系统更新」）；切片序号/总数已随切片机器退役
 * （#602/#608），不再携带进度。
 */
export type WorkSlice = {
  title: string;
};

/** 工作消息部件（part-* 事件的投影；#608 起只有工具动作与脱轨信号两件）。 */
export type WorkPart =
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
      /**
       * 写文件目标路径（#618 预览跟随）：仅 write_file／edit_file 落定/终态携带
       * （path 参数原形；参数在途缺省）——纯函数据此映射应用路由，写到哪页切
       * 哪页；非页面文件由映射函数过滤。组件／呈现不走该字段。
       */
      path?: string;
    }
  | {
      /**
       * 脱轨信号部件（#240 机器语法吞段的人话留痕）：每次吞段一件（不原位更新、
       * 天然低频）——只报发生事实不携带原文；活性行脱轨变体的推导源（呈现归组件
       * 投影：静态面无痕、不伪造动作）。
       */
      kind: "signal";
      /** React key（首见事件 id）。 */
      id: string;
      /** 来源归属（#95 委派位：子智能体名；执行体缺省）。 */
      source?: string;
      /** 信号值（封闭词表，v1 唯一 derailed）。 */
      signal: WorkSignalKind;
    };
// part-hint（#437 决策时微指令部件）2026-11-12 退役：微指令是模型面指引、用户面
// 只添噪（后端已停发、桥不再投递）。part-text / part-check / part-plan 随 #608
// 呈现面清场退役（ADR-0057 模型文本不直播）——历史存量事件按未知类型忽略。

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
  | { kind: "signal"; signal: WorkSignalKind }
  | {
      kind: "action";
      toolCallId: string;
      toolName: string;
      state: WorkActionState;
      label: string;
      /** 失败留痕（#229）：仅 failed 事件的 payload 携带（错误/stderr 首行截断）。 */
      error?: string;
      /** 写文件目标路径（#618）：仅写文件类落定/终态事件携带（参数原形）。 */
      path?: string;
    };

type ProjectWork = {
  /** 工作消息锚定的 run（新 runId 即重开——下一场 run；静默重试不换新锚，#84）。 */
  runId: string;
  /** 收口定格（run-finish / run-failed）：部件不进。 */
  frozen: boolean;
  parts: WorkPart[];
  /** 已收部件事件的 SSE id（重放去重锚，有界）。 */
  seenEventIds: string[];
  /** 工作消息头部标题（#118 run-start 扩载；run-start 被淘汰的补建路径缺省）。 */
  slice?: WorkSlice;
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
  /** 编码 run 起跑（run-start agent=executor）：新 runId 重开，同 runId 幂等；
   * 携工作消息头部切片进度（#118，缺省 = 无标题回落「正在做」）与 run 级时钟
   * 起锚（#225 run-start 信封 ts 的 epoch ms，缺省 = 无锚不渲染时钟）。 */
  startWork: (
    projectId: string,
    runId: string,
    slice?: WorkSlice,
    startedAt?: number,
  ) => void;
  /** 部件事件入消息（动作按 toolCallId 原位更新；锚定与定格守卫见实现）。 */
  notePart: (projectId: string, ref: PartEventRef, input: WorkPartInput) => void;
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
 * 锚定与定格守卫（部件事件前段）：
 * 锚不在或异 runId 时仅编码会话补建/重锚（主智能体的部件不建工作消息——对话
 * 面走 text 增量气泡，部件与其并行双发射；生长中的锚 + 异 runId = 事件序异常，静默
 * 重试不换新锚〔#84〕，防御位忽略——清锚会闪空消息）；定格不进（收口后无增量）；
 * 重放按 SSE 事件 id 去重。守卫未过 / 去重命中返回 undefined（无变更）。补建路径按
 * 会话前缀判定（coder- 编码 run）。
 */
function openedWork(work: ProjectWork | undefined, ref: PartEventRef): ProjectWork | undefined {
  let current = work;
  if (current === undefined || current.runId !== ref.runId) {
    if (current !== undefined && !current.frozen) return undefined;
    const sessionId = ref.sessionId ?? "";
    if (!sessionId.startsWith(CODER_SESSION_PREFIX)) {
      return undefined;
    }
    current = { runId: ref.runId, frozen: false, parts: [], seenEventIds: [] };
  }
  if (current.frozen) return undefined; // 定格不进部件（收口后无增量）
  if (current.seenEventIds.includes(ref.eventId)) return undefined; // 重放去重
  return { ...current, seenEventIds: appendCapped(current.seenEventIds, ref.eventId) };
}

/** 部件应用（动作原位更新；脱轨信号逐件追加；返回原数组引用即无变更）。 */
function applyPart(work: ProjectWork, ref: PartEventRef, input: WorkPartInput): ProjectWork {
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
        // 路径只在落定/终态事件携带（started 缺省）——跨状态原位更新时保留已见值
        path: input.path ?? existing.path,
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
          path: input.path,
        },
      ];
    }
    return { ...work, parts: capParts(parts) };
  }
  // 脱轨信号（#240）：每次吞段一件、不原位更新（天然低频——刷屏节流是触发器）
  const part: WorkPart = {
    kind: "signal",
    id: ref.eventId,
    source: ref.source,
    signal: input.signal,
  };
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
