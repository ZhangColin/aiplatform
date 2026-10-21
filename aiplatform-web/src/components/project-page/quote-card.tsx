"use client";

import { CircleDollarSign } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { useOrder } from "@/hooks/use-order";
import { formatPrice } from "@/lib/orders/price";

/**
 * 报价卡（#203 报价感知，ADR-0017 视镜语义）：平台对用户的钱事发言，与问答卡/
 * 收尾卡同族。卡载事件（quoted=报价已出 / repriced=报价已更新，#204 改价入流）
 * + 订单引用——金额/备注/状态渲染时取订单当前态（useOrder 详情查询，待报价/
 * 已报价态轮询现成），卡不冻结金额：已支付/已取消/已归档转态由同一视镜自然呈现，
 * 不残留可支付假象。mock 支付闭环已随 #337 退役（卡内无支付动作，付款走签约页
 * 凭证核验路径）；订单详情与改价历史以签约付款页为家（「查看订单详情」跳转
 * 回调＝装配层 onSeeOrder）。
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

  const price = formatPrice(order.amount, order.currency);

  return (
    <div
      className="w-full max-w-md rounded-xl border border-amber-600/25 bg-amber-500/[0.06] p-3"
      data-slot="quote-card"
    >
      <div className="mb-1.5 flex items-center gap-2">
        <CircleDollarSign className="size-4 shrink-0 text-amber-600" />
        <span className="text-sm font-semibold">{title}</span>
        {order.statusName ? (
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
        <Button variant="outline" size="sm" onClick={onSeeOrder}>
          查看订单详情
        </Button>
      </div>
    </div>
  );
}
