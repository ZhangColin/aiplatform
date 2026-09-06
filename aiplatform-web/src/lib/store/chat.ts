import { create } from "zustand";

import { parseQuestion, type RaisedQuestion } from "@/lib/chat/qa";
import { parseAnnotationAttachment, type AnnotationDraft } from "@/lib/preview/annotation";
import { asRecord } from "@/lib/utils";

/**
 * 对话面 store（issue #19 需求环①，SSE 相关 store——桥为唯一事件写入方，
 * ADR 0003 状态三分法）：按项目累积对话面（用户发言 / 智能体回复增量 / 问答卡 /
 * 收尾卡 / 平台轻引导 / 受理动作卡），区别于 agent-runs 的「运行注册表」——对话史
 * 跨 run 常驻。<b>#89 起对话史落库</b>：闭史以 REST 水合为准（hydrate 按库序
 * 应用），live 事件只承载在途增量（重放缓冲降级断线补发——新连接不重放，刷新
 * 重建归水合；作答文本随落库不再「即逝」）。
 *
 * <p><b>对话面 run 判定（#86 单会话收敛后）</b>：run-start 携智能体配置键
 * （agent=main）即登记为对话面 run——界面上只有一个「它」，无角色分支；对话
 * 事件的归属判定一律 runId 锚定（chatRunIds），不看会话前缀（后端 ba-/assist-
 * 派生会话已并入单会话 main-{projectId}，前端无从也无需判定）。编码 run 的
 * 解说不进对话（过程长在工作消息）；编码 run 真收口的<b>收尾卡</b>归对话流
 * （#89：闭史的常驻位——live 到达经 appendClosing、回访经水合，同一 runId
 * 退位去重）。</p>
 *
 * <p><b>无角色标签</b>（#86 终态口径，用户故事「界面上只有一个它」+ ADR 0006）：
 * 智能体话语不带任何署名标签；平台轻引导（guide-reply）自带 label（「平台」，
 * 平台自己说话、非智能体角色）是唯一带标签的对话消息。</p>
 *
 * <p><b>水合合并（#89）</b>：hydrate 增量应用新 run 的库条目——该 run 的 live
 * 片段原位退位（闭史接管，位置不跳）、开放轮（openRunId——进行中/挂起问答的
 * 轮）条目跳过不应用（live 尾巴权威，轮收口后下一次水合接管）。重放幂等沿用：
 * text / 问答 / 失败 / 收尾卡事件按 SSE 事件 id 只收一次。</p>
 */

/** guide-reply 事件缺 label 时的呈现兜底（正本在后端 GUIDE_LABEL）。 */
export const DEFAULT_GUIDE_LABEL = "平台";

/**
 * 收尾卡权威事实（#88 收口扩载的载荷形状，#89 起归对话流）：四要素 = 摘要
 * （summary）/判定行（prd 与 system 两组布尔+说明——服务端权威值）/变更清单
 * （files，文件级）/轮末统计（durationMs；文件数与变更行数由 files 派生）。
 * live 经 run-finish.closing 到达、回访经对话史水合——同载荷同形。
 */
export type WorkClosing = {
  summary: string;
  prdChanged: boolean;
  prdNote?: string;
  systemChanged: boolean;
  systemNote?: string;
  files: { path: string; added: number; removed: number }[];
  durationMs: number;
  /** 成版 commit hash（#91 收口自动成版回填；成版失败缺省）——「查看当时/回滚到此」的寻址锚。 */
  version?: string;
  /** 自测统计（#96 自测子智能体清单式播报的收尾统计）：可缺省——自测子智能体未跑时不携带。
   *  只记「自测跑了几项」——逐项 ✅/❌ 明细在过程播报里，收尾卡不带通过/未过伪判。 */
  selfTest?: { total: number };
};

/** 对话史条目（#89 水合载荷——GET /projects/{id}/conversation 读面消费口径）。 */
export type HydratedEntry = {
  /** 库写入序（对话序正本）。 */
  id: number;
  kind: "user" | "agent" | "question" | "answer" | "closing" | "guide";
  runId?: string | null;
  text?: string | null;
  question?: Record<string, unknown> | null;
  closing?: Record<string, unknown> | null;
  /** 圈注附件（#97 随用户发言落库，JSON 数组；消息回显重建圈注 chip 用）。 */
  attachments?: Record<string, unknown>[] | null;
  answered: boolean;
};

