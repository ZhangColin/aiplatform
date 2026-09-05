import { create } from "zustand";

/**
 * 运行注册表 store（SSE 相关 store，ADR 0003 状态三分法）：按 runId 记项目当前
 * 运行的生命周期状态与起跑时戳——顶栏 LIVE 脉冲 + 计时的唯一数据源（「关了浏览
 * 器也在跑」的会话内锚点）。桥为唯一事件写入方；呈现组件只读。
 *
 * <p>驱逐：**只由 run-start 触发**——按项目留最近 1 个 run + 总量软上限 10；
 * 迟到/乱序事件补建 stub 不驱逐（否则旧 run 重放会把项目当前 run 反转掉）。
 * runId 与起跑时戳取自事件（run-start 的信封 ts——重放重建时计时仍准）。</p>
 */

export type AgentRunStatus = "running" | "questioning" | "finished" | "error";

export type AgentRun = {
  runId: string;
  projectId: string;
  /** 运行起始时间戳（ms，run-start 信封 ts；断线补建的 stub 近似事件 ts）。 */
  startedAt: number;
  status: AgentRunStatus;
};

/** run 定位对（信封公共关联字段）：bridge 侧整传 payload 即可。 */
type RunTarget = Pick<AgentRun, "runId" | "projectId">;

export type AgentRunsState = {
  /** 键 = runId。 */
  runs: Record<string, AgentRun>;
  /** runId 插入序，驱逐与「最近一次运行」读取用。 */
  order: string[];
  /** run 起跑（run-start）：新 runId 重开（同项目驱逐旧 run），同 runId 幂等。 */
  startRun: (input: RunTarget & { at: number }) => void;
  /**
   * 生命周期状态落定（run 不存在时按事件携带的 projectId 补建 stub，不驱逐）：
   * question-raised → questioning（等用户 ≠ 终态）；run-finish → finished；
   * run-failed / error → error（run 失败为唯一失败终态）。
   */
  setRunStatus: (target: RunTarget & { at: number }, status: AgentRunStatus) => void;
};

/** 总量软上限。 */
const MAX_RUNS = 10;

type SetFn = (partial: Partial<AgentRunsState>) => void;

/** 建新 run。evict = true（仅 startRun）时先清同项目旧 run，再压总量软上限。 */
function ensureRun(
  set: SetFn,
  input: RunTarget & { at: number },
  evict: boolean,
): void {
  const state = useAgentRunsStore.getState();
  const runs: Record<string, AgentRun> = { ...state.runs };
  let order = state.order;
  if (evict) {
    // 驱逐同项目旧 run；自身已存在（事件先到补建的 stub）时保留不误删
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
    // run-start 重放（同 runId）：保留更早的起跑锚（计时更准），不重开
    set({ runs, order });
    return;
  }
  runs[input.runId] = {
    runId: input.runId,
    projectId: input.projectId,
    startedAt: input.at,
    status: "running",
  };
  order.push(input.runId);
  while (order.length > MAX_RUNS) {
    const evicted = order.shift();
    if (evicted !== undefined) delete runs[evicted];
  }
  set({ runs, order });
}

/** 确保 run 存在（缺则补建 stub，不驱逐）后落定其状态。 */
function withRun(
  set: SetFn,
  target: RunTarget & { at: number },
  status: AgentRunStatus,
): void {
  ensureRun(set, target, false);
  const state = useAgentRunsStore.getState();
  const run = state.runs[target.runId];
  if (run.status === status) return;
  set({ runs: { ...state.runs, [target.runId]: { ...run, status } } });
}

export const useAgentRunsStore = create<AgentRunsState>((set) => ({
  runs: {},
  order: [],
  startRun: (input) => ensureRun(set, input, true),
  setRunStatus: (target, status) => withRun(set, target, status),
}));

/**
 * 最近 run 读口（顶栏 LIVE 脉冲 + 计时）：该项目插入序最近的一个 run（order
 * 尾部倒查）。驱逐已保证同项目至多 1 个 run-start 建的真 run；断线缺口补建的
 * stub 在 order 尾部时即最新可见 run。
 */
export function latestProjectRun(
  state: Pick<AgentRunsState, "runs" | "order">,
  projectId: string,
): AgentRun | undefined {
  for (let i = state.order.length - 1; i >= 0; i--) {
    const run = state.runs[state.order[i]];
    if (run?.projectId === projectId) return run;
  }
  return undefined;
}
