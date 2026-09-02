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
      };
    }
  | { type: "preview-ready"; payload: { projectId: string; url: string } }
  | {
      /**
       * 预览内容前移一步（#49 逐修改刷新）：编码 run 每完成一次完整修改（直播
       * 步骤边界）且平台侧探活通过后发射——前端节流重载预览（秒级最小间隔）；
       * 不带 url（预览地址经 REST 取得且不变）。
       */
      type: "preview-updated";
      payload: { projectId: string };
    }
  | { type: "workspace-destroyed"; payload: { projectId: string } }
  | {
      /** 工作区文档产物写出/修订落定；v1 唯一写入方 = BA 的 savePrd。 */
      type: "document-updated";
      payload: { projectId: string; documentType: string };
    }
  | {
      /** 异步取名落库成功顶替占位名后发射；失败保占位不发。 */
      type: "project-renamed";
      payload: { projectId: string; projectName: string };
    }
  | {
      /**
       * 订单状态变化（#30）：下单（status=1）/首次报价（2）/取消（5）/支付完成
       * 归档（4）各发一次，改价不发；消费 = toast（点击直达项目页）+ 失效重查。
       */
      type: "order-status-changed";
      payload: {
        projectId: string;
        orderId: string;
        status: number;
        statusName: string;
      };
    };

const NOTIFICATION_TYPES: ReadonlySet<string> = new Set([
  "workspace-created",
  "preview-ready",
  "preview-updated",
  "workspace-destroyed",
  "document-updated",
  "project-renamed",
  "order-status-changed",
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
      type: "run-start";
      payload: AgentPayload & { prompt: string; model: string; engine?: string };
    }
  | {
      type: "role-assigned";
      payload: AgentPayload & { role: string; roleLabel: string; engine: string };
    }
  | { type: "run-created"; payload: AgentPayload & { sessionId: string; engine?: string } }
  | { type: "error"; payload: AgentPayload & { message: string } }
  | { type: "run-finish"; payload: AgentPayload & { sessionId: string; finish: string } }
  | {
      /**
       * 智能体挂起（ask_user 提问等）：答复续跑归业务编排（问答作答通道，需求环）。
       * `engineRef` = 引擎侧请求/权限 id（续跑批复的锚）；`data` = 引擎载荷原样
       * （QUESTION 时含前端问答卡投影 `data.questions`），问答卡切片消费。
       */
      type: "question-raised";
      payload: AgentPayload & {
        kind: string;
        summary: string;
        engineRef?: string;
        data?: unknown;
      };
    }
  | {
      /**
       * 编码 run 自动重试（生成编排层发射，#22）：`runId` 锚定失败的那次尝试
       * （帧序 error → run-retrying → 下一尝试 run-start）；`message` 为用户侧
       * 话术「遇到问题，正在重试」；超限后不再发（末次 error 即终态）。
       */
      type: "run-retrying";
      payload: AgentPayload & { attempt: number; message: string };
    }
  | {
      /**
       * 修正 run 收口·系统未动（#46）：编码智能体以 finish_fix(changed=false) 判定
       * 无需改动——`reason` 为未动原因；帧序 run-finish → fix-unchanged；changed=true
       * 不发。指令区呈现「系统未修改 + 原因」，区分「不需要改」与「链路断了」。
       */
      type: "fix-unchanged";
      payload: AgentPayload & { reason: string };
    }
  | {
      /**
       * 兜底轻引导回复（#47 入口三分类）：非意见非咨询输入的平台侧定型文案——
       * 零产物路径（不起任何智能体 run，本帧即该次派发的全部帧）；`prompt` 为
       * 锚定的用户输入（重放重建对话面）；`label` 为呈现标签（「平台」）；
       * `text` 为引导文案（下单意图引导到「确认下单」）。
       */
      type: "guide-reply";
      payload: AgentPayload & { prompt: string; label: string; text: string };
    }
  | {
      /** 直播·智能体自述解说段（#23，编码 run 专属）：`text` 为完整段非增量（服务端逐段成型）。 */
      type: "live-text";
      payload: AgentPayload & { text: string };
    }
  | {
      /** 直播·动作摘要行（#23）：工具动作 → 人话（如「正在编写【订单管理】」）。 */
      type: "live-action";
      payload: AgentPayload & { action: string };
    }
  | {
      /** 直播·步骤段（#23）：run 内步骤序号（1 起），呈现为「第 N 步」分隔。 */
      type: "live-step";
      payload: AgentPayload & { step: number };
    };

const PLATFORM_AGENT_TYPES: ReadonlySet<string> = new Set([
  "run-start",
  "role-assigned",
  "run-created",
  "error",
  "run-finish",
  "question-raised",
  "run-retrying",
  "fix-unchanged",
  "guide-reply",
  "live-text",
  "live-action",
  "live-step",
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
