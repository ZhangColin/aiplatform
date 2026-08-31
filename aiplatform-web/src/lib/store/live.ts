import { create } from "zustand";

/**
 * 直播面 store（#23 生成环②，SSE 相关 store——ADR 0003 状态三分法）：按项目记当前
 * 编码 run 的直播段（live-text / live-action / live-step 帧投影）。桥为唯一事件
 * 写入方；直播侧栏只读。
 *
 * <p>与 agent-streams / generation 的分工：streams 收全量过程帧（引擎透传含内），
 * generation 记生成事实与状态机，本 store 只收 <b>直播词汇帧</b>——前端不耦合引擎
 * 事件格式（SSE事件清单·通道二直播行），呈现即客户面解说广播。换 run 即重开
 * （重试下一尝试 / 新一轮生成）；run 结束收起即逝、无历史回看（段留在内存无害，
 * 侧栏随 lifecycle 隐藏）。刷新后由 agent 流通道重放缓冲重建（续看进行中 run）。</p>
 */

/** 直播段三形（live-* 帧的投影；id = SSE 完整事件 id，React key 白拿）。 */
export type LiveSegment =
  | { kind: "text"; id: string; text: string }
  | { kind: "action"; id: string; action: string }
  | { kind: "step"; id: string; step: number };

type ProjectLive = {
  /** 段所属 run（换 run 即重开的锚）。 */
  runId: string;
  segments: LiveSegment[];
};

export type LiveState = {
  lives: Record<string, ProjectLive>;
  /** 桥写入口：换 run 重开；连续同文动作行去重；总量软上限。 */
  noteLiveSegment: (projectId: string, runId: string, segment: LiveSegment) => void;
};

/** 段数软上限（重放缓冲 ~1000 帧的投影，内存有界）。 */
const MAX_SEGMENTS = 300;

type SetFn = (partial: Partial<LiveState>) => void;

function updateLive(
  set: SetFn,
  projectId: string,
  runId: string,
  append: (segments: LiveSegment[]) => LiveSegment[],
): void {
  const state = useLiveStore.getState();
  const current = state.lives[projectId];
  // 换 run 即重开：旧 run 段不残留（收起即逝的另一面）
  const base = current?.runId === runId ? current.segments : [];
  const next = append(base);
  if (next === base) return;
  set({ lives: { ...state.lives, [projectId]: { runId, segments: next } } });
}

export const useLiveStore = create<LiveState>((set) => ({
  lives: {},

  noteLiveSegment: (projectId, runId, segment) =>
    updateLive(set, projectId, runId, (base) => {
      // 连续同文动作行去重：反复编写/触达同一文件的噪音折叠为一行
      const last = base[base.length - 1];
      if (
        segment.kind === "action" &&
        last?.kind === "action" &&
        last.action === segment.action
      ) {
        return base;
      }
      const merged = [...base, segment];
      return merged.length > MAX_SEGMENTS
        ? merged.slice(merged.length - MAX_SEGMENTS)
        : merged;
    }),
}));

/** 空段共享常量（selector 引用稳定——`?? []` 每次新引用会让订阅面无限重渲染）。 */
const NO_SEGMENTS: LiveSegment[] = [];

/** 项目当前直播段（未见 = 空数组，引用稳定）。 */
export function liveSegmentsOf(
  state: Pick<LiveState, "lives">,
  projectId: string,
): LiveSegment[] {
  return state.lives[projectId]?.segments ?? NO_SEGMENTS;
}
