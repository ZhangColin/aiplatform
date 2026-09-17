"use client";

import { CircleDollarSign } from "lucide-react";
import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";

import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { useOrder, usePayOrder } from "@/hooks/use-order";
import { queryKeys } from "@/lib/api/keys";
import { ORDER_STATUS } from "@/lib/orders/lock";
import { formatPrice } from "@/lib/orders/price";

import { PayConfirmDialog } from "./pay-confirm-dialog";

/**
 * 报价卡（#203 报价感知，ADR-0017 视镜语义）：平台对用户的钱事发言，与问答卡/
 * 收尾卡同族。卡载事件（quoted=报价已出 / repriced=报价已更新，#204 改价入流）
 * + 订单引用——金额/备注/状态渲染时取订单当前态（useOrder 详情查询，待报价/
 * 已报价态轮询现成），卡不冻结金额：已支付/已取消/已归档转态由同一视镜自然呈现，
 * 不残留可支付假象。卡内即支付闭环（复用 mock 支付 mutation 与确认弹窗）；订单
 * tab 保留完整详情与改价历史的家（「查看订单详情」跳转回调归装配层 openOutputsTo）。
 */
export function QuoteCard({
  orderId,
  event,
  onSeeOrder,
}: {
  /** 订单引用（载荷唯一事实——金额与状态不随卡落库）。 */
  orderId: string;
  /** 事件类型（quoted=报价已出 / repriced=报价已更新；未知值兜底同首报）。 */
  event: string;
  /** 「查看订单详情」跳转回调（挂载并切到订单 tab）。 */
  onSeeOrder?: () => void;
}) {
  const { data: order, isPending } = useOrder(orderId);
  const pay = usePayOrder();
  const queryClient = useQueryClient();
  const [payOpen, setPayOpen] = useState(false);

  const title = event === "repriced" ? "报价已更新" : "报价已出";

  if (isPending || !order) {
    return (
      <div
        className="w-full max-w-md rounded-xl border border-amber-600/25 bg-amber-500/[0.06] p-3"
        data-slot="quote-card"
      >
        <div className="mb-1.5 flex items-center gap-2">
          <CircleDollarSign className="size-4 shrink-0 text-amber-600" />
          <span className="text-sm font-semibold">{title}</span>
        </div>
        <Skeleton className="h-6 w-24" />
        <Skeleton className="mt-2 h-4 w-2/3" />
      </div>
    );
  }

  const payable = order.status === ORDER_STATUS.quoted;
  const price = formatPrice(order.amount, order.currency);

  const onPay = () => {
    setPayOpen(false);
    // 确认即收弹窗触支付：成功 = 订单域失效重拉（usePayOrder 既有口径：projects +
    // orders）+ 本卡补失效对话史域（同项目其他报价卡的视镜源同理重查）；失败反馈
    // 归 hook 的全局 error toast——不双报
    pay.mutate(orderId, {
      onSuccess: () =>
        void queryClient.invalidateQueries({ queryKey: queryKeys.conversation.all }),
    });
  };

  return (
    <div
      className="w-full max-w-md rounded-xl border border-amber-600/25 bg-amber-500/[0.06] p-3"
      data-slot="quote-card"
    >
      <div className="mb-1.5 flex items-center gap-2">
        <CircleDollarSign className="size-4 shrink-0 text-amber-600" />
        <span className="text-sm font-semibold">{title}</span>
        {!payable && order.statusName ? (
          <span className="rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground">
            {order.statusName}
          </span>
        ) : null}
      </div>
      <p className="text-xl font-semibold tabular-nums">{price ?? "价格待定"}</p>
      {order.note ? (
        <p className="mt-1 text-sm text-muted-foreground">后台说明：{order.note}</p>
      ) : null}
      <div className="mt-2.5 flex items-center gap-2">
        {payable ? (
          <Button size="sm" onClick={() => setPayOpen(true)}>
            去支付
          </Button>
        ) : null}
        <Button variant="outline" size="sm" onClick={onSeeOrder}>
          查看订单详情
        </Button>
      </div>
      <PayConfirmDialog
        open={payOpen}
        onOpenChange={setPayOpen}
        price={price}
        onConfirm={onPay}
      />
    </div>
  );
}
