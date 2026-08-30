"use client";

import { useQueryClient } from "@tanstack/react-query";
import { useEffect } from "react";

import { dispatchAgentEvent } from "./bridge";
import { probeSessionAlive, SseConnection } from "./connection";

/**
 * agent 流通道（`/api/agent-events?projectId=`）的首个挂载方（ADR 0003 连接拓扑，
 * issue #23）：工作台 mount 建连、unmount 即断；`dispatchAgentEvent` 接为 message
 * handler（streams store 分发 + agent 侧失效注册表）。
 *
 * 形态同构通知通道 provider：会话守卫（未登录不建连）+ probe-cancelled 幂等——
 * StrictMode 双挂载时首个 probe 被 cleanup 作废，全程只建一条连接。去重开
 * （agent 流 append-only，重复一眼可见；键 = 完整事件 id，连接层既有 Set）。
 * 重连成功 → 广谱 invalidate（事件不承担正确性，粗对齐零风险）；已收分段存于
 * streams store，不受断连影响。
 */
export function useAgentStreamChannel(projectId: string) {
  const queryClient = useQueryClient();

  useEffect(() => {
    let conn: SseConnection | null = null;
    let cancelled = false;
    void probeSessionAlive().then((alive) => {
      if (!alive || cancelled) return;
      conn = new SseConnection({
        channel: "agent",
        url: `/api/agent-events?projectId=${encodeURIComponent(projectId)}`,
        dedupe: true,
        onEvent: (event) => dispatchAgentEvent(queryClient, event),
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
  }, [queryClient, projectId]);
}
