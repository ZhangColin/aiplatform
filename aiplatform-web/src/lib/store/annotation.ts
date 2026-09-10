import { create } from "zustand";

import type { AnnotationDraft } from "@/lib/preview/annotation";

/**
 * 圈注条目 store（#97 圈注 B 档，SSE 相关之外的一次性 UI 状态——Zustand 状态
 * 三分法）：按项目累积「发送前」的圈注条目（预览上选择/圈选的产物，发往发送框
 * 附件区）。系统面板（收预览 iframe 的 postMessage 锚）写入、发送框消费（chip
 * 呈现 + 删）、发送即清。发送前可删（remove）——评语入口已随 #135 chip 序号化
 * 撤除，描述统一写主输入框。
 */

/** 一条圈注条目（draft 载荷 + 本地 id——React key 与删除锚）。 */
export type AnnotationItem = AnnotationDraft & { id: string };

export type AnnotationState = {
  annotations: Record<string, AnnotationItem[]>;
  /** 收一条圈注（预览回传锚 → 入发送框附件区），返回本地 id。 */
  add: (projectId: string, draft: AnnotationDraft) => void;
  /** 发送前删除一条。 */
  remove: (projectId: string, id: string) => void;
  /** 发送即清（附件随消息发出，不再滞留）。 */
  clear: (projectId: string) => void;
};

let seq = 0;
function localId(): string {
  seq += 1;
  return `an${seq}`;
}

export const useAnnotationStore = create<AnnotationState>((set) => ({
  annotations: {},

  add: (projectId, draft) =>
    set((state) => ({
      annotations: {
        ...state.annotations,
        [projectId]: [...(state.annotations[projectId] ?? []), { ...draft, id: localId() }],
      },
    })),

  remove: (projectId, id) =>
    set((state) => ({
      annotations: {
        ...state.annotations,
        [projectId]: (state.annotations[projectId] ?? []).filter((item) => item.id !== id),
      },
    })),

  clear: (projectId) =>
    set((state) => ({
      annotations: { ...state.annotations, [projectId]: [] },
    })),
}));
