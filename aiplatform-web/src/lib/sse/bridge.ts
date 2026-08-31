import type { QueryClient } from "@tanstack/react-query";

import { parseQuestion } from "@/lib/chat/qa";
import { queryKeys } from "@/lib/api/keys";
import { useAgentStreamsStore } from "@/lib/store/agent-streams";
import { useChatStore } from "@/lib/store/chat";
import { usePrdNoticesStore } from "@/lib/store/prd-notices";

import type { SseEvent } from "./connection";
import {
  asNotificationEvent,
  asPassthroughAgentEvent,
  asPlatformAgentEvent,
  parseSseEnvelope,
  type NotificationEvent,
} from "./events";

/**
 * 事件 → 状态桥（ADR 0003）：
 * - 通知通道 = 声明式失效注册表 + 载荷展示白名单（REST 重查拿不到的载荷写轻量
 *   store，本文件是 store 唯一事件写入方）；
 * - agent 通道 = 事件 → agent-streams store（过程层）+ chat store（指令区对话面）分发。
 * 事件只让 UI 活、不承担正确性：终态事件同样只 invalidate，正确性永远走 REST。
 */

/**
 * 通知事件 → 粗粒度失效前缀。键类型锁死为名册穷尽：正本新增 type 而
 * events.ts / 此处漏登，typecheck 即红（对接 issue 时同步维护）。
 */
const NOTIFICATION_INVALIDATIONS = {
  "workspace-created": [queryKeys.projects.all],
  "preview-ready": [queryKeys.projects.all],
  "workspace-destroyed": [queryKeys.projects.all],
  // PRD 内容与更新时间在 documents 域；projects 详情的 prdProducedAt 是成果区
  // 长出判据，写出瞬间一并重拉
  "document-updated": [queryKeys.documents.all, queryKeys.projects.all],
  "project-renamed": [queryKeys.projects.all],
} as const satisfies Record<NotificationEvent["type"], readonly (readonly unknown[])[]>;

/**
 * 载荷展示白名单（ADR 0003 修订例外，#20 修订回路）：仅这些事件把 **REST 重查
 * 拿不到的载荷** 写入轻量 store 页内呈现；其余事件一律只失效。桥仍是 store
 * 唯一事件写入方；正确性以 REST 重查为准。注册表按 type 键派发，写入方内的
 * 判别守卫仅为编译期收窄（键即类型，不会走错分支——关联联合的调用点无法
 * 类型化到键，守卫不是运行时逻辑）。
 */
const NOTIFICATION_PAYLOAD_WRITERS: Partial<
  Record<NotificationEvent["type"], (event: NotificationEvent) => void>
> = {
  // 「这次写入是不是修订」重查拿不到（prd_produced_at 首产/修订同刷新）——
  // 按到达序在 store 里分岔（首产登记 seen、此后置 pending 出胶囊）
  "document-updated": (event) => {
    if (event.type !== "document-updated") return;
    if (event.payload.documentType !== "PRD") return;
    usePrdNoticesStore.getState().notePrdWritten(event.payload.projectId);
  },
};

export function dispatchNotificationEvent(queryClient: QueryClient, event: SseEvent): void {
  const envelope = parseSseEnvelope(event.data);
  if (!envelope) return;
  const notification = asNotificationEvent(envelope);
  if (!notification) return;
  for (const queryKey of NOTIFICATION_INVALIDATIONS[notification.type]) {
    void queryClient.invalidateQueries({ queryKey });
  }
  NOTIFICATION_PAYLOAD_WRITERS[notification.type]?.(notification);
}

/** 已知引擎透传名型 → 直入分段 kind（名册「通道二」引擎透传行；未知名型走 passthrough 段）。 */
const PASSTHROUGH_SEGMENT_KINDS: Record<string, "text" | "reasoning" | "patch" | "tool"> = {
  text: "text",
  reasoning: "reasoning",
  patch: "patch",
  tool: "tool",
};

/** agent 流事件 → streams store + chat store（分段 id = SSE 完整事件 id，React key 白拿）。 */
export function dispatchAgentEvent(event: SseEvent): void {
  const envelope = parseSseEnvelope(event.data);
  if (!envelope) return;
  const store = useAgentStreamsStore.getState();
  const chat = useChatStore.getState();

  const platform = asPlatformAgentEvent(envelope);
  if (platform) {
    switch (platform.type) {
      case "task-start": {
        const { payload } = platform;
        store.startRun({
          runId: payload.runId,
          projectId: payload.projectId,
          prompt: payload.prompt,
          model: payload.model,
          engine: payload.engine,
        });
        chat.ingestTaskStart(payload.projectId, payload.runId, payload.prompt);
        return;
      }
      case "session-created": {
        const { payload } = platform;
        store.markSession(payload, payload.sessionId);
        return;
      }
      case "role-assigned": {
        const { payload } = platform;
        store.appendSegment(payload, {
          kind: "role",
          id: event.id,
          role: payload.role,
          roleLabel: payload.roleLabel,
          engine: payload.engine,
        });
        if (payload.role === "BA") chat.noteBaRun(payload.projectId, payload.runId);
        return;
      }
      case "wait-raised": {
        const { payload } = platform;
        store.appendSegment(payload, {
          kind: "wait",
          id: event.id,
          waitKind: payload.kind,
          summary: payload.summary,
        });
        const question = parseQuestion(event.id, payload);
        if (question) chat.raiseQuestion(payload.projectId, payload.sessionId, question);
        return;
      }
      case "error": {
        const { payload } = platform;
        store.appendSegment(payload, {
          kind: "error",
          id: event.id,
          message: payload.message,
        });
        chat.noteTurnError(payload.projectId, payload.runId, payload.message, event.id);
        return;
      }
      case "task-finish": {
        const { payload } = platform;
        store.appendSegment(payload, {
          kind: "finish",
          id: event.id,
          finish: payload.finish,
        });
        chat.finishTurn(payload.projectId, payload.sessionId);
        return;
      }
    }
  }

  const passthrough = asPassthroughAgentEvent(envelope);
  if (!passthrough) return;
  const { payload } = passthrough;
  if (passthrough.type === "step-start" || passthrough.type === "step-finish") {
    store.appendSegment(payload, {
      kind: "step",
      id: event.id,
      phase: passthrough.type === "step-start" ? "start" : "finish",
      data: payload.data,
    });
    return;
  }
  const kind = PASSTHROUGH_SEGMENT_KINDS[passthrough.type];
  if (kind) {
    store.appendSegment(payload, { kind, id: event.id, data: payload.data });
    // 指令区对话面只收 BA 的文本增量（思考/补丁/工具帧不进对话）
    if (kind === "text") {
      const delta = asRecord(payload.data)?.delta;
      chat.appendBaDelta(payload.projectId, payload.sessionId, delta, event.id);
    }
  } else {
    // 引擎透传未知名型：data 原样入段，呈现层兜底
    store.appendSegment(payload, {
      kind: "passthrough",
      id: event.id,
      type: passthrough.type,
      data: payload.data,
    });
  }
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null
    ? (value as Record<string, unknown>)
    : null;
}
