"use client";

import { Check, FileText, Inbox, Lock, TriangleAlert, X } from "lucide-react";
import { Fragment, useEffect, useRef, useState, type ReactNode } from "react";

import { Composer, type ComposerAttachment } from "@/components/composer/composer";
import { Bubble, BubbleContent } from "@/components/ui/bubble";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { cn } from "@/lib/utils";
import { useAnswerQuestion, usePostMessage } from "@/hooks/use-chat";
import { useConversation } from "@/hooks/use-conversation";
import { useUploadMaterial } from "@/hooks/use-upload-material";
import { composeAnswer, toAnswerToolCalls } from "@/lib/chat/qa";
import {
  annotationLabel,
  annotationSummary,
  renderAnnotationsText,
  toAttachmentCommand,
} from "@/lib/preview/annotation";
import { renderMaterialsText, toImageAttachmentCommand } from "@/lib/projects/materials";
import { rawFileUrl } from "@/lib/projects/files";
import type { LockRow } from "@/lib/orders/lock";
import type { GenerationSegmentFact } from "@/lib/projects/detail";
import { useAnnotationStore, type AnnotationItem } from "@/lib/store/annotation";
import { pendingQuestionOf, useChatStore, type ChatMessage } from "@/lib/store/chat";
import {
  DIVERGENCE_EXPLORE,
  DIVERGENCE_LEVELS,
  useDesignScopeStore,
} from "@/lib/store/design-scope";
import { hasPrdUpdate, usePrdNoticesStore } from "@/lib/store/prd-notices";
import { useWorkMessageStore } from "@/lib/store/work-message";

import { ClosingCard } from "./closing-card";
import { QuestionCard } from "./question-card";
import { QuoteCard } from "./quote-card";
import { WorkMessage } from "./work-message";

const EMPTY_MESSAGES: ChatMessage[] = [];
const EMPTY_ANNOTATIONS: AnnotationItem[] = [];

/** 常驻文案（#79 初版；#299 设计主线变体）：随访谈/迭代阶段化 × 终点轨道变体——
 * 设计主线（做设计出身）梳的是设计物清单、迭代即改稿；系统/系统＋设计访谈用
 * 系统形（系统＋设计的 PRD 是功能清单形，#285 口径）。 */
const STAGE_HINTS = {
  system: {
    interview: "访谈中：说说你的想法，平台会提问、把要点整理成需求文档，聊清楚后动手做系统",
    iterate: "迭代中：想改什么、想问什么直接说，每轮修改都会更新文档、留下记录",
  },
  design: {
    interview: "访谈中：说说你想要的设计，平台会提问、把要点整理成设计物清单，聊清楚后开始出稿",
    iterate: "迭代中：想改哪件、想问什么直接说，改稿与定稿都在这里聊、留下记录",
  },
} as const;

/** 轨道两态（缺省系统——终点缺省/未知按系统形，与弱标识缺省不出同口径方向）。 */
type ChatTrack = keyof typeof STAGE_HINTS;

/** 阶段两态（两轨道同键集）。 */
type StageKey = keyof typeof STAGE_HINTS["system"];

/**
 * 对话区（issue #19 需求环① + #20 修订回路 + #26 迭代环① + #28 订单锁定 +
 * #47 入口三分类；#79 起居中当主角；#86 单会话收敛）：项目页全程常开的对话区，
 * 无标题——主智能体的开场回应、每轮一问、答询作答、意见受理都在同一会话连续
 * （界面上只有一个「它」，无角色标签），平台的兜底轻引导自带「平台」署名。
 * 首次生成后意见即迭代入口（主智能体判需求侧，轮收口后平台自动派更新
 * run——链必达 #43，形态不变）。发言入口归平台派发（意见/咨询/兜底，对用户
 * 隐式）。编码 run 进行中对话流末尾呈现一条生长中的
 * 工作消息（#81 parts 契约：解说 + 动作状态卡，思考与代码不播），
 * 收口定格。发送框 = 共享 Composer（首页/项目页同一
 * 组件，#76）；Enter 路由：有待答问题时即当前问题的答复（可与已勾选合并），
 * 否则即新发言。输入条上方挂「PRD 有更新 · 去看看」胶囊（点击认领并回调
 * 场景层跳成果区）。输入可用性吃锁定式矩阵（#28）：locked（订单处理中）禁用
 * 输入并出锁定提示，closed（归档终态）关闭。对话史 = chat store（SSE 桥喂，
 * 重放可重建近期轮）。
 */
