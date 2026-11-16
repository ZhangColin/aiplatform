"use client";

import { CircleAlert, Check, Wallet, X } from "lucide-react";
import { useState } from "react";

import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { useConfirmIntercept } from "@/hooks/use-intercept";
import { useMe } from "@/hooks/use-me";
import { useChatStore, type ChatMessage } from "@/lib/store/chat";
import { useCheckoutStore } from "@/lib/store/checkout";
import { useM01Store } from "@/lib/store/m01";
import { cn } from "@/lib/utils";

/**
 * 试用拦截卡（#533 计费②门②，ADR-0047「试用拦截面＝平台结构闸」）：试用项目
 * （迭代期）意见/兜底输入被拦下的平台侧卡——被拦原文随卡留存（服务端
 * bil_intercepts 待决行），卡上显源池（扣减源未定随卡首次选源——「本项目余额／
 * 我的账户」，第一笔付费选择永远最先选）。确认扣减＝翻转门②（付费状态不可逆）
 * 并立即受理原被拦输入（不重输）；「先去充值」开模拟收银台（#534）——充值后卡
 * 回读即显源池余额可直确认；中途关闭后输入不丢失、卡可回（GET 读面重载重建）。后台已充项目池的项目源已选定——免登录直接确认（销售
 * 演示动线）。文案为平台定型文案（代码承载，非 LLM 产）。
 */

type InterceptMessage = Extract<ChatMessage, { kind: "intercept" }>;

/** 扣减源码位（后端 BaseEnum 契约：1=账户余额 2=项目余额）。 */
const SOURCE_PROJECT = 2;
const SOURCE_ACCOUNT = 1;

/** 分 → 元呈现。 */
function yuan(fen: number | undefined): string | undefined {
  return fen === undefined ? undefined : (fen / 100).toFixed(2);
}

