import { create } from "zustand";

/** SSE 连接姿态标识（ADR 0003 + #82 单端点：通知族 root 常开连接 / 智能体事件项目页过滤连接）。 */
export type SseChannel = "notification" | "agent";

/** 传输层三态：connecting = 建连中（含原生自动重连的 CONNECTING）。 */
export type SseStatus = "connecting" | "connected" | "offline";

type SseStatusState = {
  statuses: Record<SseChannel, SseStatus>;
  setStatus: (channel: SseChannel, status: SseStatus) => void;
};

/**
 * SSE 传输层状态（ADR 0003）：connection.ts 唯一写入方；
 * 读方 = 项目页 agent 流指示器 + 门控轮询（useSseFallbackPolling）。
 */
export const useSseStatusStore = create<SseStatusState>((set) => ({
  statuses: { notification: "offline", agent: "offline" },
  setStatus: (channel, status) =>
    set((state) =>
      state.statuses[channel] === status
        ? state
        : { statuses: { ...state.statuses, [channel]: status } },
    ),
}));
