import { create } from "zustand";

/** SSE 双通道标识（ADR 0003：通知通道 root 常开 / agent 流通道工作台建连）。 */
export type SseChannel = "notification" | "agent";

/** 传输层三态：connecting = 建连中（含原生自动重连的 CONNECTING）。 */
export type SseStatus = "connecting" | "connected" | "offline";

type SseStatusState = {
  statuses: Record<SseChannel, SseStatus>;
  setStatus: (channel: SseChannel, status: SseStatus) => void;
};

/**
 * SSE 传输层状态（ADR 0003）：connection.ts 唯一写入方；
 * 读方 = 工作台 agent 流指示器 + 门控轮询（useSseFallbackPolling）。
 * 与 agent-streams（过程层）分家的理由见 ADR Considered Options「store 合一 vs 分两个」。
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