export type ChatMessage =
  | {
      /** 用户发言；annotations = 随发言发送的圈注条目（#97 回显 chip）。 */
      kind: "user";
      id: string;
      text: string;
      runId?: string;
      annotations?: AnnotationDraft[];
    }
  | {
      /** 智能体话语与平台轻引导；runId 锚增量合并（同 run 才拼接）。label 仅
       *  平台轻引导携带（「平台」——智能体话语无标签）。 */
      kind: "agent";
      id: string;
      text: string;
      label?: string;
      runId?: string;
    }
  | {
      /**
       * 收尾卡（#88 定格收口，#89 起归对话流常驻）：编码 run 真收口的凝聚物——
       * live 经 run-finish.closing 到达（id = 事件 id，重放去重），回访经对话史
       * 水合（id = 库条目 id，退位合并的锚之一）。「查看当时/回滚到此」版本控件
       * 归版本层（#91）。
       */
      kind: "closing";
      id: string;
      runId?: string;
      closing: WorkClosing;
    }
  | {
      /**
       * 受理动作卡（#87）：受理轮（迭代期意见轮）开场的受理锚——意见已接住、
       * 正在受理（追问或改 PRD 的过程呈现位，衔接更新 run 工作消息）。id = 受理
       * 事件 id（重放去重）；落定（settled）由该轮收口事件推导（run-finish /
       * error），挂起追问不落定（同一受理轮仍在途）。
       */
      kind: "acceptance";
      id: string;
      runId: string;
      settled: boolean;
    }
  | { kind: "error"; id: string; text: string; runId?: string }
  | (RaisedQuestion & { kind: "question"; answered: boolean });

export type ProjectChat = {
  messages: ChatMessage[];
  /** run-start(agent=main) 登记的对话面 run（用户气泡与对话事件归属的判定锚）。 */
  chatRunIds: string[];
  /** 已折算成对话事件的 run（run-start 重放 / 回声去重锚）。 */
  ingestedRunIds: string[];
  /** 已收事件的 SSE 事件 id（重放去重锚，有界）。 */
  seenEventIds: string[];
  /** 对话轮进行中（run-start / 作答续跑起，问答挂起或收口落）。 */
  turnActive: boolean;
  /** 进行中的对话轮 run（水合合并的开放尾巴锚——挂起问答/流式中；收口即清）。 */
  openRunId?: string;
};