export function CommandArea({
  projectId,
  lock,
  stage = "interview",
  track = "system",
  plan,
  designPlan,
  onSeePrd,
  onSeeOrder,
}: {
  projectId: string;
  /** 锁定式矩阵行（缺省 = 进行中全功能）。 */
  lock?: LockRow;
  /** 阶段（常驻文案两态）：缺省访谈期，PRD 产出后装配层切迭代期。 */
  stage?: StageKey;
  /** 终点轨道（#299 常驻文案变体）：设计主线＝设计形文案；缺省系统。 */
  track?: ChatTrack;
  /** 生成轨道片清单（#225 计划区，REST 详情透出；缺省 = 无现行计划）。 */
  plan?: GenerationSegmentFact[] | null;
  /** 设计轨道件清单（#290 designer 直播卡计划区；工作消息座席＝designer 时选送）。 */
  designPlan?: GenerationSegmentFact[] | null;
  /** 「去看看」跳转回调（跳成果区文档面等），认领（ack）在本组件内。 */
  onSeePrd?: () => void;
  /** 报价卡「查看订单详情」跳转回调（#203：挂载并切到订单 tab）。 */
  onSeeOrder?: () => void;
}) {
  const messages = useChatStore((s) => s.chats[projectId]?.messages ?? EMPTY_MESSAGES);
  const turnActive = useChatStore((s) => s.chats[projectId]?.turnActive ?? false);
  const pending = useChatStore((s) => pendingQuestionOf(s, projectId));
  const prdUpdate = usePrdNoticesStore((s) => hasPrdUpdate(s, projectId));
  // 编码 run 的工作消息（#81）：对话流末尾的生长中消息——按 run 生命周期呈现，
  // 收口定格留驻（#117：成功收口不清空，收尾卡随后入流）；designer 座席（#290
  // 设计会话）同骨架异语料——计划区换设计物清单
  const work = useWorkMessageStore((s) => s.works[projectId]);
  // 计划区选送（#290）：座席分岔——designer＝设计物清单、executor＝切片清单
  //（两清单互斥出场：设计轨与生成轨不并行，同构不混淆）
  const workPlan = work?.seat === "designer" ? designPlan : plan;
  // 工作消息插入锚（#117「过程上文、结果下卡」）：定格留驻的工作消息插在本 run
  // 收尾卡之前——上承本轮意见、下启收尾卡；-1 = 无本 run 收尾卡（生长中 /
  // run-failed），留对话流末尾。锚位即活性行沉没信号（#235：收尾卡入流 → 末行
  // 随定格沉没；未入流＝保留末行）
  const workAnchorIndex = work
    ? messages.findIndex(
        (message) => message.kind === "closing" && message.runId === work.runId,
      )
    : -1;

  const postMessage = usePostMessage(projectId);
  const answerQuestion = useAnswerQuestion(projectId);
  // 图片物料上传（#286 回形针真上传）：multipart 落工作区物料目录，chip 呈现
  // 上传中/失败态、路径引用随下一句话发出
  const uploadMaterial = useUploadMaterial(projectId);
  // 圈注条目（#97）：预览回传的标注，随发送框附件区呈现、发送前可删改，发送即清
  const annotations = useAnnotationStore((s) => s.annotations[projectId] ?? EMPTY_ANNOTATIONS);
  // 设计改稿作用域（#294 点哪改哪）：画布点选写入，chip 呈现于发送框上方；随话
  // 直达该件设计会话（designItem）＋发散度档位（divergence）同句发出
  const scope = useDesignScopeStore((s) => s.scopes[projectId]);
  const divergence = useDesignScopeStore((s) => s.divergences[projectId] ?? DIVERGENCE_EXPLORE);
  const clearScope = useDesignScopeStore((s) => s.clear);
  const setDivergence = useDesignScopeStore((s) => s.setDivergence);
  // 对话史水合（#89）：刷新 / 回访对话完整（含问答作答与收尾卡）；轮收口事件与
  // 重连失效驱动增量水合，live 事件只承载在途增量
  useConversation(projectId);

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

  /** Composer 提交（Enter / 发送键同一入口）：圈注与图片物料附件随发言同句发送
   *  （#97 / #286 增强——图片载荷＝工作区路径引用、不带字节）。 */
  function submit(
    text: string,
    attachments: ComposerAttachment[],
    annotations: AnnotationItem[],
  ) {
    if (!text.trim() || disabled || sending) return;
    // 上传完成的物料才随话发出（上传中/失败态 Composer 已阻塞发送，防御再滤）
    const materials = attachments.flatMap((a): { name: string; path: string }[] =>
      a.state === "done" && a.path ? [{ name: a.name, path: a.path }] : [],
    );
    if (pending) {
      // 作答通道无附件位：圈注与物料渲染进答复文本（主智能体可读），随答复同发即清
      const annotationText = renderAnnotationsText(annotations);
      const materialText = renderMaterialsText(materials);
      const extras = [
        annotationText ? `【圈注】${annotationText}` : "",
        materialText ? `【图片物料】${materialText}` : "",
      ].filter(Boolean).join("\n");
      const merged = composeAnswer(selection, extras ? `${text.trim()}\n${extras}` : text.trim());
      if (!merged) return;
      answer(merged);
      useAnnotationStore.getState().clear(projectId);
    } else {
      postMessage.mutate({
        content: text.trim(),
        attachments: [
          ...materials.map(toImageAttachmentCommand),
          ...annotations.map(toAttachmentCommand),
        ],
        // 作用域在场＝改稿直达（#294 点哪改哪）：designItem 路由＋发散度同句；
        // 作用域不清（stitch 挑选语义——连续改稿零重复点选，X 才退出）
        ...(scope ? { designItem: scope.ord, divergence } : {}),
      });
      // 发送即清（圈注随消息发出，不再滞留）
      useAnnotationStore.getState().clear(projectId);
    }
    setInput("");
  }

  return (
    // 居中当主角（#79）：对话列限宽居中，宽屏不散读
    <div className="mx-auto flex h-full min-h-0 w-full max-w-3xl flex-col">
      <div ref={scrollRef} className="min-h-0 flex-1 space-y-3 overflow-y-auto px-4 py-4">
        <p className="pt-2 text-center text-xs text-muted-foreground">{STAGE_HINTS[track][stage]}</p>
        {messages.map((message, index) => (
          <Fragment key={message.id}>
            {work && index === workAnchorIndex ? (
              <WorkMessage work={work} plan={workPlan} closingArrived />
            ) : null}
            <MessageRow message={message} projectId={projectId} round={closingRoundOf(messages, message)} onSeeOrder={onSeeOrder}>
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
          </Fragment>
        ))}
        {work && workAnchorIndex === -1 ? <WorkMessage work={work} plan={workPlan} /> : null}
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
        {disabled && lock?.chatHint ? (
          <div className="mb-2 flex items-center gap-2 rounded-lg border bg-muted/40 px-3 py-2 text-xs text-muted-foreground">
            <Lock className="size-3.5 shrink-0" />
            {lock.chatHint}
          </div>
        ) : null}
        {/* 编辑作用域＋发散档（#294 点哪改哪——stitch：选中＝编辑作用域、chip 调幅） */}
        {scope && !disabled ? (
          <div className="mb-1.5 flex flex-wrap items-center gap-1.5 text-xs" data-design-scope>
            <span className="flex items-center gap-1 rounded-full border border-primary/30 bg-primary/5 px-2 py-1 text-primary">
              就「{scope.itemTitle}」改
              <button
                type="button"
                className="rounded-full p-0.5 transition-colors hover:bg-primary/10"
                aria-label="取消作用域"
                data-scope-clear
                onClick={() => clearScope(projectId)}
              >
                <X className="size-3" />
              </button>
            </span>
            <span className="text-muted-foreground">发散幅度</span>
            {DIVERGENCE_LEVELS.map((level) => (
              <button
                key={level.value}
                type="button"
                onClick={() => setDivergence(projectId, level.value)}
                data-divergence={level.value}
                className={cn(
                  "rounded-full border px-2 py-0.5 transition-colors",
                  divergence === level.value
                    ? "border-foreground/40 bg-muted font-medium"
                    : "text-muted-foreground hover:bg-muted/50",
                )}
              >
                {level.label}
              </button>
            ))}
          </div>
        ) : null}
        <Composer
          value={input}
          onValueChange={setInput}
          onSubmit={submit}
          submitPending={sending}
          disabled={disabled}
          uploadFile={uploadMaterial}
          materialUrl={(path) => rawFileUrl(projectId, path)}
          annotations={annotations}
          onAnnotationRemove={(id) => useAnnotationStore.getState().remove(projectId, id)}
          inputRef={inputRef}
          placeholder={placeholder}
        />
      </div>
    </div>
  );
}