export function InterceptCard({
  projectId,
  message,
}: {
  projectId: string;
  message: InterceptMessage;
}) {
  const { card, settled } = message;
  const confirm = useConfirmIntercept(projectId);
  const me = useMe();
  const loggedIn = me.data != null;
  const dismiss = () =>
    useChatStore.getState().dismissIntercept(projectId, card.interceptId);
  /** 源未定随卡选源（本地选择态——确认随命令上送；选定后不呈现选择区）。 */
  const [picked, setPicked] = useState<number | null>(null);

  const isOpinion = card.lane === "OPINION";
  const quoted = isOpinion ? "你的修改意见" : "这条消息";
  const needPick = !card.sourceSelected;
  const directConfirm = card.sourceSelected; // 源已选定（后台已充项目池＝免登录直确认）
  const pickedSource = needPick ? picked : (card.deductionSource ?? null);
  const balanceText = yuan(card.sourcePoolBalance);

  function confirmDeduction() {
    if (settled || confirm.isPending) return;
    confirm.mutate({
      interceptId: card.interceptId,
      ...(pickedSource == null ? {} : { source: pickedSource as 1 | 2 }),
    });
  }

  function rechargeGuide() {
    // 收银台（#534）已落地：项目上下文开模拟收银台——只充当前扣减源池；
    // 卡不关闭（被拦输入随服务端留存，充值后卡回读显源池余额可直确认）；
    // 未登录先 M01（checkout-login 来路），登录成功 resume 重开收银台
    if (loggedIn) {
      useCheckoutStore.getState().openCheckout(projectId);
    } else {
      useM01Store.getState().openM01("checkout-login", () => {
        useCheckoutStore.getState().openCheckout(projectId);
      });
    }
  }

  return (
    <div
      data-slot="intercept-card"
      data-settled={settled ? "true" : undefined}
      className="flex w-full max-w-lg flex-col gap-3 rounded-2xl border border-amber-300/60 bg-amber-50/60 p-4 shadow-xs"
    >
      <div className="flex items-start gap-2">
        <CircleAlert className="mt-0.5 size-4 shrink-0 text-amber-600" />
        <div className="flex min-w-0 flex-col gap-0.5">
          <span className="text-sm font-semibold text-amber-900">
            {settled ? "已确认扣减，正在继续处理" : "继续迭代需确认付费"}
          </span>
          <span className="text-xs leading-relaxed text-amber-800/80">
            试用项目的修改与迭代在首个 Demo 生成后开始计费。确认后项目转为付费，
            {quoted}会立即继续处理，无需重新发送。
          </span>
        </div>
        <button
          type="button"
          aria-label="关闭"
          data-intercept-dismiss
          onClick={dismiss}
          className="ml-auto shrink-0 rounded-md p-1 text-amber-800/60 transition-colors hover:bg-amber-100 hover:text-amber-900"
        >
          <X className="size-3.5" />
        </button>
      </div>

      <blockquote
        data-intercept-prompt
        className="line-clamp-3 rounded-lg border border-amber-200/70 bg-white/70 px-3 py-2 text-xs text-foreground/80"
      >
        {card.prompt}
      </blockquote>

      {settled ? (
        <div className="flex items-center gap-1.5 text-xs text-green-700">
          <Check className="size-3.5" strokeWidth={3} />
          项目已转为付费，{quoted}已进入处理队列。
        </div>
      ) : (
        <>
          <div className="flex flex-col gap-1.5" data-intercept-source>
            <span className="flex items-center gap-1 text-xs font-medium text-amber-900">
              <Wallet className="size-3.5" />
              扣减来源{needPick ? "（首次付费请选择）" : ""}
            </span>
            {directConfirm ? (
              <span className="pl-5 text-xs text-amber-800/80" data-intercept-source-summary>
                {card.deductionSource === SOURCE_ACCOUNT ? "我的账户（账户余额）" : "本项目余额"}
                {balanceText !== undefined ? ` · 当前余额 ¥${balanceText}` : ""}
              </span>
            ) : (
              <div className="flex flex-col gap-1 pl-5">
                <SourceOption
                  value={SOURCE_PROJECT}
                  picked={pickedSource}
                  onPick={setPicked}
                  title="本项目余额"
                  hint="为这个项目单独充值、仅本项目使用"
                />
                <SourceOption
                  value={SOURCE_ACCOUNT}
                  picked={pickedSource}
                  onPick={setPicked}
                  title="我的账户"
                  hint={loggedIn ? "账户余额跨项目共用" : "账户余额跨项目共用（需先登录）"}
                />
              </div>
            )}
          </div>

          <div className="flex items-center gap-2">
            <Button
              size="sm"
              data-intercept-confirm
              disabled={needPick && pickedSource == null}
              onClick={confirmDeduction}
            >
              {confirm.isPending ? <Spinner className="size-3.5" /> : null}
              确认并继续
            </Button>
            <Button size="sm" variant="outline" data-intercept-recharge onClick={rechargeGuide}>
              先去充值
            </Button>
          </div>
        </>
      )}
    </div>
  );
}

/** 随卡选源单选项（源未定的首次选源——「本项目余额／我的账户」）。 */
function SourceOption({
  value,
  picked,
  onPick,
  title,
  hint,
}: {
  value: number;
  picked: number | null;
  onPick: (value: number) => void;
  title: string;
  hint: string;
}) {
  const active = picked === value;
  return (
    <button
      type="button"
      data-source-pick={value}
      aria-pressed={active}
      onClick={() => onPick(value)}
      className={cn(
        "flex flex-col items-start rounded-lg border px-3 py-1.5 text-left transition-colors",
        active
          ? "border-amber-500/70 bg-white shadow-xs"
          : "border-amber-200/60 bg-white/50 hover:border-amber-400/60",
      )}
    >
      <span className={cn("text-xs font-medium", active ? "text-amber-900" : "text-foreground/80")}>
        {title}
      </span>
      <span className="text-xs text-muted-foreground">{hint}</span>
    </button>
  );
}
