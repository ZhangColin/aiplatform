"use client";

import { FileText, Lock, SendHorizontal, TriangleAlert } from "lucide-react";
import { useEffect, useRef, useState, type KeyboardEvent, type ReactNode } from "react";

import { Bubble, BubbleContent } from "@/components/ui/bubble";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { Textarea } from "@/components/ui/textarea";
import { useAnswerQuestion, usePostMessage } from "@/hooks/use-chat";
import { composeAnswer, toAnswerToolCalls } from "@/lib/chat/qa";
import { isSubmitEnter } from "@/lib/chat/enter";
import type { LockRow } from "@/lib/orders/lock";
import { pendingQuestionOf, useChatStore, type ChatMessage } from "@/lib/store/chat";
import { hasPrdUpdate, usePrdNoticesStore } from "@/lib/store/prd-notices";

import { QuestionCard } from "./question-card";

const EMPTY_MESSAGES: ChatMessage[] = [];

/**
 * 指令区（issue #19 需求环① + #20 修订回路 + #26 迭代环① + #28 订单锁定）：
 * 项目页左侧全程常开的对话区，无标题——BA 开场回应、每轮一问、用户的意见与
 * 答复都在此流动；首次生成后意见即迭代入口（BA 判需求侧，回合收口后平台自动派
 * 修正 run——链必达 #43，指令区形态不变）。发送路由：有待答问题时 Enter 即当前问题的答复（可与已勾选
 * 合并），否则即新发言。问题到达自动聚焦输入框（不错过在等你的问题）。对话史 =
 * chat store（SSE 桥喂，重放可重建近期轮）。PRD 修订到达（未认领）时输入条上方
 * 出「PRD 有更新 · 去看看」胶囊——点击即认领并回调场景层跳转成果区；「确认下单」
 * 随首次生成完成常驻输入条上方（#26，装配层判定可见性后注入）。输入可用性吃
 * 锁定式矩阵（#28）：locked（订单处理中）禁用输入并出锁定提示，closed（归档
 * 终态）关闭——矩阵行由装配层判定后注入。
 */
