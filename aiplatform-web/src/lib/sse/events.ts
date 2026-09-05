/**
 * SSE 事件名册的手写镜像（判别联合 + 信封解析）。
 *
 * 正本 = aiplatform-server `docs/spec/SSE事件清单.md`（SSE 不进 swagger，这是唯一可行的
 * 类型来源，ADR 0003）。字段形状镜像正本字段表；**每个 SSE 对接 issue 到达时对照正本
 * 更新本文件**，勿凭记忆改。payload 为线上数据，收窄函数只按 type 字符串判别、字段
 * 直接信任转型（同源本地后端）；缺字段的容错呈现归消费端。
 */

/** 单端点单流统一信封：`data = {type, payload, ts}`（正本「信封」节）。 */
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

// ── 平台通知族（单端点单流上的广播族，封闭集合）──────────────────────────────
// 字段表镜像正本「平台通知族」；正本更新时同步改这里。

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
       * 预览内容前移一步（#49 逐修改刷新）：编码 run 每完成一次完整修改（步骤分组
       * 边界）且平台侧探活通过后发射——前端节流重载预览（秒级最小间隔）；
       * 不带 url（预览地址经 REST 取得且不变）。
       */
      type: "preview-updated";
      payload: { projectId: string };
    }
  | { type: "workspace-destroyed"; payload: { projectId: string } }
  | {
      /** 工作区文档产物写出/修订落定；v1 唯一写入方 = 主智能体的 savePrd。 */
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

/** 通知族为封闭集合：名册外 type → null（消费端按 miss 忽略）。 */
export function asNotificationEvent(envelope: SseEnvelope): NotificationEvent | null {
  return NOTIFICATION_TYPES.has(envelope.type)
    ? (envelope as unknown as NotificationEvent)
    : null;
}

// ── 智能体事件族（重放族：新连接补发近期事件）────────────────────────────────
// 平台事件 = 封闭集合（字段扁平，下表为准）；引擎透传 = 开放集合，`data` 为
// 引擎 part 原样。退役五族（role-assigned / run-created / run-retrying /
// fix-unchanged / dispatch-stage / live-*）不进名册——旧事件到达按 miss 忽略。

/** 智能体事件 payload 的公共关联字段：必带 projectId + runId，sessionId 建立后携带。 */
type AgentPayload = {
  projectId: string;
  runId: string;
  sessionId?: string;
};

export type PlatformAgentEvent =
  | {
      /** 运行开始：一场 run 恰一次（#84 静默重试——编码 run 重试不新发，用户面
       * run 身份 = 首试 runId 全程不变）。 */
      type: "run-start";
      payload: AgentPayload & {
        prompt: string;
        model: string;
        engine?: string;
        /**
         * 智能体配置键（引擎信息归一：业务侧 AgentProfile 稳定键——main 主智能体
         * 对话轮 / executor 编码 run；无配置语境的一次性调用不携带）——呈现形态
         * 的登记锚：executor 起工作消息、main 进对话面（#86 单会话收敛后对话只有
         * 主智能体一座）。
         */
        agent?: string;
      };
    }
  | { type: "error"; payload: AgentPayload & { message: string } }
  | {
      /** 运行结束：到达即真收口（#84——编码 run 在收口判据落定后才发，一场至多
       * 一次、中场无假收口）；挂起轮不发（软终点）。 */
      type: "run-finish";
      payload: AgentPayload & { sessionId: string; finish: string };
    }
  | {
      /**
       * 智能体挂起提问（#83 起纯 QUESTION——权限确认已拆 permission-required）：答复
       * 续跑归业务编排（问答作答通道，需求环）。`engineRef` = 引擎侧请求 id（续跑
       * 批复的锚）；`data` = 引擎载荷原样（含前端问答卡投影 `data.questions`），
       * 问答卡切片消费。
       */
      type: "question-raised";
      payload: AgentPayload & {
        summary: string;
        engineRef?: string;
        data?: unknown;
      };
    }
  | {
      /**
       * 权限确认挂起（#83 事件拆分，词根 = 引擎权限确认原语的非提问面）：run 执行中
       * 需用户批准的工具操作（如危险命令）→ 工作消息流内确认卡（批准/拒绝两个
       * 动作）。作答走权限作答通道（与问答作答分家）。`summary` = 首工具命令文本
       * （截断）；`data.toolCalls` = 待确认工具最小面（确认卡呈现待批准操作的依据）。
       */
      type: "permission-required";
      payload: AgentPayload & {
        summary: string;
        engineRef?: string;
        data?: unknown;
      };
    }
  | {
      /**
       * 权限确认落定（#83）：作答被受理（批准或拒绝）即发射——确认卡转已批/已拒
       * 终态（重放面：重连/刷新后确认卡不回退成待答）。续跑结果另行经 run 过程
       * 事件到达（批准的动作卡完成 / 拒绝的动作卡失败 + 后续模型行为）。
       */
      type: "permission-resolved";
      payload: AgentPayload & { engineRef: string; approved: boolean };
    }
  | {
      /**
       * 编码 run 重试超限·终态收口（#56）：轨道层在真终态落定点发射（修正轨道与
       * 终态账同事实点——排队合并续派的中途超限不是终态，不发）；`runId` = 该场
       * run 的用户面标识（首试 runId——#84 静默重试：重试不换新锚、不新发
       * run-start，中间尝试的内部 runId 不出用户面）。恢复出口（重新发起 /
       * 重新修改）只认本事件——run 失败为唯一失败终态，重试全程静默（中间错误
       * 不出用户面）。
       */
      type: "run-failed";
      payload: AgentPayload;
    }
  | {
      /**
       * 兜底轻引导回复（#47 入口三分类）：非意见非咨询输入的平台侧定型文案——
       * 零产物路径（不起任何智能体 run，本事件即该次派发的全部）；`prompt` 为
       * 锚定的用户输入（重放重建对话面）；`label` 为呈现标签（「平台」）；
       * `text` 为引导文案（下单意图引导到「确认下单」）。
       */
      type: "guide-reply";
      payload: AgentPayload & { prompt: string; label: string; text: string };
    }
  | {
      /** 解说文本部件：`text` 为完整段非增量（服务端逐段成型，收口事件前出尾段）。 */
      type: "part-text";
      payload: AgentPayload & { engine: string; text: string };
    }
  | {
      /**
       * 工具动作部件（动作卡）：开始/进行中/完成/失败全生命周期——动作一开始即出
       * 事件，同一动作以 `toolCallId` 锚定跨状态更新。`state` ∈ started（参数在途，
       * label 通用对象）/ running（参数落定，label 具体对象）/ completed / failed
       * （动作层状态；run 层唯一失败终态仍是 run-failed）；`label` 无时态（时态由
       * state 表达）。播报工具封闭表：write_file / edit_file / command。
       */
      type: "part-action";
      payload: AgentPayload & {
        engine: string;
        toolCallId: string;
        toolName: string;
        state: "started" | "running" | "completed" | "failed";
        label: string;
      };
    }
  | {
      /** 步骤分组部件：run 内步骤序号（1 起，模型调用边界），呈现「第 N 步」分组头。 */
      type: "part-step";
      payload: AgentPayload & { engine: string; step: number };
    }
  | {
      /**
       * 自检播报部件（#85「正在检查系统 → ✅/❌」）：run 收口判据核验的呈现——
       * 平台侧产出（不经引擎映射表，无 engine 字段；判据是平台事实）。`state` ∈
       * checking（核验中）/ passed / failed；静默重试同构：尝试间核验未过不发
       * failed（部件停在 checking，重复 checking 幂等），failed 仅末次未过（超限
       * 转终态）与 run-failed 同窗口。终值 = 探活结果，收尾卡统计行随 #88 消费。
       */
      type: "part-check";
      payload: AgentPayload & { sessionId: string; state: "checking" | "passed" | "failed" };
    };

const PLATFORM_AGENT_TYPES: ReadonlySet<string> = new Set([
  "run-start",
  "error",
  "run-finish",
  "question-raised",
  "permission-required",
  "permission-resolved",
  "run-failed",
  "guide-reply",
  "part-text",
  "part-action",
  "part-step",
  "part-check",
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
 * 智能体事件族收窄（两个函数而非一个判别联合）：透传 `type: string` 若并入联合会与
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
