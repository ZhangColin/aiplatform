/**
 * 问答卡纯逻辑（issue #19 需求环①）：question-raised 帧 data → 问答卡形状的解析，
 * 与三种作答形态（单选点即答 / 多选勾选提交 / 自由输入可与已勾选合并）的答复
 * 拼装。形状正本 = SSE事件清单·通道二 `data.questions` 投影
 * （[{header, question, multiple, custom, options[{label}]}]）。
 */

import type { components } from "@/lib/api/schema";

/** 作答端点回传的待确认工具形状（swagger AnswerQuestionCommand.toolCalls 元素）。 */
type AnswerToolCall = NonNullable<components["schemas"]["AnswerQuestionCommand"]["toolCalls"]>[number];

/** 待确认工具（挂起帧 data.toolCalls 元素原样——作答端点回传面，前端不解释）。 */
export type PendingToolCall = {
  id?: unknown;
  name?: unknown;
  input?: unknown;
};

/** question-raised 帧载荷面（事件名册 question-raised 行；data 为引擎载荷原样）。 */
export type QuestionRaisedPayload = {
  runId: string;
  sessionId?: string;
  kind?: string;
  engineRef?: string;
  data?: unknown;
};

/** 问答卡数据面：呈现字段 + 作答端点回传面。 */
export type RaisedQuestion = {
  /** SSE 事件 id（React key + 重放去重锚）。 */
  id: string;
  runId: string;
  /** 续跑批复的锚（作答端点路径 qid）。 */
  engineRef: string;
  header: string;
  question: string;
  multiple: boolean;
  options: string[];
  toolCalls: PendingToolCall[];
};

type QuestionProjection = {
  header?: unknown;
  question?: unknown;
  multiple?: unknown;
  options?: unknown;
};

/**
 * question-raised → 问答卡：kind=QUESTION 且 data.questions[0] 可解析才成卡
 * （PERMISSION 挂起 / 形状残缺 → null，指令区不呈现交互卡）。选项取 label；
 * 无选项纯开放题照成卡（custom 恒 true，自由输入作答）。
 */
export function parseQuestion(
  eventId: string,
  payload: QuestionRaisedPayload,
): RaisedQuestion | null {
  if (payload.kind !== "QUESTION" || !payload.engineRef) return null;
  const data = asRecord(payload.data);
  const first = asRecord(data && Array.isArray(data.questions) ? data.questions[0] : null);
  if (!first) return null;
  const projection = first as QuestionProjection;
  const options = Array.isArray(projection.options)
    ? projection.options
        .map((option) => labelOf(option))
        .filter((label): label is string => label !== null)
    : [];
  return {
    id: eventId,
    runId: payload.runId,
    engineRef: payload.engineRef,
    header: textOr(projection.header, "提问"),
    question: textOr(projection.question, ""),
    multiple: projection.multiple === true,
    options,
    toolCalls: (data && Array.isArray(data.toolCalls) ? data.toolCalls : []).map(
      (call) => call as PendingToolCall,
    ),
  };
}

/**
 * 答复拼装：勾选 label + 自由输入按序以「；」连接（可与已勾选合并）；全空返回
 * 空串（调用方禁用提交）。
 */
export function composeAnswer(selected: readonly string[], typed: string): string {
  return [...selected, typed.trim()].filter(Boolean).join("；");
}

/** 多选勾选切换（单选点即答不走此——直接以 label 作答）。 */
export function toggleSelection(current: readonly string[], label: string): string[] {
  return current.includes(label)
    ? current.filter((item) => item !== label)
    : [...current, label];
}

/** 挂起帧 data.toolCalls → 作答命令形状（回传面原样收窄：字符串/对象之外弃守）。 */
export function toAnswerToolCalls(calls: readonly PendingToolCall[]): AnswerToolCall[] {
  return calls.map((call) => ({
    id: typeof call.id === "string" ? call.id : undefined,
    name: typeof call.name === "string" ? call.name : undefined,
    input: asRecord(call.input)
      ? (call.input as AnswerToolCall["input"])
      : undefined,
  }));
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null
    ? (value as Record<string, unknown>)
    : null;
}

function labelOf(option: unknown): string | null {
  const record = asRecord(option);
  return record && typeof record.label === "string" && record.label ? record.label : null;
}

function textOr(value: unknown, fallback: string): string {
  return typeof value === "string" && value ? value : fallback;
}