export function CommandArea({
  projectId,
  lock,
  onSeePrd,
  generationCard,
  confirmOrder,
}: {
  projectId: string;
  /** 锁定式矩阵行（缺省 = 进行中全功能）。 */
  lock?: LockRow;
  /** 「去看看」跳转回调（mobile 切成果区页签等），认领（ack）在本组件内。 */
  onSeePrd?: () => void;
  /** 对话流内卡片槽（「开始做系统」，#22）——装配层判定 eligibility 后注入。 */
  generationCard?: ReactNode;
  /** 输入条上方常驻槽（「确认下单」，#26）——装配层判定可见性后注入。 */
  confirmOrder?: ReactNode;
}) {
  const messages = useChatStore((s) => s.chats[projectId]?.messages ?? EMPTY_MESSAGES);
  const turnActive = useChatStore((s) => s.chats[projectId]?.turnActive ?? false);
  const pending = useChatStore((s) => pendingQuestionOf(s, projectId));
  const prdUpdate = usePrdNoticesStore((s) => hasPrdUpdate(s, projectId));

  const postMessage = usePostMessage(projectId);
  const answerQuestion = useAnswerQuestion(projectId);

  const [input, setInput] = useState("");
  const [selection, setSelection] = useState<string[]>([]);
  /** 勾选归属的问题 id（新问题到达即清上一问勾选——渲染期派生态重置，不用 effect）。 */
  const [selectionFor, setSelectionFor] = useState<string | undefined>(undefined);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const scrollRef = useRef<HTMLDivElement>(null);

  const chatInput = lock?.chatInput ?? "open";
  const disabled = chatInput !== "open";

  const pendingId = pending?.id;
  if (pendingId !== undefined && selectionFor !== pendingId) {
    setSelectionFor(pendingId);
    setSelection([]);
  }

  // 问题到达：聚焦输入框（自由输入作答入口，不错过在等你的问题）
  useEffect(() => {
    if (pendingId && !disabled) inputRef.current?.focus();
  }, [pendingId, disabled]);

  // 新内容自动滚底（消息流增长或打字指示出现）
  useEffect(() => {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [messages, turnActive]);

  const sending = postMessage.isPending || answerQuestion.isPending;
  // 禁用态的锁定提示由输入条上方的横幅承载（具体缘由），占位只留一句短话不重复
  const placeholder = disabled
    ? "指令区已锁定"
    : pending
      ? "回答上面的问题，回车发送（可与已勾选合并）"
      : "和需求分析师聊聊你的想法…";

  function answer(text: string) {
    if (!pending || disabled) return;
    answerQuestion.mutate({
      qid: pending.engineRef,
      command: {
        runId: pending.runId,
        toolCalls: toAnswerToolCalls(pending.toolCalls),
        answer: text,
      },
    });
    setSelection([]);
  }

  function submit() {
    const text = input.trim();
    if (!text || disabled || sending) return;
    if (pending) {
      const merged = composeAnswer(selection, text);
      if (!merged) return;
      answer(merged);
    } else {
      postMessage.mutate({ content: text });
    }
    setInput("");
  }

  function onKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (!isSubmitEnter(event)) return;
    event.preventDefault();
    submit();
  }

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div ref={scrollRef} className="min-h-0 flex-1 space-y-3 overflow-y-auto px-4 py-4">
        <p className="pt-2 text-center text-xs text-muted-foreground">
          把想法告诉需求分析师，TA 会一步步问清你要什么
        </p>
        {messages.map((message) => (
          <MessageRow key={message.id} message={message}>
            {message.kind === "question" ? (
              <QuestionCard
                question={message}
                interactive={message === pending}
                selection={selection}
                onSelectionChange={setSelection}
                onAnswer={answer}
              />
            ) : null}
          </MessageRow>
        ))}
        {!disabled && generationCard ? generationCard : null}
        {turnActive ? (
          <div className="flex items-center gap-2 text-xs text-muted-foreground">
            <span className="flex gap-1">
              <Dot delay="0ms" />
              <Dot delay="150ms" />
              <Dot delay="300ms" />
            </span>
            需求分析师正在输入
          </div>
        ) : null}
      </div>

      <div className="shrink-0 border-t p-3">
        {prdUpdate && !disabled ? (
          <div className="mb-2 flex justify-center">
            <Button
              variant="outline"
              size="sm"
              className="rounded-full text-xs"
              onClick={() => {
                usePrdNoticesStore.getState().acknowledge(projectId);
                onSeePrd?.();
              }}
            >
              <FileText className="size-3.5" />
              PRD 有更新 · 去看看
            </Button>
          </div>
        ) : null}
        {!disabled && confirmOrder ? confirmOrder : null}
        {disabled && lock?.chatHint ? (
          <div className="mb-2 flex items-center gap-2 rounded-lg border bg-muted/40 px-3 py-2 text-xs text-muted-foreground">
            <Lock className="size-3.5 shrink-0" />
            {lock.chatHint}
          </div>
        ) : null}
        <div className="flex items-end gap-2">
          <Textarea
            ref={inputRef}
            rows={1}
            value={input}
            disabled={disabled}
            placeholder={placeholder}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={onKeyDown}
            className="field-sizing-fixed max-h-32 min-h-9 resize-none"
          />
          <Button
            size="icon"
            className="size-9 shrink-0"
            aria-label={pending ? "作答" : "发送"}
            disabled={disabled || !input.trim() || sending}
            onClick={submit}
          >
            {sending ? <Spinner /> : <SendHorizontal className="size-4" />}
          </Button>
        </div>
      </div>
    </div>
  );
}

/** 对话行布局：用户右对齐、BA/问答卡/错误提示左对齐。 */
function MessageRow({ message, children }: { message: ChatMessage; children?: ReactNode }) {
  if (message.kind === "question") {
    return <div className="flex w-full justify-start">{children}</div>;
  }
  if (message.kind === "error") {
    return (
      <div className="flex w-full items-start gap-2 text-xs text-destructive">
        <TriangleAlert className="mt-0.5 size-3.5 shrink-0" />
        <span>本轮回复中断：{message.text}（可重发）</span>
      </div>
    );
  }
  if (message.kind === "user") {
    return (
      <div className="flex w-full justify-end">
        <Bubble variant="tinted" align="end">
          <BubbleContent className="whitespace-pre-wrap">{message.text}</BubbleContent>
        </Bubble>
      </div>
    );
  }
  return (
    <div className="flex w-full flex-col items-start gap-1">
      <span className="pl-1 text-xs text-muted-foreground">需求分析师</span>
      <Bubble variant="muted" align="start">
        <BubbleContent className="whitespace-pre-wrap">{message.text}</BubbleContent>
      </Bubble>
    </div>
  );
}

function Dot({ delay }: { delay: string }) {
  return (
    <span
      className="size-1.5 animate-bounce rounded-full bg-muted-foreground/70"
      style={{ animationDelay: delay }}
    />
  );
}
