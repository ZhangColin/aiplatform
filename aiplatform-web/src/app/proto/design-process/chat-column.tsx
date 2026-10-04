"use client";

/**
 * ============================================================================
 * 原 型 —— 设计过程体验（#278）：对话区（一次性，勿当生产代码）
 * ============================================================================
 * 直播卡用真件（work-message 的 WorkMessage，喂假 WorkSnapshot）——设计执行体
 * 与构建 run 同骨架：计划区＝设计物清单/切片清单、活性行＝出稿/编码动作。
 * 收尾卡为设计语义自绘（克隆真收尾卡视觉习语）：设计定稿卡（稿清单＋去向）、
 * 构建收口卡（按稿对齐叙事＋lint 如实标注）。发送框用真 Composer（附件＝物料回显）。
 * ============================================================================
 */

import * as React from "react";
import {
  BadgeCheck,
  Check,
  Clock3,
  CreditCard,
  Eye,
  FileText,
  Image as ImageIcon,
  Monitor,
  Package,
  ReceiptText,
  ShieldCheck,
  Sparkles,
  TriangleAlert,
  X,
} from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Bubble, BubbleContent } from "@/components/ui/bubble";
import { Button } from "@/components/ui/button";
import {
  Message,
  MessageAvatar,
  MessageContent,
  MessageGroup,
} from "@/components/ui/message";
import {
  MessageScroller,
  MessageScrollerContent,
  MessageScrollerProvider,
  MessageScrollerViewport,
} from "@/components/ui/message-scroller";
import { cn } from "@/lib/utils";
import { Composer, type ComposerAttachment } from "@/components/composer/composer";
import { WorkMessage } from "@/components/project-page/work-message";

import { svgUrl, REFERENCE_SVG, draftName } from "./media";
import type { ChatMsg } from "./engine";
import type { DesignEngine } from "./use-design-engine";
import { DraftMedia, isLive } from "./design-tab";

/* ---------- 用户气泡（含上传物料回显 chip） ---------- */

function UserMsg({ msg }: { msg: Extract<ChatMsg, { kind: "user" }> }) {
  return (
    <Message align="end">
      <Bubble variant="tinted" align="end">
        <BubbleContent className="whitespace-pre-wrap">{msg.text}</BubbleContent>
        {msg.attachment ? (
          <div className="mt-1.5 flex items-center gap-2 rounded-lg border bg-background/70 p-1.5 pr-2.5">
            {/* eslint-disable-next-line @next/next/no-img-element -- 原型本地 data url */}
            <img src={svgUrl(REFERENCE_SVG)} alt={msg.attachment.name} className="h-10 w-16 rounded object-cover" />
            <div className="min-w-0">
              <div className="truncate text-xs font-medium">{msg.attachment.name}</div>
              <div className="text-[10px] text-muted-foreground">已存入项目物料 · 出稿会参考</div>
            </div>
          </div>
        ) : null}
      </Bubble>
    </Message>
  );
}

function AgentMsg({ text }: { text: string }) {
  return (
    <Message>
      <MessageAvatar className="size-6 bg-muted">
        <Sparkles className="size-3.5 text-muted-foreground" />
      </MessageAvatar>
      <MessageContent>
        <Bubble variant="muted" align="start">
          <BubbleContent className="whitespace-pre-wrap">{text}</BubbleContent>
        </Bubble>
      </MessageContent>
    </Message>
  );
}

/* ---------- 设计定稿收尾卡（稿清单＋去向） ---------- */

