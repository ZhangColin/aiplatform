/**
 * SSE 事件名册的手写镜像（判别联合 + 信封解析）。
 *
 * 正本 = aiplatform-server `docs/spec/SSE事件清单.md`（SSE 不进 swagger，这是唯一可行的
 * 类型来源，ADR 0003）。字段形状镜像正本字段表；**每个 SSE 对接 issue 到达时对照正本
 * 更新本文件**，勿凭记忆改。payload 为线上数据，收窄函数只按 type 字符串判别、字段
 * 直接信任转型（同源本地后端）；缺字段的容错呈现归消费端。
 */

/** 两通道统一信封：`data = {type, payload, ts}`（正本「信封」节）。 */
export type SseEnvelope = {
  type: string;
  payload: Record<string, unknown>;
  /** ISO-8601；消费层目前不依赖，解析时不校验。 */
  ts: string;
};

export function parseSseEnvelope(raw: string): SseEnvelope | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }
  if (typeof parsed !== "object" || parsed === null) return null;
  const { type, payload, ts } = parsed as Record<string, unknown>;
  if (typeof type !== "string" || typeof payload !== "object" || payload === null) {
    return null;
  }
  return { type, payload: payload as Record<string, unknown>, ts: typeof ts === "string" ? ts : "" };
}

// ── 通道一：平台通知（`GET /api/events`，封闭集合）──────────────────────────
// 字段表镜像正本「通道一」；正本更新时同步改这里。

export type NotificationEvent =
  | {
      type: "workspace-created";
      payload: {
        projectId: string;
        projectName: string;
        container: string;
        projectType: string;
        engine: string;
      };
    }
  | {
      type: "stage-changed";
      payload: {
        projectId: string;
        stage: string;
        stageLabel: string;
        approved?: boolean;
        rejected?: boolean;
        /** 驳回时携带。 */
        reason?: string;
      };
    }
  | { type: "preview-ready"; payload: { projectId: string; url: string } }
  | { type: "workspace-destroyed"; payload: { projectId: string } }
  | { type: "task-updated"; payload: { projectId: string; taskId: string; status: string } }
  | {
      /** 工作区文档产物写出/修订落定（#54）；v1 唯一写入方 = BA 的 savePrd。 */
      type: "document-updated";
      payload: { projectId: string; documentType: string };
    };

const NOTIFICATION_TYPES: ReadonlySet<string> = new Set([
  "workspace-created",
  "stage-changed",
  "preview-ready",
  "workspace-destroyed",
  "task-updated",
  "document-updated",
] satisfies Array<NotificationEvent["type"]>);

/** 通道一为封闭集合：名册外 type → null（桥按 miss 忽略）。 */
export function asNotificationEvent(envelope: SseEnvelope): NotificationEvent | null {
  return NOTIFICATION_TYPES.has(envelope.type)
    ? (envelope as unknown as NotificationEvent)
    : null;
}

// ── 通道二：agent 流（`GET /api/agent-events`）─────────────────────────────
// 平台事件 = 封闭集合（字段扁平，下表为准）；引擎透传 = 开放集合，`data` 为
// 引擎 part 原样（字段表初版，随片 2 / 片 5 spec 细化——正本注）。

/** agent 流 payload 的公共关联字段：必带 projectId + runId，sessionId 建立后携带。 */
type AgentPayload = {
  projectId: string;
  runId: string;
  sessionId?: string;
};

export type PlatformAgentEvent =
  | {
      type: "task-start";
      payload: AgentPayload & { prompt: string; model: string; engine?: string };
    }
  | {
      type: "role-assigned";
      payload: AgentPayload & { role: string; roleLabel: string; stage: string; engine: string };
    }
  | {
      type: "knowledge-retrieved";
      payload: AgentPayload & {
        items: Array<{ kind: string; projectName: string; title: string; snippet?: string }>;
      };
    }
  | { type: "session-created"; payload: AgentPayload & { sessionId: string; engine?: string } }
  | { type: "error"; payload: AgentPayload & { message: string } }
  | { type: "task-finish"; payload: AgentPayload & { sessionId: string; finish: string } }
  | {
      type: "wait-raised";
      payload: AgentPayload & { waitId: string; kind: string; summary: string };
    }
  | {
      type: "wait-settled";
      payload: AgentPayload & { waitId: string; outcome: string };
    };

const PLATFORM_AGENT_TYPES: ReadonlySet<string> = new Set([
  "task-start",
  "role-assigned",
  "knowledge-retrieved",
  "session-created",
  "error",
  "task-finish",
  "wait-raised",
  "wait-settled",
] satisfies Array<PlatformAgentEvent["type"]>);

const PASSTHROUGH_AGENT_TYPES: ReadonlySet<string> = new Set([
  "text",
  "reasoning",
  "patch",
  "tool",
  "step-start",
  "step-finish",
]);

/** 引擎透传（开放集合）：已知名型 text / reasoning / patch / tool / step-start / step-finish，data = part 原样。 */
export type PassthroughAgentEvent = {
  /** 名册列已知名型，开放集合不限于它们。 */
  type: string;
  payload: AgentPayload & { data: unknown };
};

/**
 * 通道二收窄（两个函数而非一个判别联合）：透传 `type: string` 若并入联合会与
 * 平台事件字面量重叠、破坏 switch 收窄，故平台 / 透传各自收窄。透传是开放
 * 集合——带 `data` 的未知 type 同样收窄为透传，不返回 null。
 */
export function asPlatformAgentEvent(envelope: SseEnvelope): PlatformAgentEvent | null {
  return PLATFORM_AGENT_TYPES.has(envelope.type)
    ? (envelope as unknown as PlatformAgentEvent)
    : null;
}

export function asPassthroughAgentEvent(envelope: SseEnvelope): PassthroughAgentEvent | null {
  if (PLATFORM_AGENT_TYPES.has(envelope.type)) return null;
  // 已知名型缺 data 也照收（字段表初版，防漂移丢事件）；未知 type 以携带 data 为准
  return PASSTHROUGH_AGENT_TYPES.has(envelope.type) || "data" in envelope.payload
    ? (envelope as unknown as PassthroughAgentEvent)
    : null;
}
