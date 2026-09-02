import { create } from "zustand";

/**
 * 生成面 store（#22 片2-1，SSE 相关 store——ADR 0003 状态三分法）：按项目记编码
 * run 的判定锚与状态机 + 预览重挂纪元 + 重试话术。写入方两路（同 chat store 先例）：
 * SSE 侧 = 桥（事件）；发送侧 = use-generate（发起成功的乐观登记，SSE 帧随后
 * 到达并幂等收敛）。
 *
 * <p>与 agent-streams 的分工：streams 是「过程层最近 1 run」（BA/编码互逐），
 * 本 store 是「生成事实」——编码 run 的 runId 登记（role-assigned role=CODER，
 * 后续 run-start/finish/error 帧不带角色，凭登记判定）与跨 run 的状态，BA 轮
 * 不挤掉。刷新后由 agent 流通道重放缓冲重建（生成中回页可续看状态）。</p>
 *
 * <p><b>预览重挂纪元</b>：两路信号共一套机制——编码 run 收口（run-finish，事件
 * id 去重防重放重复计）与逐修改刷新（preview-updated 通知，#49——平台侧已按
 * 「步骤完成 + 探活通过」门控，前端只做节流：秒级最小间隔内的连续通知合并丢弃，
 * 最终态由收口纪元兜底）各自 +1，SystemPanel 以 url+epoch 为 iframe key。通知
 * 通道永不补发，preview-updated 无需事件 id 去重。</p>
 */

/** 逐修改刷新的最小重载间隔（秒级，防闪烁；正步间隔远大于此，合并只在尖峰生效）。 */
export const PREVIEW_REFRESH_MIN_INTERVAL_MS = 3000;

/** 编码 run 状态（生成面视角；重试中的 run 视为仍在生成）。 */
export type CoderRunStatus = "running" | "retrying" | "finished" | "error";

type ProjectGeneration = {
  /** role-assigned(role=CODER) 登记的编码 run（后续无角色帧的判定锚，有界）。 */
  coderRunIds: string[];
  /** 最近一次编码 run 的状态；undefined = 本会话未见编码 run。 */
  coderStatus?: CoderRunStatus;
  /** 重试话术（run-retrying 帧下发，用户侧文案正本在服务端）。 */
  retryMessage?: string;
  /** 预览重挂纪元（编码 run 每次收口 +1，重放去重）。 */
  previewEpoch: number;
  /** 已计过纪元的 run-finish 事件 id（重放去重锚，有界）。 */
  seenFinishEventIds: string[];
  /** 上次逐修改刷新计纪元的时点（节流窗锚，桥传入）。 */
  previewReloadAt?: number;
};

export type GenerationState = {
  generations: Record<string, ProjectGeneration>;
  // ---- SSE 侧（bridge 唯一写入方） ----
  /** role-assigned(role=CODER)：登记编码 run。 */
  noteCoderRun: (projectId: string, runId: string) => void;
  /** 编码 run 的 run-start：状态 → running。 */
  noteCoderRunStart: (projectId: string) => void;
  /** run-retrying（锚定失败的尝试）：状态 → retrying + 记帧内话术。 */
  noteCoderRetrying: (projectId: string, message?: string) => void;
  /** 编码 run 的 run-finish：状态 → finished + 预览纪元 +1（事件 id 去重）。 */
  noteCoderFinish: (projectId: string, eventId: string) => void;
  /** 编码 run 的 error：状态 → error（无后续 run-retrying 即超限终态）。 */
  noteCoderError: (projectId: string) => void;
  /**
   * 预览内容前移（#49 preview-updated 通知）：节流后计预览纪元——间隔内的连续
   * 通知合并丢弃（不闪烁），重载由下一次出窗通知或收口纪元兜底。
   */
  notePreviewUpdated: (projectId: string, now: number) => void;
};

/** runId 登记集软上限（agent 流重放缓冲 ~1000 帧）。 */
const MAX_RUN_IDS = 100;
/** 事件 id 去重集软上限。 */
const MAX_EVENT_IDS = 500;

const emptyGeneration: ProjectGeneration = {
  coderRunIds: [],
  previewEpoch: 0,
  seenFinishEventIds: [],
};