function DesignCloseCard({
  msg,
  onViewVersion,
}: {
  msg: Extract<ChatMsg, { kind: "design-close" }>;
  onViewVersion: (n: number) => void;
}) {
  return (
    <Message>
      <MessageAvatar className="size-6 bg-muted">
        <Sparkles className="size-3.5 text-muted-foreground" />
      </MessageAvatar>
      <MessageContent>
        <div className="rounded-xl border border-green-600/25 bg-green-500/[0.06] p-3">
          <div className="mb-1.5 flex items-center gap-2">
            <BadgeCheck className="size-4 text-green-600" />
            <span className="text-sm font-semibold">{msg.item} · 已定稿</span>
            <Badge variant="outline" className="border-green-600/30 bg-background text-green-700">
              版本 {msg.version}
            </Badge>
          </div>
          <div className="flex items-start gap-2.5">
            <div className="h-20 w-16 shrink-0 overflow-hidden rounded-md border bg-muted/20">
              <DraftMedia draftId={msg.media} stage={4} />
            </div>
            <div className="min-w-0 flex-1">
              <div className="text-sm font-medium">「{draftName(msg.media)}」{isLive(msg.media) ? "（可交互轻页面）" : ""}</div>
              <div className="mt-1.5 space-y-1">
                {msg.triggers.map((t) => (
                  <div key={t} className="flex items-start gap-1.5 text-[13px] text-foreground/80">
                    <Check className="mt-1 size-3.5 shrink-0 text-green-600" strokeWidth={3} />
                    {t}
                  </div>
                ))}
              </div>
            </div>
          </div>
          {/* 设计规范刷新（token 对＝定稿色板） */}
          <div className="mt-2.5 flex items-center gap-2 rounded-lg border bg-background px-2.5 py-1.5">
            <span className="text-xs text-muted-foreground">设计规范 v{msg.spec.version}</span>
            <span className="flex gap-1">
              {msg.spec.pairs.filter(([, v]) => v.startsWith("#")).map(([k, v]) => (
                <span key={k} className="size-4 rounded-full border" style={{ background: v }} title={`${k} ${v}`} />
              ))}
            </span>
            <span className="ml-auto text-[11px] text-muted-foreground">已写入项目 · 后续出稿与系统都按这套</span>
          </div>
          <div className="mt-2.5">
            <Button size="sm" variant="outline" className="h-8 bg-background text-[13px]" onClick={() => onViewVersion(msg.version)}>
              <Eye className="size-3.5" /> 查看当时
            </Button>
          </div>
        </div>
      </MessageContent>
    </Message>
  );
}

/* ---------- 构建收口卡（按稿对齐叙事＋lint 如实标注） ---------- */

function BuildCloseCard({
  msg,
  onViewVersion,
}: {
  msg: Extract<ChatMsg, { kind: "build-close" }>;
  onViewVersion: (n: number) => void;
}) {
  return (
    <Message>
      <MessageAvatar className="size-6 bg-muted">
        <Sparkles className="size-3.5 text-muted-foreground" />
      </MessageAvatar>
      <MessageContent>
        <div className="rounded-xl border border-green-600/25 bg-green-500/[0.06] p-3">
          <div className="mb-1.5 flex items-center gap-2">
            <Check className="size-4 text-green-600" strokeWidth={3} />
            <span className="text-sm font-semibold">系统搭好了 · 按「{msg.alignName}」对齐</span>
            <Badge variant="outline" className="border-green-600/30 bg-background text-green-700">
              版本 {msg.version}
            </Badge>
          </div>
          <p className="text-sm leading-relaxed">
            首屏与其余页面都按定稿那张搭出来了，设计规范（色板、圆角）先写进了系统底座，再逐页生成。
          </p>
          {/* 按稿对齐叙事：规范写入 + token 合规扫描结果（如实标注） */}
          <div className="mt-2 rounded-lg border bg-background px-2.5 py-2">
            <div className="flex items-center gap-2 text-[13px]">
              <ShieldCheck className="size-3.5 text-muted-foreground" />
              <span>规范合规扫描：{msg.lint.total} 处违规 → 自动重试修复 {msg.lint.fixed} 处</span>
            </div>
            <div className="mt-1 flex items-start gap-2 rounded-md bg-amber-500/10 px-2 py-1.5 text-[13px] text-amber-700 dark:text-amber-400">
              <TriangleAlert className="mt-0.5 size-3.5 shrink-0" />
              <span>残留 {msg.lint.total - msg.lint.fixed} 处如实标注：{msg.lint.residual}</span>
            </div>
          </div>
          <div className="mt-2.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
            <span className="flex items-center gap-1">
              <Clock3 className="size-3.5" /> 用时 <b className="font-medium text-foreground/80">{msg.stats.time}</b>
            </span>
            <span className="flex items-center gap-1">
              <FileText className="size-3.5" /> <b className="font-medium text-foreground/80">{msg.stats.files}</b> 个文件
            </span>
            <span className="tabular-nums">
              <span className="font-medium text-green-600">+{msg.stats.add}</span> 行
            </span>
          </div>
          <div className="mt-2.5 flex gap-2">
            <Button size="sm" variant="outline" className="h-8 bg-background text-[13px]" onClick={() => onViewVersion(msg.version)}>
              <Eye className="size-3.5" /> 查看当时
            </Button>
          </div>
        </div>
      </MessageContent>
    </Message>
  );
}