export type ChatState = {
  chats: Record<string, ProjectChat>;
  // ---- SSE 侧（bridge 唯一写入方） ----
  /** run-start(agent=main) 登记对话面 run。 */
  noteChatRun: (projectId: string, runId: string) => void;
  ingestRunStart: (projectId: string, runId: string, prompt?: string) => void;
  appendAgentDelta: (
    projectId: string,
    runId: string | undefined,
    delta: unknown,
    eventId: string,
  ) => void;
  raiseQuestion: (projectId: string, runId: string | undefined, question: RaisedQuestion) => void;
  finishTurn: (projectId: string, runId: string | undefined) => void;
  noteTurnError: (projectId: string, runId: string, message: string, eventId: string) => void;
  /** 收尾卡落对话流（#88 定格收口，#89 归对话流常驻；SSE 事件 id 只收一次）。 */
  appendClosing: (
    projectId: string,
    runId: string,
    closing: WorkClosing,
    eventId: string,
  ) => void;
  /** 受理动作卡落卡（#87；SSE 事件 id 只收一次——先于 run-start 到达，不设 run 登记）。 */
  noteAcceptance: (projectId: string, runId: string, eventId: string) => void;
  /** 受理卡落定（#87：该受理轮收口——run-finish / error；幂等，异 runId 无操作）。 */
  settleAcceptance: (projectId: string, runId: string) => void;
  /** 平台轻引导落对话面（#47 兜底分支；prompt 重建用户气泡，SSE 事件 id 只收一次）。 */
  noteGuideReply: (
    projectId: string,
    runId: string,
    prompt: string | undefined,
    label: string | undefined,
    text: string,
    eventId: string,
  ) => void;
  /** 对话史水合（#89）：库条目增量应用（新 run 原位退位 live 片段，开放轮跳过）。 */
  hydrate: (projectId: string, entries: HydratedEntry[]) => void;
  // ---- 发送侧（hooks） ----
  /** 乐观落用户气泡（返回消息 id；失败经 {@link removeMessage} 撤回）。annotations
   *  = 随发言发送的圈注条目（#97 回显 chip）。 */
  appendUserMessage: (projectId: string, text: string, annotations?: AnnotationDraft[]) => string;
  /** 作答落定：用户气泡 + 问题卡转已答 + 轮进行中。 */
  submitAnswer: (projectId: string, text: string, runId: string) => string;
  /** 发言起轮（智能体将回复；run-start 回声会被去重）。 */
  startTurn: (projectId: string) => void;
  /** 发送失败收轮（无会话锚的落轮口，区别于 SSE 侧 finishTurn 的 runId 判定）。 */
  endTurn: (projectId: string) => void;
  removeMessage: (projectId: string, messageId: string) => void;
  /** 发送失败撤尾卡（#87）：撤回乐观气泡时尾随的未落定受理卡一并撤——受理事件
   *  先于提交失败发出时（REST 500），该轮无 run-finish/error 可落定，随气泡同撤。 */
  removeTrailingAcceptance: (projectId: string) => void;
  /** 作答发送失败：撤回用户气泡 + 问题卡重开。 */
  reopenQuestion: (projectId: string) => void;
  markRunIngested: (projectId: string, runId: string) => void;
};

/** 消息条数软上限（对话史内存有界，库侧全量、前端软切）。 */
const MAX_MESSAGES = 200;
/** run / 事件 id 去重集软上限。 */
const MAX_IDS = 500;

const emptyChat: ProjectChat = {
  messages: [],
  chatRunIds: [],
  ingestedRunIds: [],
  seenEventIds: [],
  turnActive: false,
};

function chatOf(state: ChatState, projectId: string): ProjectChat {
  return state.chats[projectId] ?? emptyChat;
}

function pushCapped(list: string[], id: string): string[] {
  if (list.includes(id)) return list;
  const next = [...list, id];
  return next.length > MAX_IDS ? next.slice(next.length - MAX_IDS) : next;
}

let messageSeq = 0;
function localId(): string {
  messageSeq += 1;
  return `m${messageSeq}`;
}

/** 尾条同文去重（run-start / guide-reply 回声 vs 乐观发送的等价气泡）。 */
function lastIsSameUserText(chat: ProjectChat, text: string): boolean {
  const last = chat.messages[chat.messages.length - 1];
  return last !== undefined && last.kind === "user" && last.text === text;
}

/** 用户气泡落位（#87）：尾部的同 run 受理卡之前插入——重放重建时受理事件先于
 *  run-start 到达，意见气泡仍落在其受理卡上方（卡承接的是这条意见）。 */
function insertUserMessage(
  chat: ProjectChat,
  runId: string,
  text: string,
): ProjectChat {
  const messages = [...chat.messages];
  let insertAt = messages.length;
  while (insertAt > 0) {
    const prev = messages[insertAt - 1];
    if (prev.kind === "acceptance" && prev.runId === runId) {
      insertAt--;
    } else {
      break;
    }
  }
  messages.splice(insertAt, 0, { kind: "user", id: localId(), text, runId });
  return { ...chat, messages };
}

/** 尾条未锚定的用户气泡补 runId（POST 成功回填——水合退位的合并锚）。 */
function stampTrailingUserRun(chat: ProjectChat, runId: string): ProjectChat {
  const last = chat.messages[chat.messages.length - 1];
  if (last === undefined || last.kind !== "user" || last.runId !== undefined) return chat;
  const messages = chat.messages.slice(0, -1);
  messages.push({ ...last, runId });
  return { ...chat, messages };
}