function generationOf(
  state: Pick<GenerationState, "generations">,
  projectId: string,
): ProjectGeneration {
  return state.generations[projectId] ?? emptyGeneration;
}

function pushCapped(list: string[], id: string): string[] {
  if (list.includes(id)) return list;
  const next = [...list, id];
  return next.length > MAX_RUN_IDS ? next.slice(next.length - MAX_RUN_IDS) : next;
}

type SetFn = (partial: Partial<GenerationState>) => void;

function updateGeneration(
  set: SetFn,
  projectId: string,
  mutate: (generation: ProjectGeneration) => ProjectGeneration,
): void {
  const state = useGenerationStore.getState();
  const current = generationOf(state, projectId);
  const next = mutate(current);
  if (next === current) return;
  set({ generations: { ...state.generations, [projectId]: next } });
}

export const useGenerationStore = create<GenerationState>((set) => ({
  generations: {},

  noteCoderRun: (projectId, runId) =>
    updateGeneration(set, projectId, (generation) => {
      if (generation.coderRunIds.includes(runId)) return generation;
      return { ...generation, coderRunIds: pushCapped(generation.coderRunIds, runId) };
    }),

  noteCoderRunStart: (projectId) =>
    updateGeneration(set, projectId, (generation) =>
      generation.coderStatus === "running" ? generation : { ...generation, coderStatus: "running" },
    ),

  noteCoderRetrying: (projectId, message) =>
    updateGeneration(set, projectId, (generation) =>
      generation.coderStatus === "retrying" && generation.retryMessage === message
        ? generation
        : { ...generation, coderStatus: "retrying", retryMessage: message },
    ),

  noteCoderFinish: (projectId, eventId) =>
    updateGeneration(set, projectId, (generation) => {
      if (generation.seenFinishEventIds.includes(eventId)) {
        // 重放已计过：状态收敛即可，纪元不再递增（iframe 不因回页重挂）
        return generation.coderStatus === "finished"
          ? generation
          : { ...generation, coderStatus: "finished" };
      }
      const seen = [...generation.seenFinishEventIds, eventId];
      return {
        ...generation,
        coderStatus: "finished",
        previewEpoch: generation.previewEpoch + 1,
        seenFinishEventIds:
          seen.length > MAX_EVENT_IDS ? seen.slice(seen.length - MAX_EVENT_IDS) : seen,
      };
    }),

  noteCoderError: (projectId) =>
    updateGeneration(set, projectId, (generation) =>
      generation.coderStatus === "error" ? generation : { ...generation, coderStatus: "error" },
    ),

  notePreviewUpdated: (projectId, now) =>
    updateGeneration(set, projectId, (generation) => {
      // 节流：距上次刷新不足最小间隔即合并丢弃（连续通知不闪烁；平台侧已按
      // 「步骤完成且探活通过」门控，出窗后下一次通知或收口纪元会带来最新内容）
      if (
        generation.previewReloadAt !== undefined &&
        now - generation.previewReloadAt < PREVIEW_REFRESH_MIN_INTERVAL_MS
      ) {
        return generation;
      }
      return { ...generation, previewEpoch: generation.previewEpoch + 1, previewReloadAt: now };
    }),
}));

/** 编码 run 判定（bridge 侧用：登记过的 runId 即编码 run）。 */
export function isCoderRun(
  state: Pick<GenerationState, "generations">,
  projectId: string,
  runId: string,
): boolean {
  return generationOf(state, projectId).coderRunIds.includes(runId);
}

/** 项目生成面状态（undefined = 本会话未见编码 run，以 REST 的 generatedAt 为准）。 */
export function coderStatusOf(
  state: Pick<GenerationState, "generations">,
  projectId: string,
): CoderRunStatus | undefined {
  return generationOf(state, projectId).coderStatus;
}

/** 重试话术（帧下发；缺省回落本地字面量——帧丢失时的防御位）。 */
export function retryMessageOf(
  state: Pick<GenerationState, "generations">,
  projectId: string,
): string | undefined {
  return generationOf(state, projectId).retryMessage;
}

/** 预览重挂纪元（0 = 本会话无完成信号）。 */
export function previewEpochOf(
  state: Pick<GenerationState, "generations">,
  projectId: string,
): number {
  return generationOf(state, projectId).previewEpoch;
}