/* ---------- 报价卡（设计单）与通告 ---------- */

function QuoteCard({ msg, engine }: { msg: Extract<ChatMsg, { kind: "quote" }>; engine: DesignEngine }) {
  const paid = engine.state.order === "paid";
  return (
    <Message>
      <MessageAvatar className="size-6 bg-muted">
        <Sparkles className="size-3.5 text-muted-foreground" />
      </MessageAvatar>
      <MessageContent>
        <div className="rounded-xl border p-3">
          <div className="mb-1.5 flex items-center gap-2">
            <ReceiptText className="size-4 text-muted-foreground" />
            <span className="text-sm font-semibold">报价已出</span>
            <span className="ml-auto text-lg font-bold tabular-nums">{msg.price}</span>
          </div>
          {msg.lines.map((l) => (
            <div key={l} className="flex items-start gap-2 text-[13px] text-foreground/80">
              <Package className="mt-0.5 size-3.5 shrink-0 text-muted-foreground" />
              {l}
            </div>
          ))}
          <div className="mt-2.5">
            {paid ? (
              <span className="flex items-center gap-1.5 text-[13px] text-green-700">
                <Check className="size-4" strokeWidth={3} /> 已支付 · 「订单」页可下载设计资产包
              </span>
            ) : (
              <Button size="sm" className="h-8 text-[13px] transition-transform active:scale-[0.98]" onClick={() => engine.onPay()}>
                <CreditCard className="size-3.5" /> 去支付
              </Button>
            )}
          </div>
        </div>
      </MessageContent>
    </Message>
  );
}

function NoticeCard({ msg }: { msg: Extract<ChatMsg, { kind: "notice" }> }) {
  const amber = msg.tone === "amber";
  return (
    <Message>
      <MessageAvatar className="size-6 bg-muted">
        <Sparkles className="size-3.5 text-muted-foreground" />
      </MessageAvatar>
      <MessageContent>
        <div
          className={cn(
            "flex items-start gap-2 rounded-xl border p-3 text-sm leading-relaxed",
            amber ? "border-amber-500/30 bg-amber-500/[0.07] text-amber-800 dark:text-amber-300" : "border-green-600/25 bg-green-500/[0.06]",
          )}
        >
          {amber ? <TriangleAlert className="mt-0.5 size-4 shrink-0" /> : <Check className="mt-0.5 size-4 shrink-0 text-green-600" strokeWidth={3} />}
          {msg.text}
        </div>
      </MessageContent>
    </Message>
  );
}

/* ---------- 装配 ---------- */