/**
 * 库条目 → 对话消息（#89 水合转换）：answer 渲染同用户气泡；question 复用
 * question-raised 解析（载荷原样存储——问答卡可重建可作答）；closing 载荷容错
 * 收窄（异常形状视同无卡，不出坏卡）。不可解析条目丢弃（null）。
 */
function hydratedMessage(entry: HydratedEntry): ChatMessage | null {
  const runId = entry.runId ?? undefined;
  switch (entry.kind) {
    case "user":
    case "answer":
      return {
        kind: "user",
        id: `h${entry.id}`,
        text: entry.text ?? "",
        runId,
        annotations: parseHydratedAnnotations(entry.attachments),
      };
    case "agent":
      return { kind: "agent", id: `h${entry.id}`, text: entry.text ?? "", runId };
    case "guide":
      return {
        kind: "agent",
        id: `h${entry.id}`,
        text: entry.text ?? "",
        label: DEFAULT_GUIDE_LABEL,
        runId,
      };
    case "closing": {
      const closing = toWorkClosing(entry.closing);
      return closing ? { kind: "closing", id: `h${entry.id}`, runId, closing } : null;
    }
    case "question": {
      const question = parseQuestion(`h${entry.id}`, {
        runId: entry.runId ?? "",
        ...(entry.question as Record<string, unknown> | undefined),
      });
      return question ? { ...question, kind: "question", answered: entry.answered } : null;
    }
    default:
      return null;
  }
}

/** 对话史附件数组 → 圈注条目（#97 容错收窄：非圈注/坏形状条目丢弃）。 */
function parseHydratedAnnotations(
  attachments: Record<string, unknown>[] | null | undefined,
): AnnotationDraft[] {
  if (!attachments) return [];
  return attachments.flatMap((raw) => {
    const draft = parseAnnotationAttachment(raw);
    return draft ? [draft] : [];
  });
}

/** 末条目即开放轮尾（流式中发言/作答，或挂起未答问答卡）——该 run 的 live 尾巴权威。 */
function isOpenTail(entry: HydratedEntry | undefined): boolean {
  if (!entry?.runId) return false;
  return (
    entry.kind === "user" ||
    entry.kind === "answer" ||
    (entry.kind === "question" && !entry.answered)
  );
}

