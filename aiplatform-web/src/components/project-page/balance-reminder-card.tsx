"use client";

import { CircleAlert, Wallet, X } from "lucide-react";

import { Button } from "@/components/ui/button";
import { useMe } from "@/hooks/use-me";
import { useChatStore, type ChatMessage } from "@/lib/store/chat";
import { useCheckoutStore } from "@/lib/store/checkout";
import { useM01Store } from "@/lib/store/m01";

/**
 * 余额提醒卡（#537 计费③段间硬门，ADR-0047「段间硬门段中自由」）：付费项目
 * 段边界源池≤0 被拦的平台侧卡——显当前负额＋充值入口（到账自动放行，不手动
 * 重试——区别于拦截卡的「确认扣减」动作，本卡只有「去充值」引导）。有留存的卡
 * （意见/咨询车道）引用被拦原文；run 场拦截（生成/修正）无留存——只有负额与
 * 暂停事实。中途关闭＝本地面移除（有留存的卡可回：GET 读面
 * 重载重建；run 场卡一次性呈现）。文案为平台定型文案（代码承载，非 LLM 产）。
 */

type BalanceReminderMessage = Extract<ChatMessage, { kind: "balance-reminder" }>;

/** 车道呈现名（封闭词表；run 场拦截无车道）。 */
const LANE_LABELS: Record<string, string> = {
  OPINION: "修改意见",
  INQUIRY: "咨询",
};

/** 分 → 元呈现（负额显欠费形：-¥5.00）。 */
function yuanText(fen: number | undefined): string {
  if (fen === undefined) return "—";
  return fen < 0 ? `-¥${(-fen / 100).toFixed(2)}` : `¥${(fen / 100).toFixed(2)}`;
}

export function BalanceReminderCard({
  projectId,
  message,
}: {
  projectId: string;
  message: BalanceReminderMessage;
}) {
  const { card } = message;
  const me = useMe();
  const loggedIn = me.data != null;
  const dismiss = () => useChatStore.getState().dismissBalanceReminder(projectId);
  /** run 场拦截（生成/修正）——无留存无原文，只报暂停事实。 */
  const runBlocked = !card.holdId;
  const quoted = card.lane ? LANE_LABELS[card.lane] : undefined;

  function recharge() {
    // 收银台（#534）：项目上下文开模拟收银台——到账自动放行（服务端认领恢复/
    // run 场排空）；未登录先 M01（checkout-login 来路），登录成功 resume 重开
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
      data-slot="balance-reminder-card"
      className="flex w-full max-w-lg flex-col gap-3 rounded-2xl border border-rose-300/60 bg-rose-50/60 p-4 shadow-xs"
    >
      <div className="flex items-start gap-2">
        <CircleAlert className="mt-0.5 size-4 shrink-0 text-rose-600" />
        <div className="flex min-w-0 flex-col gap-0.5">
          <span className="text-sm font-semibold text-rose-900">源池余额不足，处理已暂停</span>
          <span className="text-xs leading-relaxed text-rose-800/80">
            当前源池余额 {yuanText(card.sourcePoolBalance)}（已欠费）。
            {runBlocked
              ? "被暂停的系统任务"
              : quoted
                ? `被拦下的${quoted}`
                : "被拦下的输入"}
            会在充值到账后自动继续，无需重新发送。
          </span>
        </div>
        <button
          type="button"
          aria-label="关闭"
          data-balance-reminder-dismiss
          onClick={dismiss}
          className="ml-auto shrink-0 rounded-md p-1 text-rose-800/60 transition-colors hover:bg-rose-100 hover:text-rose-900"
        >
          <X className="size-3.5" />
        </button>
      </div>

      {card.prompt ? (
        <blockquote
          data-balance-reminder-prompt
          className="line-clamp-3 rounded-lg border border-rose-200/70 bg-white/70 px-3 py-2 text-xs text-foreground/80"
        >
          {card.prompt}
        </blockquote>
      ) : null}

      <div className="flex items-center gap-2">
        <Button size="sm" data-balance-reminder-recharge onClick={recharge}>
          <Wallet className="size-3.5" />
          去充值
        </Button>
        <span className="text-xs text-rose-800/70">充值到账后自动继续</span>
      </div>
    </div>
  );
}
