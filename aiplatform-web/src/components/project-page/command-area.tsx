"use client";

import { Check, FileText, Inbox, Lock, RefreshCcw, Sparkles, TriangleAlert, User, X } from "lucide-react";
import { Fragment, useEffect, useRef, useState, type ReactNode, type RefObject } from "react";

import { Composer, type ComposerAttachment } from "@/components/composer/composer";
import { Bubble, BubbleContent } from "@/components/ui/bubble";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { cn } from "@/lib/utils";
import { useAnswerQuestion, usePostMessage } from "@/hooks/use-chat";
import { useConversation } from "@/hooks/use-conversation";
import { usePrd } from "@/hooks/use-prd";
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

/** 数据模板钮插入的模板片段（#346 澄清输入照原型位；最小实现＝向输入框插入
 *  数据描述骨架，用户照着填——数据模板的最小可用形态，实施期定）。 */
export const DATA_TEMPLATE_SNIPPET = [
  "【数据模板】",
  "· 数据名称：",
  "· 字段清单：字段名（含义／示例），每行一个",
  "· 数据量与更新频率：",
  "· 来源（现有表格／系统／手工）：",
].join("\n");

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
 * 场景层跳成果区）；范围级新草稿待确认（#334 改需求回路，已生成项目）时升级为
 * 「PRD 已更新，确认新版后生成下一版 Demo」提示（REST 派生态，跳文档面过确认门）。
 * 输入可用性吃锁定式矩阵（#28）：locked（订单处理中）禁用
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
  generated,
  variant = "default",
  inputRef: externalInputRef,
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
  /** 系统已生成（#334 改需求回路提示的分野：范围级新草稿待确认只在迭代期出「确认新版」提示）。 */
  generated?: boolean;
  /** 呈现形态（#346）：default＝对话区（成果区呼出式布局内，过渡桥用）；
   *  stage＝阶段页形态（P02/P03 需求分析页——时间流与澄清输入各自成卡照
   *  原型左栏构图、PRD 更新胶囊迁移为时间流内系统横幅、澄清输入照原型
   * 「提交澄清」＋数据模板钮）。 */
  variant?: "default" | "stage";
  /** 输入框外接 ref（「对话调优」聚焦挂点；缺省内部自持）。 */
  inputRef?: RefObject<HTMLTextAreaElement | null>;
}) {
  const messages = useChatStore((s) => s.chats[projectId]?.messages ?? EMPTY_MESSAGES);
  const turnActive = useChatStore((s) => s.chats[projectId]?.turnActive ?? false);
  const pending = useChatStore((s) => pendingQuestionOf(s, projectId));
  const prdUpdate = usePrdNoticesStore((s) => hasPrdUpdate(s, projectId));
  // 范围级新草稿停驻（#334 改需求回路）：已生成项目＋差异摘要在场＝待确认——
  // 「PRD 已更新，确认新版后生成下一版 Demo」提示（REST 派生态，确认前常驻）。
  // 仅已生成才查（生成蕴含 PRD 在场，不空跑 404）
  const prd = usePrd(projectId, { enabled: generated });
  const scopeDraftPending = generated && !!prd.data?.draftChangeSummary;
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
  const internalInputRef = useRef<HTMLTextAreaElement>(null);
  const inputRef = externalInputRef ?? internalInputRef;
  const scrollRef = useRef<HTMLDivElement>(null);

  const chatInput = lock?.chatInput ?? "open";
  const disabled = chatInput !== "open";
  const staged = variant === "stage";

  const pendingId = pending?.id;
  if (pendingId !== undefined && selectionFor !== pendingId) {
    setSelectionFor(pendingId);
    setSelection([]);
  }

  // 问题到达：聚焦输入框（自由输入作答入口，不错过在等你的问题）
  useEffect(() => {
    if (pendingId && !disabled) inputRef.current?.focus();
  }, [pendingId, disabled, inputRef]);

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
      : staged
        ? "补充您的约束逻辑或回答 AI 提问 (例如：客户工单服务费是否包含备件费用结算？)..."
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
    // 居中当主角（#79）：对话列限宽居中，宽屏不散读；阶段页形态（#346）＝
    // 时间流与澄清输入各自成卡填满左栏（照原型左栏三卡构图，无限宽居中）
    <div className={staged ? "flex h-full min-h-0 w-full flex-col gap-4" : "mx-auto flex h-full min-h-0 w-full max-w-3xl flex-col"}>
      <div
        ref={scrollRef}
        data-frame={staged ? "timeline-card" : undefined}
        className={
          staged
            ? "min-h-0 flex-1 space-y-3 overflow-y-auto rounded-2xl border border-slate-soft/80 bg-white px-4 py-4 shadow-e1"
            : "min-h-0 flex-1 space-y-3 overflow-y-auto px-4 py-4"
        }
      >
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
        {/* PRD 更新呈现迁移位（#346 阶段页形态）：时间流内系统横幅照原型
            （「PRD 规范已增量刷新」＝旧「PRD 有更新 · 去看看」胶囊的迁移位——
            文档面常驻右栏，横幅只报事实不带跳转；范围级停驻草稿＝指路确认门） */}
        {staged && scopeDraftPending && !disabled ? <ScopeDraftBanner /> : null}
        {staged && !scopeDraftPending && prdUpdate && !disabled ? (
          <PrdRefreshBanner onDismiss={() => usePrdNoticesStore.getState().acknowledge(projectId)} />
        ) : null}
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

      <div
        data-frame={staged ? "composer-card" : undefined}
        className={staged ? "shrink-0 rounded-2xl border border-slate-soft/80 bg-white p-3 shadow-e1" : "shrink-0 p-3"}
      >
        {!staged && scopeDraftPending && !disabled ? (
          // 范围级新草稿待确认（#334）：升级版提示取代通用胶囊——跳 PRD 文档面
          // 过确认门（「确认此版 PRD 并生成 Demo」）。REST 派生态：确认成功（清停驻）
          // 后自然退场，无需认领
          <div className="mb-2 flex justify-center">
            <Button
              variant="outline"
              size="sm"
              className="rounded-full text-xs"
              onClick={() => onSeePrd?.()}
            >
              <FileText className="size-3.5" />
              PRD 已更新，确认新版后生成下一版 Demo
            </Button>
          </div>
        ) : !staged && prdUpdate && !disabled ? (
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
          {...(staged
            ? {
                // 澄清输入照原型（#346）：「提交澄清」文本键＋语音/附件/数据模板钮
                submitLabel: "提交澄清",
                onDataTemplate: () => {
                  setInput((prev) =>
                    prev.trim() ? `${prev}\n${DATA_TEMPLATE_SNIPPET}` : DATA_TEMPLATE_SNIPPET,
                  );
                  inputRef.current?.focus();
                },
              }
            : {})}
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

/** 对话行布局：用户右对齐、智能体（无署名）/问答卡/收尾卡/报价卡/受理动作卡/错误提示/平台引导左对齐。
 *  气泡形态照原型（#346）：用户气泡削右上角（rounded-tr-sm）＋用户头像视觉锚；智能体气泡削
 *  左上角（rounded-tl-sm）＋渐变头像锚——头像只作视觉锚，不落角色名（ADR 0006 无角色署名口径）。 */
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
      <div className="flex w-full items-start justify-end gap-2">
        <Bubble variant="tinted" align="end">
          <BubbleContent className="whitespace-pre-wrap rounded-2xl rounded-tr-sm shadow-xs">{message.text}</BubbleContent>
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
        <span
          data-avatar="user"
          aria-hidden="true"
          className="mt-0.5 flex size-6 shrink-0 items-center justify-center rounded-full border border-slate-soft bg-surface-dawn text-ink-muted"
        >
          <User className="size-3.5" />
        </span>
      </div>
    );
  }
  // 智能体话语无署名（#86：界面上只有一个「它」）；平台轻引导（#47）自带
  // 「平台」署名——平台自己说话，非智能体角色
  return (
    <div className="flex w-full items-start gap-2">
      <span
        data-avatar="agent"
        aria-hidden="true"
        className="mt-0.5 flex size-6 shrink-0 items-center justify-center rounded-full bg-linear-to-tr from-electric-indigo to-mint-teal text-white shadow-xs"
      >
        <Sparkles className="size-3.5" />
      </span>
      <div className="flex min-w-0 flex-col items-start gap-1">
        {message.label ? (
          <span className="pl-1 text-xs text-muted-foreground">{message.label}</span>
        ) : null}
        <Bubble variant="muted" align="start">
          <BubbleContent className="whitespace-pre-wrap rounded-2xl rounded-tl-sm border-electric-indigo/15 shadow-xs">{message.text}</BubbleContent>
        </Bubble>
      </div>
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
 * 「PRD 规范已增量刷新并重算基线」系统横幅（#346 阶段页形态迁移位——旧
 * 「PRD 有更新 · 去看看」胶囊的迁移形态，照原型：渐变底＋sync 图标块）：
 * 只报事实不带跳转（文档面常驻右栏、已实时刷新），X 关闭即认领（ack）。
 */
function PrdRefreshBanner({ onDismiss }: { onDismiss: () => void }) {
  return (
    <div className="flex items-start gap-3 rounded-2xl border border-electric-indigo/25 bg-linear-to-r from-surface-violet-tint via-surface-mist/40 to-white p-3.5 shadow-xs">
      <div className="mt-0.5 flex size-7 shrink-0 items-center justify-center rounded-xl bg-electric-indigo text-white shadow-xs">
        <RefreshCcw className="size-4" aria-hidden="true" />
      </div>
      <div className="flex min-w-0 flex-col gap-0.5">
        <span className="text-label-md font-bold text-electric-indigo">PRD 规范已增量刷新并重算基线</span>
        <span className="text-body-sm leading-relaxed text-ink-muted">
          右侧 PRD 文档已实时增量刷新。
        </span>
      </div>
      <button
        type="button"
        aria-label="知道了"
        onClick={onDismiss}
        className="ml-auto shrink-0 rounded-md p-1 text-ink-faint transition-colors hover:bg-surface-dawn hover:text-ink-primary"
      >
        <X className="size-3.5" />
      </button>
    </div>
  );
}

/**
 * 范围级新草稿停驻横幅（#334 改需求回路的阶段页形态）：指路确认门——主 CTA
 * 常驻粘性 header（「确认此版 PRD 并生成 Demo」），横幅只说明事实与去向；
 * REST 派生态（draftChangeSummary 在场即在），确认成功自然退场，无认领位。
 */
function ScopeDraftBanner() {
  return (
    <div className="flex items-start gap-3 rounded-2xl border border-electric-indigo/25 bg-linear-to-r from-surface-violet-tint via-surface-mist/40 to-white p-3.5 shadow-xs">
      <div className="mt-0.5 flex size-7 shrink-0 items-center justify-center rounded-xl bg-electric-indigo text-white shadow-xs">
        <FileText className="size-4" aria-hidden="true" />
      </div>
      <div className="flex min-w-0 flex-col gap-0.5">
        <span className="text-label-md font-bold text-electric-indigo">
          PRD 已更新，确认新版后生成下一版 Demo
        </span>
        <span className="text-body-sm leading-relaxed text-ink-muted">
          本版更新内容已并入右侧 PRD 文档；点右上角「确认此版 PRD 并生成 Demo」查看差异并确认。
        </span>
      </div>
    </div>
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
