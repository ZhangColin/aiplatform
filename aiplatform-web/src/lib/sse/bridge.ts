import type { QueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import { parseQuestion } from "@/lib/chat/qa";
import { queryKeys } from "@/lib/api/keys";
import { useAgentRunsStore } from "@/lib/store/agent-runs";
import { useChatStore } from "@/lib/store/chat";
import { isCoderRun, useGenerationStore } from "@/lib/store/generation";
import { usePrdNoticesStore } from "@/lib/store/prd-notices";
import { useWorkMessageStore } from "@/lib/store/work-message";
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
 * 事件 → 状态桥（ADR 0003；单端点单流，#82）：
 * - 通知族 = 声明式失效注册表 + 载荷展示白名单（REST 重查拿不到的载荷写轻量
 *   store 或即时呈现——本文件是 store 唯一事件写入方；订单态变化 toast 是即时
 *   呈现例外，#30）——通知由站点级常开连接消费（SseProvider），项目页的智能体
 *   事件连接不重复分发（族内分工，防双连接双处理）；
 * - 智能体事件族 = 事件 → agent-runs store（运行注册表）+ chat store（对话面）
 *   + generation store（生成面）+ 工作消息 store（生长中的工作消息）分发；编码
 *   run 收口的失效也在此（generated_at 落库后详情重拉，正确性走 REST）。
 * 事件只让 UI 活、不承担正确性：终态事件同样只 invalidate，正确性永远走 REST。
 */

/**
 * 通知事件 → 粗粒度失效前缀。键类型锁死为名册穷尽：正本新增 type 而
 * events.ts / 此处漏登，typecheck 即红（对接 issue 时同步维护）。
 */
const NOTIFICATION_INVALIDATIONS = {
  "workspace-created": [queryKeys.projects.all],
  // 预览地址由 REST 响应自身携带、无需失效；preview() 每次成功都会发射本事件，
  // 若在此失效 projects 前缀会重拉预览查询 → 又成功 → 又发事件——自反馈死循环
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

/**
 * 智能体事件 → agent-runs store + chat store + generation store + 工作消息 store
 * 分发（事件 id = SSE 完整事件 id，React key 白拿）。run-start 携带角色键（引擎
 * 信息归一）——对话面 run 与编码 run 的登记锚都在此：BA/ASSISTANT 进对话、
 * CODER 起工作消息。
 */
export function dispatchAgentEvent(queryClient: QueryClient, event: SseEvent): void {
  const envelope = parseSseEnvelope(event.data);
  if (!envelope) return;
  // 单端点单流上通知族与智能体事件族混载：本分发口只消费智能体事件族——
  // 通知族由站点级常开连接的 dispatchNotificationEvent 消费（防双连接双处理）
  if (asNotificationEvent(envelope)) return;
  const runs = useAgentRunsStore.getState();
  const chat = useChatStore.getState();
  const generation = useGenerationStore.getState();
  const work = useWorkMessageStore.getState();
  // 信封 ts 是部件时长与起跑锚的唯一时间源（重放保留原值，客户端到达时序不可用）
  const at = eventTime(envelope.ts);

  const platform = asPlatformAgentEvent(envelope);
  if (platform) {
    switch (platform.type) {
      case "run-start": {
        const { payload } = platform;
        // 运行注册表（LIVE 脉冲锚）：新 runId 重开（同项目驱逐旧 run）
        runs.startRun({ runId: payload.runId, projectId: payload.projectId, at });
        // 角色键 = 会话/呈现形态的登记锚（引擎信息归一，#82 起 run-start 唯一携带）：
        // CODER → 编码 run（生成面登记 + 工作消息起锚）；BA/ASSISTANT → 对话面
        // run（登记在先、用户气泡随 ingestRunStart 落——对话史重建的判定锚）
        if (payload.role === "CODER") {
          generation.noteCoderRun(payload.projectId, payload.runId);
          work.startWork(payload.projectId, payload.runId, at);
        } else if (payload.role === "BA" || payload.role === "ASSISTANT") {
          chat.noteChatRun(payload.projectId, payload.runId);
          chat.ingestRunStart(payload.projectId, payload.runId, payload.prompt);
        }
        return;
      }
      case "question-raised": {
        const { payload } = platform;
        runs.setRunStatus(
          { runId: payload.runId, projectId: payload.projectId, at },
          "questioning",
        );
        const question = parseQuestion(event.id, payload);
        if (question) chat.raiseQuestion(payload.projectId, payload.sessionId, question);
        return;
      }
      // ---- 权限确认（#83 作答通道分家）：确认卡长在工作消息流，与问答卡分形态 ----
      case "permission-required": {
        const { payload } = platform;
        // 等用户 ≠ 终态（同问答挂起语义）；作答后 permission-resolved 回 running
        runs.setRunStatus(
          { runId: payload.runId, projectId: payload.projectId, at },
          "questioning",
        );
        work.notePart(
          payload.projectId,
          { runId: payload.runId, sessionId: payload.sessionId, eventId: event.id, at },
          {
            kind: "permission",
            engineRef: typeof payload.engineRef === "string" ? payload.engineRef : "",
            summary: payload.summary,
          },
        );
        return;
      }
      case "permission-resolved": {
        const { payload } = platform;
        // run 续跑进行中（批准的动作卡随后完成 / 拒绝的动作卡随后失败）；终态仍归
        // run-finish / run-failed
        runs.setRunStatus(
          { runId: payload.runId, projectId: payload.projectId, at },
          "running",
        );
        work.resolvePermission(
          payload.projectId,
          payload.engineRef,
          payload.approved ? "approved" : "denied",
        );
        return;
      }
      case "error": {
        const { payload } = platform;
        // 编码 run 的 error = 事件序异常（#84：尝试环内中间错误不出用户面事件流，
        // 服务端投影失守的防御位）——不写任何 UI（中途闪错即本票防的回归），
        // run 层唯一失败终态仍归 run-failed
        if (isCoderRun(generation, payload.projectId, payload.runId)) return;
        // 对话轮失败（非重试族）：对话面收轮 + 失败气泡；生成面不写状态（#84）
        runs.setRunStatus({ runId: payload.runId, projectId: payload.projectId, at }, "error");
        chat.noteTurnError(payload.projectId, payload.runId, payload.message, event.id);
        return;
      }
      case "run-failed": {
        // 编码 run 超限终态收口（#56）：轨道真终态（事件到 ⟺ 恢复出口可达）——
        // 「重新发起/重新修改」只认本事件；无 CODER 登记的 runId 忽略（事件序
        // 异常防御位，同其他 coder 事件）
        const { payload } = platform;
        runs.setRunStatus({ runId: payload.runId, projectId: payload.projectId, at }, "error");
        if (isCoderRun(generation, payload.projectId, payload.runId)) {
          generation.noteCoderFailed(payload.projectId);
        }
        // 工作消息定格（run 失败是唯一失败终态——消息冻结，恢复出口在生成面）
        work.freezeWork(payload.projectId, payload.runId, at);
        return;
      }
      case "run-finish": {
        const { payload } = platform;
        runs.setRunStatus(
          { runId: payload.runId, projectId: payload.projectId, at },
          "finished",
        );
        chat.finishTurn(payload.projectId, payload.sessionId);
        // 工作消息定格（run 收口 = 消息定格；非锚定 run 的收口在 store 内忽略）
        work.freezeWork(payload.projectId, payload.runId, at);
        if (isCoderRun(generation, payload.projectId, payload.runId)) {
          generation.noteCoderFinish(payload.projectId, event.id);
          // 编码 run 收口：generated_at 落库 → 失效项目域（详情重拉出事实，
          // 预览地址域随之刷新；预览重挂由 generation store 纪元驱动）
          void queryClient.invalidateQueries({ queryKey: queryKeys.projects.all });
        }
        return;
      }
      case "guide-reply": {
        // 兜底轻引导（#47 入口三分类）：平台定型文案直达对话面（带标签对话气泡，
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
      // ---- 消息部件（parts 契约）→ 工作消息 store ----
      // 部件全事件流恒挂（BA/助理 run 也产部件）——store 侧锚定守卫只收编码 run。
      case "part-text": {
        const { payload } = platform;
        work.notePart(
          payload.projectId,
          { runId: payload.runId, sessionId: payload.sessionId, eventId: event.id, at },
          { kind: "text", text: payload.text },
        );
        return;
      }
      case "part-action": {
        const { payload } = platform;
        work.notePart(
          payload.projectId,
          { runId: payload.runId, sessionId: payload.sessionId, eventId: event.id, at },
          {
            kind: "action",
            toolCallId: payload.toolCallId,
            toolName: payload.toolName,
            state: payload.state,
            label: payload.label,
          },
        );
        return;
      }
      case "part-step": {
        const { payload } = platform;
        work.notePart(
          payload.projectId,
          { runId: payload.runId, sessionId: payload.sessionId, eventId: event.id, at },
          { kind: "step", step: payload.step },
        );
        return;
      }
    }
  }

  // 引擎透传（开放集合）：唯一消费面 = 对话角色的解说文本增量（text——BA/助理
  // 对话气泡）；其余名型（reasoning/patch/tool/step-*）过程呈现归工作消息部件，
  // 不进任何 store
  const passthrough = asPassthroughAgentEvent(envelope);
  if (!passthrough) return;
  if (passthrough.type === "text") {
    const { payload } = passthrough;
    const delta = asRecord(payload.data)?.delta;
    chat.appendAgentDelta(payload.projectId, payload.runId, payload.sessionId, delta, event.id);
  }
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null
    ? (value as Record<string, unknown>)
    : null;
}

/** 信封 ts → ms（坏值回落客户端时钟：时长粗对齐总好过锚丢失）。 */
function eventTime(ts: string): number {
  const parsed = Date.parse(ts);
  return Number.isNaN(parsed) ? Date.now() : parsed;
}
