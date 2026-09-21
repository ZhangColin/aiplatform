import type { QueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import { parseQuestion } from "@/lib/chat/qa";
import { asRecord } from "@/lib/utils";
import { queryKeys } from "@/lib/api/keys";
import { toWorkClosing, useChatStore } from "@/lib/store/chat";
import { isCoderRun, useGenerationStore } from "@/lib/store/generation";
import { usePrdNoticesStore } from "@/lib/store/prd-notices";
import { useWorkMessageStore } from "@/lib/store/work-message";
import { ORDER_STATUS } from "@/lib/orders/lock";
import { orderStatusToastText, REPRICED_TOAST_TEXT } from "@/lib/orders/status";

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

/** 订单事件（order-status-changed / order-repriced）的共用失效面。 */
const ORDER_INVALIDATIONS = [
  queryKeys.projects.all,
  queryKeys.orders.all,
  queryKeys.conversation.all,
] as const;

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
  // 订单事件失效面（#203 首报 / #204 改价同款）：订单域（详情/金额现值重拉——
  // 报价卡视镜显当前价）+ 项目域（activeOrder/archived 嵌入——锁定式矩阵与归档
  // 终态的推导输入）+ 对话史域（在场项目页的报价卡经重查水合实时入流；信号-only：
  // 载荷不含金额，金额走订单查询）；toast 分流见下方载荷写入方（#206）
  "order-status-changed": ORDER_INVALIDATIONS,
  "order-repriced": ORDER_INVALIDATIONS,
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
  // 订单态变化 toast（#30 + #206 分流）：状态文案归纯函数单点
  // （lib/orders/status）。在场分流——首次报价（quoted）在项目页在场时不弹：
  // 失效水合已让报价卡实时入流，toast 即冗余；其余状态无入流对等面，维持即时
  // 呈现（订单卡支付/取消的成功反馈依赖本 toast，order-panel 口径）
  "order-status-changed": (event) => {
    if (event.type !== "order-status-changed") return;
    const { projectId, status, statusName } = event.payload;
    if (status === ORDER_STATUS.quoted && inPlaceOnProject(projectId)) return;
    orderSignpostToast(orderStatusToastText(status, statusName), projectId);
  },
  // 改价 toast 路标（#204 事件语义 + #206 分流）：在场不弹——历史报价卡视镜经
  // 订单域失效重查自然显新价；他页弹「报价已更新」（与首报文案区分，不带金额）
  "order-repriced": (event) => {
    if (event.type !== "order-repriced") return;
    const { projectId } = event.payload;
    if (inPlaceOnProject(projectId)) return;
    orderSignpostToast(REPRICED_TOAST_TEXT, projectId);
  },
};

// ── React 路由上下文（#206 toast 分流的判定输入）──────────────────────────────

/** 当前路由 + 组件内导航口（SseProvider 挂载时登记；桥在 React 外按需读）。 */
export type RouteContext = {
  pathname: string;
  push: (path: string) => void;
};

let routeContext: RouteContext | null = null;

/**
 * 登记口（SseProvider 挂载/路由变化时写入）：桥内 toast 分流的在场判定与路标
 * 跳转都经此读路由——桥无 React 上下文，这是 router 的唯一取用缝。未登记
 * （早于挂载/测试）按他页在场兜底，路标回落整页跳。
 */
export function noteRouteContext(context: RouteContext): void {
  routeContext = context;
}

/** 项目页地址（订单事件路标的跳转目标/在场比照）。 */
function projectPath(projectId: string): string {
  return `/projects/${projectId}`;
}

/** 在场判定：当前路由即该订单的项目页（精确匹配——/projects/9001 不误伤 900）。 */
function inPlaceOnProject(projectId: string): boolean {
  return routeContext?.pathname === projectPath(projectId);
}

/** 订单事件 toast 路标：动作走组件内跳转（router.push，无整页刷新；点击才跳）。 */
function orderSignpostToast(text: string, projectId: string): void {
  toast(text, {
    action: {
      label: "查看项目",
      onClick: () => {
        const target = projectPath(projectId);
        // 未登记回落整页跳（同 401 出口先例）——生产登记先于任何事件到达
        if (routeContext) routeContext.push(target);
        else window.location.href = target;
      },
    },
  });
}

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

/** 信封 ts → epoch ms（时钟锚，#225）；缺失/不可解析 → undefined（不伪造起点）。 */
function epochMsOf(ts: string): number | undefined {
  if (!ts) return undefined;
  const ms = Date.parse(ts);
  return Number.isNaN(ms) ? undefined : ms;
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
          // run 级时钟起锚（#225）：run-start 信封 ts——单一 run 起点锚
          // （ADR-0010 窄修订），缺 ts（测试/异常信封）不落锚、时钟不渲染
          work.startWork(payload.projectId, payload.runId, payload.slice, epochMsOf(envelope.ts));
          // 四态投影回「生成中」（#222）：起跑即失效项目域——补产轮收口再派的
          // 轨道落库无对话面事件可搭，靠本失效收尾（点击路径的失效在 mutation）
          void queryClient.invalidateQueries({ queryKey: queryKeys.projects.all });
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
        // 「继续生成/重新修改」只认本事件；无 executor 登记的 runId 忽略（事件序
        // 异常防御位，同其他 coder 事件）
        const { payload } = platform;
        if (isCoderRun(generation, payload.projectId, payload.runId)) {
          generation.noteCoderFailed(payload.projectId);
          // 四态投影回「生成中断」（#222）：终态落轨道表即失效项目域——「继续
          // 生成」出口由投影派生（刷新后仍在，不依赖本事件）
          void queryClient.invalidateQueries({ queryKey: queryKeys.projects.all });
        }
        // 工作消息定格（run 失败是唯一失败终态——消息冻结，恢复出口在生成面）；
        // endedAt = 收口信封 ts（时钟定格锚，#225）
        work.freezeWork(payload.projectId, payload.runId, epochMsOf(envelope.ts));
        return;
      }
      case "run-finish": {
        const { payload } = platform;
        chat.finishTurn(payload.projectId, payload.runId);
        // 受理卡落定（#87）：受理轮收口（受理完成——追问挂起轮不发本事件，挂起
        // 期间卡保持受理中）；更新 run 随收口自动派发，工作消息即视觉衔接
        chat.settleAcceptance(payload.projectId, payload.runId);
        // 工作消息定格（run 收口 = 消息定格；编码 run 真收口携 closing——#88/#89
        // 收尾卡权威事实归对话流随后入流，工作消息原地定格留驻 #117，非锚定 run
        // 的收口在 store 内忽略）
        const closing = toWorkClosing(payload.closing);
        // endedAt = 收口信封 ts（时钟定格锚，#225）
        work.freezeWork(payload.projectId, payload.runId, epochMsOf(envelope.ts));
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
          { runId: payload.runId, sessionId: payload.sessionId, eventId: event.id,
            source: payload.source },
          { kind: "text", text: payload.text },
        );
        return;
      }
      case "part-action": {
        const { payload } = platform;
        work.notePart(
          payload.projectId,
          { runId: payload.runId, sessionId: payload.sessionId, eventId: event.id,
            source: payload.source },
          {
            kind: "action",
            toolCallId: payload.toolCallId,
            toolName: payload.toolName,
            state: payload.state,
            label: payload.label,
            error: payload.error, // 失败留痕（#229）：仅 failed 携带，其余态缺省
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
          { runId: payload.runId, sessionId: payload.sessionId, eventId: event.id },
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
