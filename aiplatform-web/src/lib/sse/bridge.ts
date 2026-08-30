import type { QueryClient } from "@tanstack/react-query";

import { queryKeys } from "@/lib/api/keys";
import { useAgentStreamsStore } from "@/lib/store/agent-streams";
import { useProjectNoticesStore } from "@/lib/store/project-notices";

import type { SseEvent } from "./connection";
import {
  asNotificationEvent,
  asPassthroughAgentEvent,
  asPlatformAgentEvent,
  parseSseEnvelope,
  type NotificationEvent,
  type PlatformAgentEvent,
} from "./events";

/**
 * 事件 → 状态桥（ADR 0003）：
 * - 通知通道 = 声明式失效注册表，事件 type → 失效 key 前缀列表，粗粒度、不写业务 switch；
 * - agent 通道 = 事件 → agent-streams store 分发（本文件是 store 唯一写入方）。
 * 事件只让 UI 活、不承担正确性：终态事件同样只 invalidate，正确性永远走 REST。
 */

/**
 * 通知事件 → 粗粒度失效前缀。键类型锁死为名册穷尽：正本新增 type 而
 * events.ts / 此处漏登，typecheck 即红（对接 issue 时同步维护）。
 */
const NOTIFICATION_INVALIDATIONS = {
  "workspace-created": [queryKeys.projects.all],
  "stage-changed": [queryKeys.projects.all, queryKeys.todos.all],
  // tasks 域连带：OPC 任务卡片 / 详情的 previewUrl 只能靠任务端点重拉点亮（#22）
  "preview-ready": [queryKeys.projects.all, queryKeys.tasks.all],
  "workspace-destroyed": [queryKeys.projects.all],
  "task-updated": [queryKeys.tasks.all, queryKeys.projects.all, queryKeys.todos.all],
  // 工作文档产物重拉（#54）+ 门就绪连带（#58）：PRD 内容与更新时间在本域；
  // gate.ready 藏在 projects 详情，PRD 写出瞬间一并重拉，门卡 /「等你」徽章即时就位
  "document-updated": [queryKeys.documents.all, queryKeys.projects.all],
} as const satisfies Record<NotificationEvent["type"], readonly (readonly unknown[])[]>;

/**
 * agent 流平台事件 → 失效前缀（issue #21）：等待点开关即待办增删（AGENT_WAIT
 * 型），与 streams store 分发并存——同通知注册表形态，粗粒度、正确性走 REST。
 * agent 通道工作台建连（ADR 0003），未挂连接期间待办实时性由门控轮询兜底。
 */
const AGENT_EVENT_INVALIDATIONS: Partial<
  Record<PlatformAgentEvent["type"], readonly (readonly unknown[])[]>
> = {
  // 等待点开关 = 待办增删 + 项目 waits 域（pending 列表）刷新（#45）
  "wait-raised": [queryKeys.todos.all, queryKeys.projects.all],
  "wait-settled": [queryKeys.todos.all, queryKeys.projects.all],
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

/**
 * 载荷展示白名单（ADR 0003 修订例外，spec 0002 §6）：仅这些事件把**REST 重查
 * 拿不到的载荷**写入轻量 store 页内呈现（驳回理由 / 预览有更新信号）；其余
 * 事件一律只失效。桥仍是 store 唯一事件写入方；正确性以 REST 重查为准。
 */
const NOTIFICATION_PAYLOAD_WRITERS: Partial<
  Record<NotificationEvent["type"], (event: NotificationEvent) => void>
> = {
  // 注册表按 type 键派发，写入方内的判别守卫仅为编译期收窄（键即类型，不会走错分支）
  "stage-changed": (event) => {
    if (event.type !== "stage-changed") return;
    const { payload } = event;
    const store = useProjectNoticesStore.getState();
    if (payload.rejected === true && payload.reason) {
      store.setRejection(payload.projectId, {
        stageLabel: payload.stageLabel,
        reason: payload.reason,
      });
    } else if (payload.approved === true) {
      // 再次拍板通过：驳回理由不再相关，随批清掉
      store.clearRejection(payload.projectId);
    }
  },
  "preview-ready": (event) => {
    if (event.type !== "preview-ready") return;
    useProjectNoticesStore.getState().markPreviewUpdate(event.payload.projectId);
  },
  // PRD 写出/修订落定（#54）：「有更新」信号 REST 重查拿不到，置位驱动对话区胶囊
  "document-updated": (event) => {
    if (event.type !== "document-updated") return;
    useProjectNoticesStore.getState().markDocumentUpdate(event.payload.projectId);
  },
};

/** 已知引擎透传名型 → 直入分段 kind（名册「通道二」引擎透传行；未知名型走 passthrough 段）。 */
const PASSTHROUGH_SEGMENT_KINDS: Record<string, "text" | "reasoning" | "patch" | "tool"> = {
  text: "text",
  reasoning: "reasoning",
  patch: "patch",
  tool: "tool",
};

/** agent 流事件 → streams store（分段 id = SSE 完整事件 id，React key 白拿）。 */
export function dispatchAgentEvent(queryClient: QueryClient, event: SseEvent): void {
  const envelope = parseSseEnvelope(event.data);
  if (!envelope) return;
  const store = useAgentStreamsStore.getState();

  const platform = asPlatformAgentEvent(envelope);
  if (platform) {
    for (const queryKey of AGENT_EVENT_INVALIDATIONS[platform.type] ?? []) {
      void queryClient.invalidateQueries({ queryKey });
    }
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
          stage: payload.stage,
          engine: payload.engine,
        });
        return;
      }
      case "knowledge-retrieved": {
        const { payload } = platform;
        store.appendSegment(payload, {
          kind: "knowledge",
          id: event.id,
          items: payload.items,
        });
        return;
      }
      case "wait-raised": {
        const { payload } = platform;
        store.appendSegment(payload, {
          kind: "wait",
          id: event.id,
          waitId: payload.waitId,
          waitKind: payload.kind,
          summary: payload.summary,
        });
        return;
      }
      case "wait-settled": {
        const { payload } = platform;
        store.appendSegment(payload, {
          kind: "wait-settled",
          id: event.id,
          waitId: payload.waitId,
          outcome: payload.outcome,
        });
        return;
      }
      case "error": {
        const { payload } = platform;
        store.appendSegment(payload, {
          kind: "error",
          id: event.id,
          message: payload.message,
        });
        return;
      }
      case "task-finish": {
        const { payload } = platform;
        store.appendSegment(payload, {
          kind: "finish",
          id: event.id,
          finish: payload.finish,
        });
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
  } else {
    // 引擎透传未知名型：data 原样入段，Feed 兜底呈现
    store.appendSegment(payload, {
      kind: "passthrough",
      id: event.id,
      type: passthrough.type,
      data: payload.data,
    });
  }
}
