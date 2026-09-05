"use client";

import { FileText, Lock, TriangleAlert } from "lucide-react";
import { useEffect, useRef, useState, type ReactNode } from "react";

import { Composer } from "@/components/composer/composer";
import { Bubble, BubbleContent } from "@/components/ui/bubble";
import { Button } from "@/components/ui/button";
import { useAnswerQuestion, usePostMessage } from "@/hooks/use-chat";
import { composeAnswer, toAnswerToolCalls } from "@/lib/chat/qa";
import type { LockRow } from "@/lib/orders/lock";
import { pendingQuestionOf, useChatStore, type ChatMessage } from "@/lib/store/chat";
import { hasPrdUpdate, usePrdNoticesStore } from "@/lib/store/prd-notices";
import { useWorkMessageStore } from "@/lib/store/work-message";

import { QuestionCard } from "./question-card";
import { WorkMessage } from "./work-message";

const EMPTY_MESSAGES: ChatMessage[] = [];

/** 常驻文案（#79 初版）：随访谈/迭代阶段化，告诉用户「现在在哪、下一步能做什么」。 */
const STAGE_HINTS = {
  interview: "访谈中：说说你的想法，平台会提问、把要点整理成需求文档，聊清楚后动手做系统",
  iterate: "迭代中：想改什么、想问什么直接说，每轮修改都会更新文档、留下记录",
} as const;

/**
 * 对话区（issue #19 需求环① + #20 修订回路 + #26 迭代环① + #28 订单锁定 +
 * #47 入口三分类；#79 起居中当主角；#86 单会话收敛）：项目页全程常开的对话区，
 * 无标题——主智能体的开场回应、每轮一问、答询作答、意见受理都在同一会话连续
 * （界面上只有一个「它」，无角色标签），平台的兜底轻引导自带「平台」署名。
 * 首次生成后意见即迭代入口（主智能体判需求侧，轮收口后平台自动派修正
 * run——链必达 #43，形态不变）。发言入口归平台派发（意见/咨询/兜底，对用户
 * 隐式）。编码 run 进行中对话流末尾呈现一条生长中的
 * 工作消息（#81 parts 契约：解说 + 动作状态卡 + 步骤分组，思考与代码不播），
 * 收口定格。发送框 = 共享 Composer（首页/项目页同一
 * 组件，#76）；Enter 路由：有待答问题时即当前问题的答复（可与已勾选合并），
 * 否则即新发言。输入条上方挂「PRD 有更新 · 去看看」胶囊（点击认领并回调
 * 场景层跳成果区）；「确认下单」随首次生成完成常驻输入
 * 条上方（#26）。输入可用性吃锁定式矩阵（#28）：locked（订单处理中）禁用
 * 输入并出锁定提示，closed（归档终态）关闭。对话史 = chat store（SSE 桥喂，
 * 重放可重建近期轮）。
 */
export function CommandArea({
  projectId,
  lock,
  stage = "interview",
  onSeePrd,
  generationCard,
  confirmOrder,
}: {
  projectId: string;
  /** 锁定式矩阵行（缺省 = 进行中全功能）。 */
  lock?: LockRow;
  /** 阶段（常驻文案两态）：缺省访谈期，PRD 产出后装配层切迭代期。 */
  stage?: keyof typeof STAGE_HINTS;
  /** 「去看看」跳转回调（跳成果区文档面等），认领（ack）在本组件内。 */
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
  // 编码 run 的工作消息（#81）：对话流末尾的生长中消息——按 run 生命周期呈现，
  // 收口定格留驻（凝聚物收尾卡归后续票）
  const work = useWorkMessageStore((s) => s.works[projectId]);

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

  // 新内容自动滚底（消息流增长、工作消息长部件或打字指示出现）
  useEffect(() => {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [messages, work?.parts.length, turnActive]);

  const sending = postMessage.isPending || answerQuestion.isPending;
  // 禁用态的锁定提示由输入条上方的横幅承载（具体缘由），占位只留一句短话不重复
  const placeholder = disabled
    ? "对话区已锁定"
    : pending
      ? "回答上面的问题，回车发送（可与已勾选合并）"
      : "和平台聊聊你的想法…";

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

  /** Composer 提交（Enter / 发送键同一入口；对话流暂无附件管道，入口已隐）。 */
  function submit(text: string) {
    if (!text.trim() || disabled || sending) return;
    if (pending) {
      const merged = composeAnswer(selection, text.trim());
      if (!merged) return;
      answer(merged);
    } else {
      postMessage.mutate({ content: text.trim() });
    }
    setInput("");
  }

  return (
    // 居中当主角（#79）：对话列限宽居中，宽屏不散读
    <div className="mx-auto flex h-full min-h-0 w-full max-w-3xl flex-col">
      <div ref={scrollRef} className="min-h-0 flex-1 space-y-3 overflow-y-auto px-4 py-4">
        <p className="pt-2 text-center text-xs text-muted-foreground">{STAGE_HINTS[stage]}</p>
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
        {work ? <WorkMessage work={work} projectId={projectId} /> : null}
        {!disabled && generationCard ? generationCard : null}
        {turnActive ? (
          <div className="flex items-center gap-2 text-xs text-muted-foreground">
            <span className="flex gap-1">
              <Dot delay="0ms" />
              <Dot delay="150ms" />
              <Dot delay="300ms" />
            </span>
            正在输入
          </div>
        ) : null}
      </div>

      <div className="shrink-0 p-3">
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
        <Composer
          value={input}
          onValueChange={setInput}
          onSubmit={submit}
          submitPending={sending}
          disabled={disabled}
          attachmentsEnabled={false}
          inputRef={inputRef}
          placeholder={placeholder}
        />
      </div>
    </div>
  );
}

/** 对话行布局：用户右对齐、智能体（无署名）/问答卡/错误提示/平台引导左对齐。 */
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
  // 智能体话语无署名（#86：界面上只有一个「它」）；平台轻引导（#47）自带
  // 「平台」署名——平台自己说话，非智能体角色
  return (
    <div className="flex w-full flex-col items-start gap-1">
      {message.label ? (
        <span className="pl-1 text-xs text-muted-foreground">{message.label}</span>
      ) : null}
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
