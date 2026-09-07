import type { QueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import { parseQuestion } from "@/lib/chat/qa";
import { asRecord } from "@/lib/utils";
import { queryKeys } from "@/lib/api/keys";
import { toWorkClosing, useChatStore } from "@/lib/store/chat";
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
 * - 智能体事件族 = 事件 → chat store（对话面）+ generation store（生成面）+
 *   工作消息 store（生长中的工作消息）分发；编码 run 收口的失效也在此
 *   （generated_at 落库后详情重拉，正确性走 REST）。
 * 事件只让 UI 活、不承担正确性：终态事件同样只 invalidate，正确性永远走 REST。
 */

/**
 * 通知事件 → 粗粒度失效前缀。键类型锁死为名册穷尽：正本新增 type 而
 * events.ts / 此处漏登，typecheck 即红（对接 issue 时同步维护）。
 */
const NOTIFICATION_INVALIDATIONS = {
  "workspace-created": [queryKeys.projects.all],
  // 预览地址由切片收口事件推 URL（#105 载荷写入口，见下方 NOTIFICATION_PAYLOAD_
  // WRITERS）；不在此失效 projects 前缀——preview() 每次成功仍会发本事件，失效即
  // 重拉预览查询 → 又成功 → 又发事件——自反馈死循环（写缓存非失效，不重拉 preview）
  "preview-ready": [],
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
 * 拿不到的载荷** 写入轻量 store 页内呈现，或把**免轮询拿到的载荷**（#105
 * preview-ready 的 URL）写进查询缓存；其余事件一律只失效。桥仍是 store 唯一
 * 事件写入方；正确性以 REST 重查为准。注册表按 type 键派发，写入方内的
 * 判别守卫仅为编译期收窄（键即类型，不会走错分支——关联联合的调用点无法
 * 类型化到键，守卫不是运行时逻辑）。
 */
const NOTIFICATION_PAYLOAD_WRITERS: Partial<
  Record<NotificationEvent["type"], (event: NotificationEvent, queryClient: QueryClient) => void>
> = {
  // 「这次写入是不是修订」重查拿不到（prd_produced_at 首产/修订同刷新）——
  // 按到达序在 store 里分岔（首产登记 seen、此后置 pending 出胶囊）
  "document-updated": (event) => {
    if (event.type !== "document-updated") return;
    if (event.payload.documentType !== "PRD") return;
    usePrdNoticesStore.getState().notePrdWritten(event.payload.projectId);
  },
  // 预览 URL 事件驱动拿取（#105）：切片收口推 URL 直接写预览查询缓存——免 3s
  // 轮询；写缓存非失效，不会重拉 preview 再发事件（消自反馈循环顾虑）。缺省该
  // 写入方 = 空登忽略（旧口径），现在 URL 由事件推送而非 REST 副作用唯一来源
  "preview-ready": (event, queryClient) => {
    if (event.type !== "preview-ready") return;
    queryClient.setQueryData(queryKeys.projects.preview(event.payload.projectId), {
      url: event.payload.url,
    });
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
  NOTIFICATION_PAYLOAD_WRITERS[notification.type]?.(notification, queryClient);
}

/**
 * 智能体事件 → chat store + generation store + 工作消息 store 分发（事件 id =
 * SSE 完整事件 id，React key 白拿）。run-start 携带智能体配置键（引擎信息归一）
 * ——呈现形态的登记锚都在此：executor 起工作消息、main 进对话面（#86 单会话
 * 收敛：对话只有主智能体一座，无角色分支）。
 */
export function dispatchAgentEvent(queryClient: QueryClient, event: SseEvent): void {
  const envelope = parseSseEnvelope(event.data);
  if (!envelope) return;
  // 单端点单流上通知族与智能体事件族混载：本分发口只消费智能体事件族——
  // 通知族由站点级常开连接的 dispatchNotificationEvent 消费（防双连接双处理）
  if (asNotificationEvent(envelope)) return;
  const chat = useChatStore.getState();
  const generation = useGenerationStore.getState();
  const work = useWorkMessageStore.getState();
  // 信封 ts 保留（#115：过程耗时已下线不作展示——ts 仅供确认卡挂起锚与断线补发
  // 排序；重放保留原值，客户端到达时序不可用）
  const at = eventTime(envelope.ts);

  const platform = asPlatformAgentEvent(envelope);
  if (platform) {
    switch (platform.type) {
      case "run-start": {
        const { payload } = platform;
        // 配置键 = 呈现形态的登记锚（引擎信息归一，#82 起 run-start 唯一携带）：
        // executor → 编码 run（生成面登记 + 工作消息起锚）；main → 对话面 run
        // （登记在先、用户气泡随 ingestRunStart 落——对话史重建的判定锚）
        if (payload.agent === "executor") {
          generation.noteCoderRun(payload.projectId, payload.runId);
          work.startWork(payload.projectId, payload.runId);
        } else if (payload.agent === "main") {
          chat.noteChatRun(payload.projectId, payload.runId);
          chat.ingestRunStart(payload.projectId, payload.runId, payload.prompt);
        }
        return;
      }
      case "question-raised": {
        const { payload } = platform;
        const question = parseQuestion(event.id, payload);
        if (question) chat.raiseQuestion(payload.projectId, payload.runId, question);
        return;
      }
      // ---- 权限确认（#83 作答通道分家）：确认卡长在工作消息流，与问答卡分形态 ----
      case "permission-required": {
        const { payload } = platform;
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
        work.resolvePermission(
          payload.projectId,
          payload.engineRef,
          payload.approved ? "approved" : "denied",
        );
        return;
      }
      case "permission-timed-out": {
        // 权限确认超时（#112）：确认卡转「已超时」定格（不可作答，按钮退场）——
        // run-failed 随后到达定格整条工作消息
        const { payload } = platform;
        work.resolvePermission(payload.projectId, payload.engineRef, "timedout");
        return;
      }
      case "error": {
        const { payload } = platform;
        // 编码 run 的 error = 事件序异常（#84：尝试环内中间错误不出用户面事件流，
        // 服务端投影失守的防御位）——不写任何 UI（中途闪错即本票防的回归），
        // run 层唯一失败终态仍归 run-failed
        if (isCoderRun(generation, payload.projectId, payload.runId)) return;
        // 对话轮失败（非重试族）：对话面收轮 + 失败气泡；生成面不写状态（#84）
        chat.noteTurnError(payload.projectId, payload.runId, payload.message, event.id);
        // 受理卡落定不死转（#87）：受理轮炸——中断提示已是兜底呈现，卡不悬转
        chat.settleAcceptance(payload.projectId, payload.runId);
        return;
      }
      case "run-failed": {
        // 编码 run 超限终态收口（#56）：轨道真终态（事件到 ⟺ 恢复出口可达）——
        // 「重新发起/重新修改」只认本事件；无 executor 登记的 runId 忽略（事件序
        // 异常防御位，同其他 coder 事件）
        const { payload } = platform;
        if (isCoderRun(generation, payload.projectId, payload.runId)) {
          generation.noteCoderFailed(payload.projectId);
        }
        // 工作消息定格（run 失败是唯一失败终态——消息冻结，恢复出口在生成面）
        work.freezeWork(payload.projectId, payload.runId);
        return;
      }
      case "run-finish": {
        const { payload } = platform;
        chat.finishTurn(payload.projectId, payload.runId);
        // 受理卡落定（#87）：受理轮收口（受理完成——追问挂起轮不发本事件，挂起
        // 期间卡保持受理中）；更新 run 随收口自动派发，工作消息即视觉衔接
        chat.settleAcceptance(payload.projectId, payload.runId);
        // 工作消息定格（run 收口 = 消息定格；编码 run 真收口携 closing——#88/#89
        // 收尾卡权威事实归对话流，过程部件退场，非锚定 run 的收口在 store 内忽略）
        const closing = toWorkClosing(payload.closing);
        work.freezeWork(payload.projectId, payload.runId, closing);
        if (closing) {
          chat.appendClosing(payload.projectId, payload.runId, closing, event.id);
        }
        if (isCoderRun(generation, payload.projectId, payload.runId)) {
          generation.noteCoderFinish(payload.projectId, event.id);
          // 编码 run 收口：generated_at 落库 → 失效项目域（详情重拉出事实，
          // 预览地址域随之刷新；预览重挂由 generation store 纪元驱动）
          void queryClient.invalidateQueries({ queryKey: queryKeys.projects.all });
        }
        // 轮收口即对话史有新条目（发言+回复 / 收尾卡落库）——失效对话域，水合增量
        // 接管 live 片段（#89：闭史以 REST 为准）
        void queryClient.invalidateQueries({ queryKey: queryKeys.conversation.all });
        return;
      }
      case "guide-reply": {
        // 兜底轻引导（#47 入口三分类）：平台定型文案直达对话面（带标签对话气泡，
        // 非 run、非智能体话语）；prompt 供重建用户气泡；runId 锚定水合退位合并
        const { payload } = platform;
        chat.noteGuideReply(
          payload.projectId,
          payload.runId,
          payload.prompt,
          payload.label,
          payload.text,
          event.id,
        );
        return;
      }
      case "acceptance-start": {
        // 受理动作卡（#87）：受理轮开场受理事实——意见已接住、正在受理（服务端
        // 守卫全过后、受理动作前发，先于 run-start 到达）。落定不出专事件：该轮
        // run-finish / error 收口即落定（见上两分支）；咨询轮与纯追问轮服务端不发
        const { payload } = platform;
        chat.noteAcceptance(payload.projectId, payload.runId, event.id);
        return;
      }
      // ---- 消息部件（parts 契约）→ 工作消息 store ----
      // 部件全事件流恒挂（主智能体对话轮也产部件）——store 侧锚定守卫只收编码 run。
      case "part-text": {
        const { payload } = platform;
        work.notePart(
          payload.projectId,
          { runId: payload.runId, sessionId: payload.sessionId, eventId: event.id, at,
            source: payload.source },
          { kind: "text", text: payload.text },
        );
        return;
      }
      case "part-action": {
        const { payload } = platform;
        work.notePart(
          payload.projectId,
          { runId: payload.runId, sessionId: payload.sessionId, eventId: event.id, at,
            source: payload.source },
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
      case "part-check": {
        // 自检播报（#85）：收口判据核验「检查中 → ✅/❌」——平台侧产出（无 engine
        // 字段）；一场 run 一个自检部件，跨状态原位换装归 store
        const { payload } = platform;
        work.notePart(
          payload.projectId,
          { runId: payload.runId, sessionId: payload.sessionId, eventId: event.id, at },
          { kind: "check", state: payload.state },
        );
        return;
      }
    }
  }

  // 引擎透传（开放集合）：唯一消费面 = 主智能体的解说文本增量（text——对话
  // 气泡）；其余名型（reasoning/patch/tool/step-*）过程呈现归工作消息部件，
  // 不进任何 store
  const passthrough = asPassthroughAgentEvent(envelope);
  if (!passthrough) return;
  if (passthrough.type === "text") {
    const { payload } = passthrough;
    const delta = asRecord(payload.data)?.delta;
    chat.appendAgentDelta(payload.projectId, payload.runId, delta, event.id);
  }
}

/** 信封 ts → ms（坏值回落客户端时钟：时长粗对齐总好过锚丢失）。 */
function eventTime(ts: string): number {
  const parsed = Date.parse(ts);
  return Number.isNaN(parsed) ? Date.now() : parsed;
}
