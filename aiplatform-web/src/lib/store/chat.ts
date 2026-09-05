import { create } from "zustand";

import type { RaisedQuestion } from "@/lib/chat/qa";

/**
 * 对话面 store（issue #19 需求环①，SSE 相关 store——桥为唯一事件写入方，
 * ADR 0003 状态三分法）：按项目累积对话面（用户发言 / 智能体回复增量 / 问答卡 /
 * 平台轻引导），区别于 agent-runs 的「运行注册表」——对话史跨 run 常驻
 * （store 是会话内存态，刷新后由事件流重放缓冲重建近期对话，用户作答文本
 * 不在流中、刷新即逝为 v1 取舍）。
 *
 * <p><b>对话面 run 判定（#86 单会话收敛后）</b>：run-start 携智能体配置键
 * （agent=main）即登记为对话面 run——界面上只有一个「它」，无角色分支；对话
 * 事件的归属判定一律 runId 锚定（chatRunIds），不看会话前缀（后端 ba-/assist-
 * 派生会话已并入单会话 main-{projectId}，前端无从也无需判定）。编码 run 的
 * 解说不进对话（过程长在工作消息）。</p>
 *
 * <p><b>无角色标签</b>（#86 终态口径，用户故事「界面上只有一个它」+ ADR 0006）：
 * 智能体话语不带任何署名标签；平台轻引导（guide-reply）自带 label（「平台」，
 * 平台自己说话、非智能体角色）是唯一带标签的对话消息。</p>
 *
 * <p><b>重放幂等</b>：通道是带缓冲热流，重新挂载（含路由回访）会重收近期事件——
 * runId 已入对话的 run-start 不再补用户气泡（乐观发送先落、run-start 回声
 * 靠「尾条同文」去重），text / 问答 / 失败事件按 SSE 事件 id 只收一次。</p>
 */

/** guide-reply 事件缺 label 时的呈现兜底（正本在后端 GUIDE_LABEL）。 */
export const DEFAULT_GUIDE_LABEL = "平台";

export type ChatMessage =
  | { kind: "user"; id: string; text: string }
  | {
      /** 智能体话语与平台轻引导；runId 锚增量合并（同 run 才拼接）。label 仅
       *  平台轻引导携带（「平台」——智能体话语无标签）。 */
      kind: "agent";
      id: string;
      text: string;
      label?: string;
      runId?: string;
    }
  | { kind: "error"; id: string; text: string }
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
  /** 平台轻引导落对话面（#47 兜底分支；prompt 重建用户气泡，SSE 事件 id 只收一次）。 */
  noteGuideReply: (
    projectId: string,
    prompt: string | undefined,
    label: string | undefined,
    text: string,
    eventId: string,
  ) => void;
  // ---- 发送侧（hooks） ----
  /** 乐观落用户气泡（返回消息 id；失败经 {@link removeMessage} 撤回）。 */
  appendUserMessage: (projectId: string, text: string) => string;
  /** 作答落定：用户气泡 + 问题卡转已答 + 轮进行中。 */
  submitAnswer: (projectId: string, text: string) => string;
  /** 发言起轮（智能体将回复；run-start 回声会被去重）。 */
  startTurn: (projectId: string) => void;
  /** 发送失败收轮（无会话锚的落轮口，区别于 SSE 侧 finishTurn 的 runId 判定）。 */
  endTurn: (projectId: string) => void;
  removeMessage: (projectId: string, messageId: string) => void;
  /** 作答发送失败：撤回用户气泡 + 问题卡重开。 */
  reopenQuestion: (projectId: string) => void;
  markRunIngested: (projectId: string, runId: string) => void;
};

/** 消息条数软上限（重放缓冲 ~1000 事件，对话史内存有界）。 */
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
      const ingested = {
        ...chat,
        ingestedRunIds: pushCapped(chat.ingestedRunIds, runId),
        turnActive: true,
      };
      if (!prompt || lastIsSameUserText(ingested, prompt)) return ingested;
      return appendMessage(ingested, { kind: "user", id: localId(), text: prompt });
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
      // 问答卡只出自主智能体（对话面 run；执行体无 ask_user）——runId 锚定
      if (runId === undefined || !chat.chatRunIds.includes(runId)) return chat;
      if (chat.seenEventIds.includes(question.id)) return chat;
      const seen = { ...chat, seenEventIds: pushCapped(chat.seenEventIds, question.id) };
      // 旧未答问题被新问题取代（一轮一问）：转已答不再可交互
      const messages = seen.messages.map((message) =>
        message.kind === "question" && !message.answered
          ? { ...message, answered: true }
          : message,
      );
      return appendMessage(
        { ...seen, messages, turnActive: false },
        { ...question, kind: "question", answered: false },
      );
    }),

  finishTurn: (projectId, runId) =>
    updateChat(set, projectId, (chat) =>
      runId !== undefined && chat.chatRunIds.includes(runId)
        ? { ...chat, turnActive: false }
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
        },
        { kind: "error", id: localId(), text: message || "本轮回复失败" },
      );
    }),

  noteGuideReply: (projectId, prompt, label, text, eventId) =>
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
          ? appendMessage(seen, { kind: "user", id: localId(), text: prompt })
          : seen;
      return appendMessage(withUser, {
        kind: "agent",
        id: localId(),
        text,
        label: label || DEFAULT_GUIDE_LABEL,
      });
    }),

  appendUserMessage: (projectId, text) => {
    const id = localId();
    updateChat(set, projectId, (chat) =>
      appendMessage(chat, { kind: "user", id, text }),
    );
    return id;
  },

  submitAnswer: (projectId, text) => {
    const id = localId();
    updateChat(set, projectId, (chat) => {
      const messages = chat.messages.map((message) =>
        message.kind === "question" && !message.answered
          ? { ...message, answered: true }
          : message,
      );
      return appendMessage(
        { ...chat, messages, turnActive: true },
        { kind: "user", id, text },
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
    updateChat(set, projectId, (chat) =>
      chat.ingestedRunIds.includes(runId)
        ? chat
        : { ...chat, ingestedRunIds: pushCapped(chat.ingestedRunIds, runId) },
    ),
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

type SetFn = (partial: Partial<ChatState>) => void;

function updateChat(set: SetFn, projectId: string, mutate: (chat: ProjectChat) => ProjectChat): void {
  const state = useChatStore.getState();
  const current = chatOf(state, projectId);
  const next = mutate(current);
  if (next === current) return;
  set({ chats: { ...state.chats, [projectId]: next } });
}