export function ChatColumn({
  engine,
  onViewVersion,
}: {
  engine: DesignEngine;
  onViewVersion: (n: number) => void;
}) {
  const { state } = engine;
  const [text, setText] = React.useState("");
  const [range, setRange] = React.useState<"refine" | "explore" | "reimagine">("explore");
  const pickedName = state.picked ? draftName(state.picked) : null;

  const onSubmit = (t: string, attachments: ComposerAttachment[]) => {
    const attachment = attachments[0];
    engine.onSend(t, attachment ? { name: attachment.name, url: "" } : undefined, range);
    setText("");
  };

  return (
    <div className="mx-auto flex min-h-0 w-full max-w-3xl flex-1 flex-col">
      <MessageScrollerProvider>
        <MessageScroller className="min-h-0 flex-1">
          <MessageScrollerViewport>
            <MessageScrollerContent className="gap-5 p-4 pb-2">
              <MessageGroup>
                {state.chat.map((m, i) => {
                  if (m.kind === "user") return <UserMsg key={i} msg={m} />;
                  if (m.kind === "agent") return <AgentMsg key={i} text={m.text} />;
                  if (m.kind === "quote") return <QuoteCard key={i} msg={m} engine={engine} />;
                  if (m.kind === "notice") return <NoticeCard key={i} msg={m} />;
                  if (m.kind === "design-close") return <DesignCloseCard key={i} msg={m} onViewVersion={onViewVersion} />;
                  if (m.kind === "build-close") return <BuildCloseCard key={i} msg={m} onViewVersion={onViewVersion} />;
                  /* 工作消息（已定格入流）——真直播卡喂假快照 */
                  return (
                    <Message key={i}>
                      <MessageAvatar className="size-6 bg-muted">
                        <Monitor className="size-3.5 text-muted-foreground" />
                      </MessageAvatar>
                      <MessageContent>
                        <WorkMessage work={m.work} plan={m.plan} closingArrived />
                      </MessageContent>
                    </Message>
                  );
                })}
                {/* 生长中的直播卡（设计执行体 / 构建 run） */}
                {state.work ? (
                  <Message>
                    <MessageAvatar className="size-6 bg-muted">
                      <Monitor className="size-3.5 text-muted-foreground" />
                    </MessageAvatar>
                    <MessageContent>
                      <WorkMessage work={state.work} plan={state.workPlan} />
                    </MessageContent>
                  </Message>
                ) : null}
              </MessageGroup>
            </MessageScrollerContent>
          </MessageScrollerViewport>
        </MessageScroller>
      </MessageScrollerProvider>
      {/* gate 提示条：轮到走查者动手了 */}
      {engine.gate ? (
        <div className="mx-4 mb-1 flex items-start gap-2 rounded-xl border border-amber-500/40 bg-amber-500/10 px-3 py-2 text-[13px] text-amber-800 dark:text-amber-300">
          <ImageIcon className="mt-0.5 size-3.5 shrink-0" />
          {engine.gate.hint}
        </div>
      ) : null}
      {/* 编辑作用域＋发散档（stitch：选中＝编辑作用域、REFINE/EXPLORE/REIMAGINE 三档） */}
      {state.picked ? (
        <div className="mx-4 mb-1.5 flex flex-wrap items-center gap-1.5 text-xs">
          <span className="flex items-center gap-1 rounded-full border border-primary/30 bg-primary/5 px-2 py-1 text-primary">
            就「{pickedName}」改
            <button
              className="rounded-full p-0.5 transition-colors hover:bg-primary/10"
              aria-label="取消作用域"
              onClick={() => engine.commit({ t: "pick", id: state.picked! })}
            >
              <X className="size-3" />
            </button>
          </span>
          <span className="text-muted-foreground">发散幅度</span>
          {(["refine", "explore", "reimagine"] as const).map((r) => (
            <button
              key={r}
              onClick={() => setRange(r)}
              className={cn(
                "rounded-full border px-2 py-0.5 transition-colors",
                range === r ? "border-foreground/40 bg-muted font-medium" : "text-muted-foreground hover:bg-muted/50",
              )}
            >
              {r === "refine" ? "微调" : r === "explore" ? "探索" : "大胆"}
            </button>
          ))}
          <span className="text-[10px] text-muted-foreground/50">微调 / 探索 / 大胆（三档发散度）</span>
        </div>
      ) : null}
      <div className="shrink-0 bg-gradient-to-t from-background via-background to-transparent p-4 pt-6">
        <Composer
          value={text}
          onValueChange={setText}
          onSubmit={onSubmit}
          attachmentsEnabled
          placeholder={state.runActive ? "可以说想改什么——这轮做完就处理（排队中）…" : "说说想改什么…"}
        />
      </div>
    </div>
  );
}
