"use client";

import { useQueryClient } from "@tanstack/react-query";
import { useEffect } from "react";

import { dispatchAgentEvent } from "./bridge";
import { probeSessionAlive, SseConnection } from "./connection";

/**
 * 智能体事件连接（单端点单流，#82：`/api/events?projectId=`）的项目页挂载方
 * （ADR 0003 连接拓扑）：mount 建连、unmount 即断；`dispatchAgentEvent` 接为
 * message handler。同一端点上的通知族由站点级常开连接（SseProvider）消费，
 * 此处按项目过滤只收智能体事件族——两连接族内分工，不重复处理。
 *
 * 新连接（无 Last-Event-ID）由服务端补发命中项目过滤的近期智能体缓冲事件
 * （工作消息/对话面重放重建）；断线重连不补发，重连成功 → 广谱 invalidate
 * （事件不承担正确性，粗对齐零风险）。去重开（事件流 append-only，重复一眼
 * 可见；键 = 完整事件 id，连接层既有 Set）。
 */
export function useAgentEventChannel(projectId: string) {
  const queryClient = useQueryClient();

  useEffect(() => {
    let conn: SseConnection | null = null;
    let cancelled = false;
    void probeSessionAlive().then((alive) => {
      if (!alive || cancelled) return;
      conn = new SseConnection({
        channel: "agent",
        url: `/api/events?projectId=${encodeURIComponent(projectId)}`,
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
