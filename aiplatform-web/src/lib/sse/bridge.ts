import type { QueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import { parseQuestion } from "@/lib/chat/qa";
import { queryKeys } from "@/lib/api/keys";
import { useAgentStreamsStore } from "@/lib/store/agent-streams";
import { useChatStore } from "@/lib/store/chat";
import { isCoderRun, useGenerationStore } from "@/lib/store/generation";
import { useDispatchStageStore } from "@/lib/store/dispatch-stage";
import { useLiveStore } from "@/lib/store/live";
import { usePrdNoticesStore } from "@/lib/store/prd-notices";
import { orderStatusToastText } from "@/lib/orders/status";

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
 *   store 或即时呈现——本文件是 store 唯一事件写入方；订单态变化 toast 是即时
 *   呈现例外，#30）；
 * - agent 通道 = 事件 → agent-streams store（过程层）+ chat store（指令区对话面）
 *   + generation store（生成面）分发；编码 run 收口的失效也在此（generated_at
 *   落库后详情重拉，正确性走 REST）。
 * 事件只让 UI 活、不承担正确性：终态事件同样只 invalidate，正确性永远走 REST。
 */

/**
 * 通知事件 → 粗粒度失效前缀。键类型锁死为名册穷尽：正本新增 type 而
 * events.ts / 此处漏登，typecheck 即红（对接 issue 时同步维护）。
 */
const NOTIFICATION_INVALIDATIONS = {
  "workspace-created": [queryKeys.projects.all],
  // 预览地址由 REST 响应自身携带、无需失效；preview() 每次成功都会发射本帧，
  // 若在此失效 projects 前缀会重拉预览查询 → 又成功 → 又发帧——自反馈死循环
  // （#45 门禁解除后轮询从 run 开始，循环必被踩中，故显式空登）
  "preview-ready": [],
  // 逐修改刷新（#49）：内容在 iframe 背后的沙箱应用里、REST 域无变化可失效，
  // URL 不变——重载走 generation store 预览纪元（见载荷展示注册表），非失效
  "preview-updated": [],
  "workspace-destroyed": [queryKeys.projects.all],
  // PRD 内容与更新时间在 documents 域；projects 详情的 prdProducedAt 是成果区
  // 长出判据，写出瞬间一并重拉
  "document-updated": [queryKeys.documents.all, queryKeys.projects.all],
  "project-renamed": [queryKeys.projects.all],
  // 订单态变化：订单卡详情（状态/金额/改价历史）+ 项目域（activeOrder/archived
  // 嵌入——锁定式矩阵与归档终态的推导输入）一并重拉
  "order-status-changed": [queryKeys.projects.all, queryKeys.orders.all],
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
  // 逐修改刷新（#49）：「内容前移了一步」是瞬时信号，REST 重查拿不到（预览
  // URL 不变、轮询已停）——写 generation store 计预览纪元（节流在 store 内）
  "preview-updated": (event) => {
    if (event.type !== "preview-updated") return;
    useGenerationStore.getState().notePreviewUpdated(event.payload.projectId, Date.now());
  },
  // 订单态变化 toast（spec：点击直达项目页）：状态文案归纯函数单点
  // （lib/orders/status），导航用整页跳（桥在 React 外，无 router 上下文——
  // 同 401 出口先例 window.location.href；点击时才跳，停留中的页面不被动导航）
  "order-status-changed": (event) => {
    if (event.type !== "order-status-changed") return;
    const { projectId } = event.payload;
    toast(orderStatusToastText(event.payload.status, event.payload.statusName), {
      action: {
        label: "查看项目",
        onClick: () => {
          // 桥在 React 外（无 router 上下文）：整页跳同 401 出口先例，点击才跳
          // eslint-disable-next-line @next/next/no-location-assign-relative-destination
          window.location.href = `/projects/${projectId}`;
        },
      },
    });
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

/** agent 流事件 → streams store + chat store + generation store（分段 id = SSE 完整事件 id，React key 白拿）。 */
export function dispatchAgentEvent(queryClient: QueryClient, event: SseEvent): void {
  const envelope = parseSseEnvelope(event.data);
  if (!envelope) return;
  const store = useAgentStreamsStore.getState();
  const chat = useChatStore.getState();
  const generation = useGenerationStore.getState();
  const stage = useDispatchStageStore.getState();
  const live = useLiveStore.getState();

  const platform = asPlatformAgentEvent(envelope);
  if (platform) {
    switch (platform.type) {
      case "run-start": {
        const { payload } = platform;
        store.startRun({
          runId: payload.runId,
          projectId: payload.projectId,
          prompt: payload.prompt,
          model: payload.model,
          engine: payload.engine,
        });
        chat.ingestRunStart(payload.projectId, payload.runId, payload.prompt);
        if (isCoderRun(generation, payload.projectId, payload.runId)) {
          generation.noteCoderRunStart(payload.projectId);
        }
        return;
      }
      case "run-created": {
        const { payload } = platform;
        store.markRunCreated(payload, payload.sessionId);
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
        // 对话面 run 登记（#47 三分类后多角色进指令区）：BA / 助理会话 run 才
        // 进对话（run-start 落用户气泡、text 增量累积）；CODER 归生成面
        if (payload.role === "BA" || payload.role === "ASSISTANT") {
          chat.noteChatRun(payload.projectId, payload.runId, payload.roleLabel);
        }
        if (payload.role === "CODER") {
          generation.noteCoderRun(payload.projectId, payload.runId);
        }
        return;
      }
      case "question-raised": {
        const { payload } = platform;
        store.appendSegment(payload, {
          kind: "question",
          id: event.id,
          questionKind: payload.kind,
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
        // 生成面不写状态（#56）：error 是逐次尝试的过程事实，终态只认 run-failed
        // 收口帧——否则重试间隔内恢复出口闪现（点击被 PRJ_025 挡回）
        return;
      }
      case "run-retrying": {
        const { payload } = platform;
        store.appendSegment(payload, {
          kind: "retrying",
          id: event.id,
          attempt: payload.attempt,
          message: payload.message,
        });
        if (isCoderRun(generation, payload.projectId, payload.runId)) {
          generation.noteCoderRetrying(payload.projectId, payload.message);
        }
        return;
      }
      case "run-failed": {
        // 编码 run 超限终态收口（#56）：轨道真终态（帧到 ⟺ 恢复出口可达）——
        // 「重新发起/重新修改」只认本帧；无 CODER 登记的 runId 忽略（帧序异常
        // 防御位，同其他 coder 帧）
        const { payload } = platform;
        if (isCoderRun(generation, payload.projectId, payload.runId)) {
          generation.noteCoderFailed(payload.projectId);
        }
        return;
      }
      case "run-finish": {
        const { payload } = platform;
        store.appendSegment(payload, {
          kind: "finish",
          id: event.id,
          finish: payload.finish,
        });
        chat.finishTurn(payload.projectId, payload.sessionId);
        if (isCoderRun(generation, payload.projectId, payload.runId)) {
          generation.noteCoderFinish(payload.projectId, event.id);
          // 编码 run 收口：generated_at 落库 → 失效项目域（详情重拉出事实，
          // 预览地址域随之刷新；预览重挂由 generation store 纪元驱动）
          void queryClient.invalidateQueries({ queryKey: queryKeys.projects.all });
        }
        return;
      }
      case "fix-unchanged": {
        // 修正收口·系统未动（#46）：如实呈现进指令区（非 BA 话语——平台侧通告，
        // 「系统未修改 + 原因」让用户区分「不需要改」与「链路断了」）；编码 run
        // 判定锚同其他 coder 帧（无登记的 runId 忽略——帧序异常的防御位）
        const { payload } = platform;
        if (isCoderRun(generation, payload.projectId, payload.runId)) {
          chat.noteSystemUnchanged(payload.projectId, payload.reason, event.id);
        }
        return;
      }
      case "guide-reply": {
        // 兜底轻引导（#47 入口三分类）：平台定型文案直达指令区（带标签对话气泡，
        // 非 run、非智能体话语）；prompt 供重放重建用户气泡；重放按事件 id 只收一次
        const { payload } = platform;
        chat.noteGuideReply(
          payload.projectId,
          payload.prompt,
          payload.label,
          payload.text,
          event.id,
        );
        return;
      }
      case "dispatch-stage": {
        // 派发阶段帧（#50 阶段状态条）：只进阶段 store（指令区状态条唯一消费面；
        // 帧不承担正确性——失败链停在事发阶段，error 帧另行呈现）
        const { payload } = platform;
        stage.noteStage(payload.projectId, payload.stage, payload.changed);
        return;
      }
      // 直播帧（#23）：只进直播面 store（直播侧栏唯一消费面——前端不耦合引擎
      // 事件格式；帧仅编码 run 发射，无需角色过滤）
      case "live-text": {
        const { payload } = platform;
        live.noteLiveSegment(payload.projectId, payload.runId, {
          kind: "text",
          id: event.id,
          text: payload.text,
        });
        return;
      }
      case "live-action": {
        const { payload } = platform;
        live.noteLiveSegment(payload.projectId, payload.runId, {
          kind: "action",
          id: event.id,
          action: payload.action,
        });
        return;
      }
      case "live-step": {
        const { payload } = platform;
        live.noteLiveSegment(payload.projectId, payload.runId, {
          kind: "step",
          id: event.id,
          step: payload.step,
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
    // 指令区对话面只收对话角色（BA/助理）的文本增量（思考/补丁/工具帧不进对话）
    if (kind === "text") {
      const delta = asRecord(payload.data)?.delta;
      chat.appendAgentDelta(payload.projectId, payload.runId, payload.sessionId, delta, event.id);
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