/**
 * 收尾卡的轮次序数（#142 标题语境源一）：对话流（写入序即对话序）中第 N 个
 * 收尾卡 = 第 N 轮，客户端数出、零新数据。序数即用户在对话里感知的「第几轮」，
 * 与成版是否成功无关（成版失败轮无「查看当时」入口，但占序数——对话轮与版本
 * 轮不对齐时以对话为准）。#140 的「同 runId 首条用户消息」尽力而为管线（无锚/
 * 被裁即缺场，走查实测全回落不可分辨）已随 #142 退役。
 */
function closingRoundOf(messages: ChatMessage[], message: ChatMessage): number | undefined {
  if (message.kind !== "closing") return undefined;
  let round = 0;
  for (const m of messages) {
    if (m.kind === "closing") round += 1;
    if (m === message) return round;
  }
  return undefined;
}

/** 对话行布局：用户右对齐、智能体（无署名）/问答卡/收尾卡/报价卡/受理动作卡/错误提示/平台引导左对齐。 */
function MessageRow({ message, children, projectId, round, onSeeOrder }: { message: ChatMessage; children?: ReactNode; projectId: string; round?: number; onSeeOrder?: () => void }) {
  if (message.kind === "question") {
    return <div className="flex w-full justify-start">{children}</div>;
  }
  if (message.kind === "acceptance") {
    return <AcceptanceRow message={message} />;
  }
  if (message.kind === "quote") {
    // 报价卡（#203 视镜语义）：金额/备注/状态渲染时取订单当前态，卡不冻结金额
    return (
      <div className="flex w-full justify-start">
        <QuoteCard orderId={message.orderId} event={message.event} onSeeOrder={onSeeOrder} />
      </div>
    );
  }
  if (message.kind === "closing") {
    // 收尾卡（#88 定格收口，#89 归对话流常驻——live 与水合同卡）
    return (
      <div className="flex w-full justify-start">
        <ClosingCard closing={message.closing} projectId={projectId} round={round} />
      </div>
    );
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
          {message.annotations && message.annotations.length > 0 ? (
            <div className="mt-1.5 flex flex-wrap justify-end gap-1">
              {message.annotations.map((a, i) => (
                <span
                  key={i}
                  className="flex items-center gap-1 rounded-md border bg-background/60 px-1.5 py-0.5 text-xs text-foreground/70"
                >
                  {i + 1}·{annotationLabel(a.kind)}·{annotationSummary(a)}
                </span>
              ))}
            </div>
          ) : null}
          {message.materials && message.materials.length > 0 ? (
            <div className="mt-1.5 flex flex-wrap justify-end gap-1">
              {message.materials.map((m) => (
                <a
                  key={m.path}
                  href={rawFileUrl(projectId, m.path)}
                  target="_blank"
                  rel="noopener"
                  data-material-chip={m.path}
                  className="flex items-center gap-1.5 rounded-md border bg-background/60 py-0.5 pl-0.5 pr-1.5 text-xs text-foreground/70 transition-colors hover:bg-muted"
                  title={`${m.name}（点开看大图）`}
                >
                  {/* eslint-disable-next-line @next/next/no-img-element -- 平台文件服务直出的用户图片，非静态资源（Next Image 不适用） */}
                  <img
                    src={rawFileUrl(projectId, m.path)}
                    alt={m.name}
                    className="size-5 rounded-sm object-cover"
                  />
                  <span className="max-w-36 truncate">{m.name}</span>
                </a>
              ))}
            </div>
          ) : null}
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

/**
 * 受理动作卡（#87 受理轮过程呈现）：意见已接住、正在受理的单行状态卡（形态同
 * 工作消息动作行——图标 + 对象 + 状态）。受理中转圈（追问挂起期间保持——同一
 * 受理轮仍在途）；该轮收口（run-finish / error 推导）落定为「意见已受理」，
 * 衔接随后到达的更新 run 工作消息。
 */
function AcceptanceRow({ message }: { message: Extract<ChatMessage, { kind: "acceptance" }> }) {
  return (
    <div className="flex w-full justify-start">
      <div className="flex w-full items-center gap-2 rounded-xl border border-foreground/10 px-3 py-2 text-sm">
        <Inbox className="size-3.5 shrink-0 text-muted-foreground" />
        <span className={cn("min-w-0 flex-1", message.settled && "text-muted-foreground")}>
          {message.settled ? "意见已受理" : "已收到你的意见，正在处理"}
        </span>
        {message.settled ? (
          <Check className="size-3.5 shrink-0 text-green-600" strokeWidth={3} />
        ) : (
          <Spinner className="size-3 shrink-0 text-muted-foreground" />
        )}
      </div>
    </div>
  );
}
