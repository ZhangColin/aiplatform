import { create } from "zustand";

import type { RaisedQuestion } from "@/lib/chat/qa";

/**
 * 指令区对话 store（issue #19 需求环①，SSE 相关 store——桥为唯一事件写入方，
 * ADR 0003 状态三分法）：按项目累积对话面（用户发言 / BA 回复增量 / 问答卡），
 * 区别于 agent-streams 的「过程层最近 1 run」——对话史跨 run 常驻（store 是
 * 会话内存态，刷新后由 agent 流通道重放缓冲重建近期对话，用户作答文本不在流中、
 * 刷新即逝为 v1 取舍）。
 *
 * <p><b>BA 判定</b>：role-assigned(role=BA) 登记 baRunIds（run-start 的用户
 * 气泡只认 BA run，片 2 编码 run 不进对话）；透传帧自带 sessionId，
 * {@code ba-{projectId}} 前缀（后端会话命名约定：角色 × 项目）判定 text /
 * question-raised / run-finish 归 BA。</p>
 *
 * <p><b>重放幂等</b>：通道是带缓冲热流，重新挂载（含路由回访）会重收近期帧——
 * runId 已入对话的 run-start 不再补用户气泡（乐观发送先落、run-start 回声
 * 靠「尾条同文」去重），text / 问答 / 失败帧按 SSE 事件 id 只收一次。</p>
 */

export type ChatMessage =
  | { kind: "user"; id: string; text: string }
  | { kind: "ba"; id: string; text: string }
  | { kind: "error"; id: string; text: string }
  | (RaisedQuestion & { kind: "question"; answered: boolean });

type ProjectChat = {
  messages: ChatMessage[];
  /** role-assigned(role=BA) 登记的 run（BA 判定锚一）。 */
  baRunIds: string[];
  /** 已折算成对话事件的 run（run-start 重放 / 回声去重锚）。 */
  ingestedRunIds: string[];
  /** 已收帧的 SSE 事件 id（重放去重锚，有界）。 */
  seenEventIds: string[];
  /** BA 轮进行中（run-start / 作答续跑起，问答挂起或收口落）。 */
  turnActive: boolean;
};

export type ChatState = {
  chats: Record<string, ProjectChat>;
  // ---- SSE 侧（bridge 唯一写入方） ----
  noteBaRun: (projectId: string, runId: string) => void;
  ingestRunStart: (projectId: string, runId: string, prompt?: string) => void;
  appendBaDelta: (
    projectId: string,
    sessionId: string | undefined,
    delta: unknown,
    eventId: string,
  ) => void;
  raiseQuestion: (
    projectId: string,
    sessionId: string | undefined,
    question: RaisedQuestion,
  ) => void;
  finishTurn: (projectId: string, sessionId: string | undefined) => void;
  noteTurnError: (projectId: string, runId: string, message: string, eventId: string) => void;
  // ---- 发送侧（hooks） ----
  /** 乐观落用户气泡（返回消息 id；失败经 {@link removeMessage} 撤回）。 */
  appendUserMessage: (projectId: string, text: string) => string;
  /** 作答落定：用户气泡 + 问题卡转已答 + 轮进行中。 */
  submitAnswer: (projectId: string, text: string) => string;
  /** 发言起轮（BA 将回复；run-start 回声会被去重）。 */
  startTurn: (projectId: string) => void;
  /** 发送失败收轮（无会话锚的落轮口，区别于 SSE 侧 finishTurn 的 BA 会话判定）。 */
  endTurn: (projectId: string) => void;
  removeMessage: (projectId: string, messageId: string) => void;
  /** 作答发送失败：撤回用户气泡 + 问题卡重开。 */
  reopenQuestion: (projectId: string) => void;
  markRunIngested: (projectId: string, runId: string) => void;
};

/** 消息条数软上限（重放缓冲 ~1000 帧，对话史内存有界）。 */
const MAX_MESSAGES = 200;
/** run / 事件 id 去重集软上限。 */
const MAX_IDS = 500;

const emptyChat: ProjectChat = {
  messages: [],
  baRunIds: [],
  ingestedRunIds: [],
  seenEventIds: [],
  turnActive: false,
};

function chatOf(state: ChatState, projectId: string): ProjectChat {
  return state.chats[projectId] ?? emptyChat;
}

function isBaSession(sessionId: string | undefined, projectId: string): boolean {
  return sessionId === `ba-${projectId}`;
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

/** 尾条同文去重（run-start 回声 vs 乐观发送的等价气泡）。 */
function lastIsSameUserText(chat: ProjectChat, text: string): boolean {
  const last = chat.messages[chat.messages.length - 1];
  return last !== undefined && last.kind === "user" && last.text === text;
}

export const useChatStore = create<ChatState>((set) => ({
  chats: {},

  noteBaRun: (projectId, runId) =>
    updateChat(set, projectId, (chat) =>
      chat.baRunIds.includes(runId) ? chat : { ...chat, baRunIds: pushCapped(chat.baRunIds, runId) },
    ),

  ingestRunStart: (projectId, runId, prompt) =>
    updateChat(set, projectId, (chat) => {
      if (chat.ingestedRunIds.includes(runId) || !chat.baRunIds.includes(runId)) return chat;
      const ingested = { ...chat, ingestedRunIds: pushCapped(chat.ingestedRunIds, runId), turnActive: true };
      if (!prompt || lastIsSameUserText(chat, prompt)) return ingested;
      return appendMessage(ingested, { kind: "user", id: localId(), text: prompt });
    }),

  appendBaDelta: (projectId, sessionId, delta, eventId) =>
    updateChat(set, projectId, (chat) => {
      if (!isBaSession(sessionId, projectId) || typeof delta !== "string" || !delta) return chat;
      if (chat.seenEventIds.includes(eventId)) return chat;
      const seen = { ...chat, seenEventIds: pushCapped(chat.seenEventIds, eventId) };
      const last = seen.messages[seen.messages.length - 1];
      if (last !== undefined && last.kind === "ba") {
        const messages = seen.messages.slice(0, -1);
        messages.push({ ...last, text: last.text + delta });
        return { ...seen, messages };
      }
      return appendMessage(seen, { kind: "ba", id: localId(), text: delta });
    }),

  raiseQuestion: (projectId, sessionId, question) =>
    updateChat(set, projectId, (chat) => {
      if (!isBaSession(sessionId, projectId) || chat.seenEventIds.includes(question.id)) return chat;
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

  finishTurn: (projectId, sessionId) =>
    updateChat(set, projectId, (chat) =>
      isBaSession(sessionId, projectId) ? { ...chat, turnActive: false } : chat,
    ),

  noteTurnError: (projectId, runId, message, eventId) =>
    updateChat(set, projectId, (chat) => {
      if (!chat.baRunIds.includes(runId) || chat.seenEventIds.includes(eventId)) return chat;
      return appendMessage(
        {
          ...chat,
          seenEventIds: pushCapped(chat.seenEventIds, eventId),
          turnActive: false,
        },
        { kind: "error", id: localId(), text: message || "本轮回复失败" },
      );
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
