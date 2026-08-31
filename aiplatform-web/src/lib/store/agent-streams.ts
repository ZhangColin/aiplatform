import { create } from "zustand";

/**
 * agent 流过程层 store（ADR 0003）：按 runId 键控、run 内分段数组。
 * SSE handler（bridge）唯一写入方；呈现组件只读。
 * 驱逐：**只由 startRun 触发**——按项目留最近 1 个 run + 总量软上限 10，
 * 够回看刚才那次运行、内存有界；迟到/乱序事件补建 stub 不驱逐（否则旧 run
 * 重放会把项目当前 run 反转掉）。
 */

export type AgentRunStatus = "running" | "waiting" | "finished" | "error";

/** run 内分段：平台事件与引擎透传各归其 kind，透传 data 保持 part 原样。 */
export type AgentStreamSegment =
  | {
      kind: "role";
      id: string;
      role: string;
      roleLabel: string;
      engine: string;
    }
  | { kind: "wait"; id: string; waitKind: string; summary: string }
  | {
      /** 生成自动重试（task-retrying）：锚定的尝试失败后同一场生成仍在途。 */
      kind: "retrying";
      id: string;
      attempt: number;
      message: string;
    }
  | { kind: "text"; id: string; data: unknown }
  | { kind: "reasoning"; id: string; data: unknown }
  | { kind: "patch"; id: string; data: unknown }
  | { kind: "tool"; id: string; data: unknown }
  | { kind: "step"; id: string; phase: "start" | "finish"; data: unknown }
  | { kind: "error"; id: string; message: string }
  | { kind: "finish"; id: string; finish: string }
  /** 引擎透传的未知名型：原样保留，呈现层兜底。 */
  | { kind: "passthrough"; id: string; type: string; data: unknown };

export type AgentRun = {
  runId: string;
  projectId: string;
  /** task-start 的 prompt / model；缺 task-start 的补建 run 无这些字段。 */
  prompt?: string;
  model?: string;
  engine?: string;
  sessionId?: string;
  /** 运行起始时间戳（ms，顶栏计时锚）。建 run 时落；断线补建的 stub 近似 now。 */
  startedAt?: number;
  status: AgentRunStatus;
  segments: AgentStreamSegment[];
};

/** run 定位对（信封公共关联字段）：bridge 侧整传 payload 即可。 */
type RunTarget = Pick<AgentRun, "runId" | "projectId">;

type StartRunInput = RunTarget & {
  prompt?: string;
  model?: string;
  engine?: string;
};

export type AgentStreamsState = {
  /** 键 = runId。 */
  runs: Record<string, AgentRun>;
  /** runId 插入序，驱逐与「最近一次运行」读取用。 */
  order: string[];
  startRun: (input: StartRunInput) => void;
  /** run 不存在时按事件携带的 projectId 补建 stub（断线缺口修复），不触发驱逐。 */
  appendSegment: (target: RunTarget, segment: AgentStreamSegment) => void;
  markSession: (target: RunTarget, sessionId: string) => void;
};

/** 总量软上限。 */
const MAX_RUNS = 10;

type SetFn = (partial: Partial<AgentStreamsState>) => void;

/** 建新 run。evict = true（仅 startRun）时先清同项目旧 run，再压总量软上限。 */
function ensureRun(set: SetFn, input: StartRunInput, evict: boolean): void {
  const state = useAgentStreamsStore.getState();
  const runs: Record<string, AgentRun> = { ...state.runs };
  let order = state.order;
  if (evict) {
    // 驱逐同项目旧 run；自身已存在（role-assigned 等首帧先到补建的 stub）时保留不误删
    order = order.filter((runId) => {
      if (runId === input.runId) return true;
      const sameProject = runs[runId].projectId === input.projectId;
      if (sameProject) delete runs[runId];
      return !sameProject;
    });
  }
  const existing = runs[input.runId];
  if (existing) {
    if (!evict) return;
    // task-start 晚于 role-assigned（帧序 role-assigned → task-start，正本）：run 已由
    // 首帧补建 stub，这里补 task-start 元数据（prompt/model/engine）而不清已收分段
    //（stub 的 startedAt 保留——运行锚更早更准）。此前 early-return 会把 prompt 丢掉，
    // 用户右泡（task-start 的 prompt）永远不出现。
    runs[input.runId] = {
      ...existing,
      prompt: input.prompt,
      model: input.model,
      engine: input.engine,
    };
    set({ runs, order });
    return;
  }
  runs[input.runId] = {
    runId: input.runId,
    projectId: input.projectId,
    prompt: input.prompt,
    model: input.model,
    engine: input.engine,
    startedAt: Date.now(),
    status: "running",
    segments: [],
  };
  order.push(input.runId);
  while (order.length > MAX_RUNS) {
    const evicted = order.shift();
    if (evicted !== undefined) delete runs[evicted];
  }
  set({ runs, order });
}

/** 确保 run 存在（缺则补建 stub，不驱逐）后对其做一次变更。 */
function withRun(
  set: SetFn,
  target: RunTarget,
  mutate: (run: AgentRun) => AgentRun,
): void {
  ensureRun(set, target, false);
  const state = useAgentStreamsStore.getState();
  const run = state.runs[target.runId];
  const next = mutate(run);
  if (next === run) return;
  set({ runs: { ...state.runs, [target.runId]: next } });
}

export const useAgentStreamsStore = create<AgentStreamsState>((set) => ({
  runs: {},
  order: [],
  startRun: (input) => ensureRun(set, input, true),
  appendSegment: (target, segment) =>
    withRun(set, target, (run) => ({
      ...run,
      status: nextRunStatus(run.status, segment),
      segments: [...run.segments, segment],
    })),
  markSession: (target, sessionId) =>
    withRun(set, target, (run) =>
      run.sessionId === sessionId ? run : { ...run, sessionId },
    ),
}));

/**
 * 最近 run 读口（顶栏 LIVE / 后续直播视图共用）：该项目插入序最近的一个 run
 * （order 尾部倒查）。驱逐已保证同项目至多 1 个 task-start 建的真 run；断线
 * 缺口补建的 stub 在 order 尾部时即最新可见 run。
 */
export function latestProjectRun(
  state: Pick<AgentStreamsState, "runs" | "order">,
  projectId: string,
): AgentRun | undefined {
  for (let i = state.order.length - 1; i >= 0; i--) {
    const run = state.runs[state.order[i]];
    if (run?.projectId === projectId) return run;
  }
  return undefined;
}

/** 终态/等待分段推导 run 状态：wait（问答挂起）= waiting，等用户 ≠ 终态；
 * retrying 把失败尝试的 error 拉回 running（同一场生成仍在途，下一尝试即 task-start）。 */
function nextRunStatus(
  current: AgentRunStatus,
  segment: AgentStreamSegment,
): AgentRunStatus {
  switch (segment.kind) {
    case "wait":
      return "waiting";
    case "retrying":
      return "running";
    case "error":
      return "error";
    case "finish":
      return "finished";
    default:
      return current;
  }
}
