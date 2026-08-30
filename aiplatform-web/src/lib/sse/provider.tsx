"use client";

import { useQueryClient } from "@tanstack/react-query";
import { useEffect, type ReactNode } from "react";

import { useSseStatusStore, type SseChannel, type SseStatus } from "@/lib/store/sse-status";

import { dispatchNotificationEvent } from "./bridge";
import { probeSessionAlive, SseConnection } from "./connection";

/**
 * 通知通道（`/api/events`）的 root 级挂载点（ADR 0003）：登录后单例常开、
 * 缺省全量不过滤，切门户不断线；每标签页天然各持一条。agent 流通道不在此挂——
 * 工作台 mount 建连、unmount 即断，首个挂载方 = 项目工作台页（agent-channel.tsx，#23）。
 */
export function SseProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();

  useEffect(() => {
    let conn: SseConnection | null = null;
    let cancelled = false;
    // 会话守卫：未登录不建连。探 /api/me——401 时薄 client 的全局 401 出口已
    // 接管整页跳，此处保持沉默；其余结果（200；404 = 后端 A2 未上；网络错）
    // 按已登录建连，交给连接层的失败计数兜底。#17 落地 useMe 后改由其守卫。
    void probeSessionAlive().then((alive) => {
      if (!alive || cancelled) return;
      conn = new SseConnection({
        channel: "notification",
        url: "/api/events",
        onEvent: (event) => dispatchNotificationEvent(queryClient, event),
        // 重连成功 → 广谱 invalidate：当前挂载 query 自然重拉，粗对齐零风险
        onReconnect: () => {
          void queryClient.invalidateQueries();
        },
      });
      conn.connect();
    });
    return () => {
      cancelled = true;
      conn?.close();
    };
  }, [queryClient]);

  return children;
}

/** SSE 层对 React 的唯一状态读口（ADR 0003）：按通道读 connected/connecting/offline。 */
export function useSseStatus(channel: SseChannel): SseStatus {
  return useSseStatusStore((state) => state.statuses[channel]);
}

/**
 * 门控轮询兜底（issue #16）：活页面 query 挂到 `refetchInterval`——
 * 对应通道 ≠ connected 才给 15s，连接健康时不空转。列表类看通知通道、
 * 工作台看 agent 通道：
 *
 * ```ts
 * useQuery({ queryKey: queryKeys.projects.all, refetchInterval: useSseFallbackPolling("notification") })
 * ```
 */
export function useSseFallbackPolling(
  channel: SseChannel,
  intervalMs = 15_000,
): number | false {
  return useSseStatus(channel) === "connected" ? false : intervalMs;
}