export const useChatStore = create<ChatState>((set) => ({
  chats: {},

  noteChatRun: (projectId, runId) =>
    updateChat(set, projectId, (chat) =>
      chat.chatRunIds.includes(runId)
        ? chat
        : { ...chat, chatRunIds: pushCapped(chat.chatRunIds, runId) },
    ),

  ingestRunStart: (projectId, runId, prompt) =>
    updateChat(set, projectId, (chat) => {
      if (chat.ingestedRunIds.includes(runId) || !chat.chatRunIds.includes(runId)) return chat;
      const stamped = stampTrailingUserRun(chat, runId);
      const ingested = {
        ...stamped,
        ingestedRunIds: pushCapped(stamped.ingestedRunIds, runId),
        turnActive: true,
        openRunId: runId,
      };
      if (!prompt || lastIsSameUserText(ingested, prompt)) return ingested;
      // 受理事件先于 run-start 到达（#87：服务端守卫后即发）——重放重建时用户
      // 气泡插到本 run 受理卡之前（意见在卡上，卡承接的是这条意见）
      return insertUserMessage(ingested, runId, prompt);
    }),

  appendAgentDelta: (projectId, runId, delta, eventId) =>
    updateChat(set, projectId, (chat) => {
      // 对话事件归属 = runId 锚定（对话面 run 才进对话——编码 run 的解说长在
      // 工作消息）
      if (runId === undefined || !chat.chatRunIds.includes(runId)) return chat;
      if (typeof delta !== "string" || !delta) return chat;
      if (chat.seenEventIds.includes(eventId)) return chat;
      const seen = { ...chat, seenEventIds: pushCapped(chat.seenEventIds, eventId) };
      const last = seen.messages[seen.messages.length - 1];
      // 同 run 的连续增量拼接成一条（runId 锚定——跨 run 交错不互并）
      if (
        last !== undefined &&
        last.kind === "agent" &&
        last.label === undefined &&
        last.runId === runId
      ) {
        const messages = seen.messages.slice(0, -1);
        messages.push({ ...last, text: last.text + delta });
        return { ...seen, messages };
      }
      return appendMessage(seen, { kind: "agent", id: localId(), text: delta, runId });
    }),

  raiseQuestion: (projectId, runId, question) =>
    updateChat(set, projectId, (chat) => {
      if (runId === undefined) return chat;
      // 问答卡只出自主智能体（执行体无 ask_user）——question-raised 即对话面 run 的
      // 充分证据。run-start 可能因连接竞态漏收（SSE 连接晚于 run-start 发射），此时
      // runId 未登记：就地登记不丢卡（水合虽也能补登记，竞态窗口内不兜底）。
      const registered = chat.chatRunIds.includes(runId)
        ? chat
        : { ...chat, chatRunIds: pushCapped(chat.chatRunIds, runId) };
      if (registered.seenEventIds.includes(question.id)) return registered;
      // 水合已建同锚卡（挂起问答卡由库重建）——事件回声不双卡
      const hydrated = registered.messages.some(
        (message) =>
          message.kind === "question" && !message.answered && message.engineRef === question.engineRef,
      );
      if (hydrated) return registered;
      const seen = { ...registered, seenEventIds: pushCapped(registered.seenEventIds, question.id) };
      // 旧未答问题被新问题取代（一轮一问）：转已答不再可交互
      const messages = seen.messages.map((message) =>
        message.kind === "question" && !message.answered
          ? { ...message, answered: true }
          : message,
      );
      return appendMessage(
        { ...seen, messages, turnActive: false, openRunId: runId },
        { ...question, kind: "question", answered: false },
      );
    }),

  finishTurn: (projectId, runId) =>
    updateChat(set, projectId, (chat) =>
      runId !== undefined && chat.chatRunIds.includes(runId)
        ? { ...chat, turnActive: false, openRunId: chat.openRunId === runId ? undefined : chat.openRunId }
        : chat,
    ),

  noteTurnError: (projectId, runId, message, eventId) =>
    updateChat(set, projectId, (chat) => {
      if (!chat.chatRunIds.includes(runId) || chat.seenEventIds.includes(eventId)) return chat;
      return appendMessage(
        {
          ...chat,
          seenEventIds: pushCapped(chat.seenEventIds, eventId),
          turnActive: false,
          openRunId: chat.openRunId === runId ? undefined : chat.openRunId,
        },
        { kind: "error", id: localId(), text: message || "本轮回复失败", runId },
      );
    }),

  appendClosing: (projectId, runId, closing, eventId) =>
    updateChat(set, projectId, (chat) => {
      // 收尾卡（#89 归对话流）：live 到达即常驻（断线补发窗口内重复投递按事件 id
      // 只收一次；水合同 run 块整体接管时原位退位）
      if (chat.seenEventIds.includes(eventId)) return chat;
      if (chat.messages.some((message) => message.kind === "closing" && message.runId === runId)) {
        return chat;
      }
      return appendMessage(
        { ...chat, seenEventIds: pushCapped(chat.seenEventIds, eventId) },
        { kind: "closing", id: eventId, runId, closing },
      );
    }),

  noteGuideReply: (projectId, runId, prompt, label, text, eventId) =>
    updateChat(set, projectId, (chat) => {
      // 平台轻引导（#47 兜底分支）：即时到达即收轮（乐观起轮的对称收口）
      if (chat.seenEventIds.includes(eventId)) return chat;
      const seen = {
        ...chat,
        seenEventIds: pushCapped(chat.seenEventIds, eventId),
        turnActive: false,
      };
      // 重放重建：prompt 落用户气泡（乐观发送已落时尾条同文去重）
      const withUser =
        prompt && !lastIsSameUserText(seen, prompt)
          ? appendMessage(seen, { kind: "user", id: localId(), text: prompt, runId })
          : seen;
      return appendMessage(withUser, {
        kind: "agent",
        id: localId(),
        text,
        label: label || DEFAULT_GUIDE_LABEL,
        runId,
      });
    }),

  hydrate: (projectId, entries) =>
    updateChat(set, projectId, (chat) => {
      if (entries.length === 0) return chat;
      // 开放轮（live 尾巴权威）条目跳过；其余 run 的库块整体接管
      const applied = entries.filter((entry) => entry.runId && entry.runId !== chat.openRunId);
      const runIds = [...new Set(applied.map((entry) => entry.runId))] as string[];
      if (runIds.length === 0) return chat;
      // 退位：被接管 run 的 live / 已水合消息原位移除（按 run 整体替换——幂等，
      // 库块重放不双条），记最早退位位为插入位
      let insertAt = chat.messages.length;
      const kept: ChatMessage[] = [];
      for (const message of chat.messages) {
        if (message.runId && runIds.includes(message.runId)) {
          if (kept.length < insertAt) insertAt = kept.length;
          continue;
        }
        kept.push(message);
      }
      // 插入位不越过开放尾巴（被接管条目必旧于开放轮——收口即清 openRunId）
      const openIdx = kept.findIndex((message) => message.runId === chat.openRunId);
      if (openIdx >= 0 && openIdx < insertAt) insertAt = openIdx;
      const messages = [...kept];
      messages.splice(
        insertAt,
        0,
        ...applied.flatMap((entry) => {
          const message = hydratedMessage(entry);
          return message ? [message] : [];
        }),
      );
      // 开放轮判定（#89）：末条目为流式中发言/作答或未答问答卡 → 该 run 开放
      // （后续水合跳过其条目，live 流式/挂起卡不被动塌；轮收口事件清锚后接管）
      const last = entries[entries.length - 1];
      const openRunId = isOpenTail(last) ? last.runId ?? undefined : chat.openRunId;
      return {
        ...chat,
        messages: messages.length > MAX_MESSAGES ? messages.slice(messages.length - MAX_MESSAGES) : messages,
        chatRunIds: runIds.reduce((ids, id) => pushCapped(ids, id), chat.chatRunIds),
        ingestedRunIds: runIds.reduce((ids, id) => pushCapped(ids, id), chat.ingestedRunIds),
        openRunId,
      };
    }),

  noteAcceptance: (projectId, runId, eventId) =>
    updateChat(set, projectId, (chat) => {
      // 受理事件先于 run-start 到达（动作卡先出、解说随后）——不设 chatRunIds
      // 登记，SSE 事件 id 即去重锚
      if (chat.seenEventIds.includes(eventId)) return chat;
      const seen = { ...chat, seenEventIds: pushCapped(chat.seenEventIds, eventId) };
      return appendMessage(seen, {
        kind: "acceptance",
        id: eventId,
        runId,
        settled: false,
      });
    }),

  settleAcceptance: (projectId, runId) =>
    updateChat(set, projectId, (chat) => {
      const target = chat.messages.find(
        (message) => message.kind === "acceptance" && message.runId === runId && !message.settled,
      );
      if (!target) return chat; // 无该轮受理卡（咨询/纯追问轮）或已落定——幂等
      const messages = chat.messages.map((message) =>
        message === target ? { ...target, settled: true } : message,
      );
      return { ...chat, messages };
    }),

  appendUserMessage: (projectId, text, annotations = []) => {
    const id = localId();
    updateChat(set, projectId, (chat) =>
      appendMessage(chat, { kind: "user", id, text, annotations }),
    );
    return id;
  },

  submitAnswer: (projectId, text, runId) => {
    const id = localId();
    updateChat(set, projectId, (chat) => {
      const messages = chat.messages.map((message) =>
        message.kind === "question" && !message.answered
          ? { ...message, answered: true }
          : message,
      );
      return appendMessage(
        { ...chat, messages, turnActive: true },
        { kind: "user", id, text, runId },
      );
    });
    return id;
  },

  startTurn: (projectId) => updateChat(set, projectId, (chat) => ({ ...chat, turnActive: true })),

  endTurn: (projectId) => updateChat(set, projectId, (chat) => ({ ...chat, turnActive: false })),

  removeMessage: (projectId, messageId) =>
    updateChat(set, projectId, (chat) => ({
      ...chat,
      messages: chat.messages.filter((message) => message.id !== messageId),
    })),

  removeTrailingAcceptance: (projectId) =>
    updateChat(set, projectId, (chat) => {
      const last = chat.messages[chat.messages.length - 1];
      if (last === undefined || last.kind !== "acceptance" || last.settled) return chat;
      return {
        ...chat,
        messages: chat.messages.slice(0, -1),
      };
    }),

  reopenQuestion: (projectId) =>
    updateChat(set, projectId, (chat) => {
      for (let i = chat.messages.length - 1; i >= 0; i--) {
        const message = chat.messages[i];
        if (message.kind === "question") {
          const messages = chat.messages.slice();
          messages[i] = { ...message, answered: false };
          return { ...chat, messages, turnActive: false };
        }
      }
      return { ...chat, turnActive: false };
    }),

  markRunIngested: (projectId, runId) =>
    updateChat(set, projectId, (chat) => {
      const stamped = stampTrailingUserRun(chat, runId);
      return stamped.ingestedRunIds.includes(runId)
        ? stamped
        : { ...stamped, ingestedRunIds: pushCapped(stamped.ingestedRunIds, runId) };
    }),
}));

