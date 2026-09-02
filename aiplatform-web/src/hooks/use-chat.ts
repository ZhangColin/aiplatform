import { useMutation } from "@tanstack/react-query";
import { toast } from "sonner";

import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/schema";
import { errorText } from "@/lib/api/api-error";
import { useChatStore } from "@/lib/store/chat";
import { useDispatchStageStore } from "@/lib/store/dispatch-stage";

/**
 * 指令区两发送口（issue #19 需求环①）：发言（POST /api/projects/{id}/messages，
 * 无待答问题时 Enter 走此）与问答作答（POST /api/projects/{id}/questions/{qid}/
 * answer，qid = 挂起帧 engineRef；body 回传 runId + data.toolCalls 原样 + 答复）。
 * 乐观更新都在 chat store（成功路径 REST/SSE 自愈；失败撤回气泡、问题卡重开）；
 * 发送即重置派发阶段条（#50：上一链终态不滞留，新链 analyzing 帧随后到达）。
 */

type PostMessageCommand = components["schemas"]["PostMessageCommand"];
type AnswerQuestionCommand = components["schemas"]["AnswerQuestionCommand"];
type InterviewTurnResponse = components["schemas"]["InterviewTurnResponse"];

export function usePostMessage(projectId: string) {
  return useMutation({
    mutationFn: (command: PostMessageCommand) =>
      api.post<InterviewTurnResponse>(`/projects/${projectId}/messages`, command),
    onMutate: ({ content }) => {
      // 乐观落用户气泡 + 起轮；runId 回来即入对话登记（run-start 回声不再补气泡）
      const chat = useChatStore.getState();
      const messageId = chat.appendUserMessage(projectId, content);
      chat.startTurn(projectId);
      useDispatchStageStore.getState().noteSubmitted(projectId);
      return { messageId };
    },
    onSuccess: (result) => {
      if (result?.runId) useChatStore.getState().markRunIngested(projectId, result.runId);
    },
    onError: (error, _command, context) => {
      const chat = useChatStore.getState();
      if (context?.messageId) chat.removeMessage(projectId, context.messageId);
      chat.endTurn(projectId);
      toast.error(errorText(error, "发送失败，请稍后重试"));
    },
  });
}

export function useAnswerQuestion(projectId: string) {
  return useMutation({
    mutationFn: (input: { qid: string; command: AnswerQuestionCommand }) =>
      api.post<void>(`/projects/${projectId}/questions/${input.qid}/answer`, input.command),
    onMutate: ({ command }) => {
      const messageId = useChatStore.getState().submitAnswer(projectId, command.answer);
      useDispatchStageStore.getState().noteSubmitted(projectId);
      return { messageId };
    },
    onError: (error, _input, context) => {
      const chat = useChatStore.getState();
      if (context?.messageId) chat.removeMessage(projectId, context.messageId);
      chat.reopenQuestion(projectId);
      toast.error(errorText(error, "作答失败，请重试"));
    },
  });
}
