import { create } from "zustand";

/**
 * 项目级瞬时通知（展示性载荷，issue #19 / spec 0002 §6）：SSE 载荷中 REST 重查
 * 拿不到的字段（驳回理由、预览有更新信号）经白名单进入本 store 页内呈现。
 * 桥（src/lib/sse/bridge.ts）是唯一事件写入方；UI 只读 + 主动确认
 * （关横幅 / 点刷新）。模式同 agent-streams store；正确性仍以 REST 重查为准。
 */

/** 驳回理由（stage-changed · rejected:true 携带 reason；REST 详情无此字段）。 */
export type StageRejectionNotice = {
  /** 驳回发生时的段展示名（信封 stageLabel）。 */
  stageLabel: string;
  /** 驳回意见，原样（必填后才入 store）。 */
  reason: string;
};

export type ProjectNotice = {
  rejection?: StageRejectionNotice;
  /** preview-ready 置位；用户点「点击刷新」即确认清除（重挂 iframe 不自动）。 */
  previewUpdate?: boolean;
  /** document-updated 置位（#54）；对话区提示胶囊，用户点胶囊即确认清除。 */
  documentUpdate?: boolean;
};

export type ProjectNoticesState = {
  /** 键 = projectId。 */
  notices: Record<string, ProjectNotice>;
  /** 插入序，驱逐用（同 agent-streams）。 */
  order: string[];
  setRejection: (projectId: string, notice: StageRejectionNotice) => void;
  /** 再次拍板通过（approved）或用户关闭横幅时清除。 */
  clearRejection: (projectId: string) => void;
  markPreviewUpdate: (projectId: string) => void;
  ackPreviewUpdate: (projectId: string) => void;
  markDocumentUpdate: (projectId: string) => void;
  ackDocumentUpdate: (projectId: string) => void;
};

/** 项目数软上限：瞬时展示态，留足最近浏览的项目即可。 */
const MAX_PROJECTS = 20;

type SetFn = (partial: Partial<ProjectNoticesState>) => void;

/** 就地单项目变更：维持插入序，超软上限驱逐最旧项目的整条通知。 */
function withProject(
  set: SetFn,
  projectId: string,
  mutate: (notice: ProjectNotice) => ProjectNotice,
): void {
  const state = useProjectNoticesStore.getState();
  const current = state.notices[projectId] ?? {};
  const next = mutate(current);
  if (next === current) return;
  const notices = { ...state.notices, [projectId]: next };
  let order = state.order;
  if (!(projectId in state.notices)) {
    order = [...order, projectId];
    while (order.length > MAX_PROJECTS) {
      const evicted = order.shift();
      if (evicted !== undefined) delete notices[evicted];
    }
  }
  if (
    next.rejection === undefined &&
    next.previewUpdate === undefined &&
    next.documentUpdate === undefined &&
    projectId in state.notices
  ) {
    // 变回空通知：连同键与序位一起撤掉，store 不留空壳
    delete notices[projectId];
    order = order.filter((id) => id !== projectId);
  }
  set({ notices, order });
}

export const useProjectNoticesStore = create<ProjectNoticesState>((set) => ({
  notices: {},
  order: [],
  setRejection: (projectId, notice) =>
    withProject(set, projectId, (n) => ({ ...n, rejection: notice })),
  clearRejection: (projectId) =>
    withProject(set, projectId, (n) => (n.rejection === undefined ? n : { ...n, rejection: undefined })),
  markPreviewUpdate: (projectId) =>
    withProject(set, projectId, (n) => (n.previewUpdate ? n : { ...n, previewUpdate: true })),
  ackPreviewUpdate: (projectId) =>
    withProject(set, projectId, (n) =>
      n.previewUpdate === undefined ? n : { ...n, previewUpdate: undefined },
    ),
  markDocumentUpdate: (projectId) =>
    withProject(set, projectId, (n) => (n.documentUpdate ? n : { ...n, documentUpdate: true })),
  ackDocumentUpdate: (projectId) =>
    withProject(set, projectId, (n) =>
      n.documentUpdate === undefined ? n : { ...n, documentUpdate: undefined },
    ),
}));