/** 当前待答问题（最后一条未答问答卡；无则 undefined）。 */
export function pendingQuestionOf(
  state: Pick<ChatState, "chats">,
  projectId: string,
): Extract<ChatMessage, { kind: "question" }> | undefined {
  const messages = state.chats[projectId]?.messages ?? [];
  for (let i = messages.length - 1; i >= 0; i--) {
    const message = messages[i];
    if (message.kind === "question") return message.answered ? undefined : message;
  }
  return undefined;
}

function appendMessage(chat: ProjectChat, message: ChatMessage): ProjectChat {
  const messages = [...chat.messages, message];
  return {
    ...chat,
    messages: messages.length > MAX_MESSAGES ? messages.slice(messages.length - MAX_MESSAGES) : messages,
  };
}

/**
 * closing 载荷的容错收窄（#88/#89：live 事件与水合同形）：类型镜像只做信任转型、
 * 缺字段容错归消费端——只兜「载荷非对象」的形状异常（视同无收尾卡，不出坏卡）；
 * files 非数组回落空清单。
 */
export function toWorkClosing(raw: unknown): WorkClosing | undefined {
  const record = asRecord(raw);
  if (!record) return undefined;
  return {
    summary: typeof record.summary === "string" ? record.summary : "",
    prdChanged: record.prdChanged === true,
    prdNote: typeof record.prdNote === "string" ? record.prdNote : undefined,
    systemChanged: record.systemChanged === true,
    systemNote: typeof record.systemNote === "string" ? record.systemNote : undefined,
    files: Array.isArray(record.files)
      ? record.files.flatMap((file) => {
          const entry = asRecord(file);
          return entry && typeof entry.path === "string"
            ? [{
                path: entry.path,
                added: typeof entry.added === "number" ? entry.added : 0,
                removed: typeof entry.removed === "number" ? entry.removed : 0,
              }]
            : [];
        })
      : [],
    durationMs: typeof record.durationMs === "number" ? record.durationMs : 0,
    version: typeof record.version === "string" ? record.version : undefined,
    selfTest: toSelfTest(record.selfTest),
  };
}

/** selfTest 载荷容错收窄（#96）：非对象或 total 为 0（无自测动作）回落 undefined。 */
function toSelfTest(raw: unknown): { total: number } | undefined {
  const record = asRecord(raw);
  if (!record) return undefined;
  const total = typeof record.total === "number" ? record.total : 0;
  return total > 0 ? { total } : undefined;
}

type SetFn = (partial: Partial<ChatState>) => void;

function updateChat(set: SetFn, projectId: string, mutate: (chat: ProjectChat) => ProjectChat): void {
  const state = useChatStore.getState();
  const current = chatOf(state, projectId);
  const next = mutate(current);
  if (next === current) return;
  set({ chats: { ...state.chats, [projectId]: next } });
}
