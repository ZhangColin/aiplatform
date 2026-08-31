"use client";

import { ChevronRight, CircleCheck, Hourglass, PackageCheck } from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
import { Skeleton } from "@/components/ui/skeleton";
import { useCancelOrder, useOrder } from "@/hooks/use-order";
import { errorText } from "@/lib/api/api-error";
import { ORDER_STATUS } from "@/lib/orders/lock";
import type { OrderDetail, OrderPriceEntry } from "@/lib/orders/detail";
import { formatPrice } from "@/lib/orders/price";
import { formatRelativeTime } from "@/lib/utils/time";

import { PanelPlaceholder } from "./panel-placeholder";

/**
 * 订单面板（#28 交易环① + #29 交易环②，项目模式主区域）：当前态卡——待报价 =
 * 状态 + 等待文案 + 取消；已报价（=待支付）= 总价 + 后台备注 + 去支付 + 折叠
 * 改价历史 + 取消。后台报/改价经机机接口（无前端入口，联调走脚本），等待期
 * 详情轮询使新价实时可见。归档终态记录随支付切片（#30）。
 */
export function OrderPanel({ orderId }: { orderId?: string | null }) {
  if (orderId == null) {
    return (
      <PanelPlaceholder icon={<PackageCheck />} title="还没有订单">
        对系统满意时，在左侧点「确认下单」，订单会在这里呈现
      </PanelPlaceholder>
    );
  }
  return <OrderCard orderId={orderId} />;
}

function OrderCard({ orderId }: { orderId: string }) {
  const { data: order, isPending } = useOrder(orderId);
  const cancel = useCancelOrder();

  if (isPending || !order) {
    return (
      <div className="p-4">
        <Card className="gap-4 py-6">
          <CardHeader>
            <Skeleton className="h-5 w-24" />
          </CardHeader>
          <CardContent className="space-y-3">
            <Skeleton className="h-4 w-2/3" />
            <Skeleton className="h-4 w-1/3" />
          </CardContent>
        </Card>
      </div>
    );
  }

  const unpaid =
    order.status === ORDER_STATUS.pendingQuote || order.status === ORDER_STATUS.quoted;
  const waitingQuote = order.status === ORDER_STATUS.pendingQuote;

  return (
    <div className="h-full min-h-0 overflow-y-auto p-4">
      <Card className="gap-4 py-6">
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-base">
            {waitingQuote ? <Hourglass className="size-4" /> : <CircleCheck className="size-4" />}
            {order.statusName ?? "订单"}
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-4 text-sm">
          {waitingQuote ? (
            <p className="text-muted-foreground">
              已收到您的订单，后台正在评估报价；报价出来后会在这里呈现价格与说明。
            </p>
          ) : (
            <QuotedFacts order={order} />
          )}
          <p className="text-xs text-muted-foreground/70">
            下单于 {formatRelativeTime(order.createdAt) || "未知时间"}
            {order.quotedAt ? ` · 报价于 ${formatRelativeTime(order.quotedAt) || "未知时间"}` : ""}
            {order.cancelledAt ? " · 已取消" : ""}
          </p>
          {unpaid ? (
            <div className="flex items-center gap-2">
              {!waitingQuote ? (
                <Button size="sm" disabled title="在线支付即将开通">
                  去支付
                </Button>
              ) : null}
              <Button
                variant="outline"
                size="sm"
                disabled={cancel.isPending}
                onClick={() =>
                  cancel.mutate(orderId, {
                    onSuccess: () => toast.success("已取消订单，可以继续修改系统"),
                    onError: (error) => toast.error(errorText(error)),
                  })
                }
              >
                取消订单
              </Button>
              <span className="text-xs text-muted-foreground">
                取消后回到迭代，可继续修改再重新下单
              </span>
            </div>
          ) : null}
        </CardContent>
      </Card>
      {!waitingQuote ? <PriceHistory order={order} /> : null}
    </div>
  );
}

/** 已报价（=待支付）事实面：总价 + 后台报价说明。 */
function QuotedFacts({ order }: { order: OrderDetail }) {
  const price = formatPrice(order.amount, order.currency);
  return (
    <div className="space-y-2">
      <p className="text-2xl font-semibold tabular-nums">{price ?? "价格待定"}</p>
      {order.note ? <p className="text-muted-foreground">后台说明：{order.note}</p> : null}
    </div>
  );
}

/**
 * 折叠改价历史（时间 + 金额 + 备注，新 → 旧）：只追加不改写，首次报价不算
 * 「改价」——两次报价起展示。
 */
function PriceHistory({ order }: { order: OrderDetail }) {
  const reprices = order.priceEntries.length - 1;
  if (reprices < 1) {
    return null;
  }
  return (
    <Collapsible className="mt-4">
      <CollapsibleTrigger
        className="group flex w-full items-center gap-1.5 text-sm text-muted-foreground transition-colors hover:text-foreground"
        aria-label="展开改价历史"
      >
        <ChevronRight className="size-4 transition-transform group-data-[panel-open]:rotate-90" />
        <span className="font-medium">改价历史（{reprices} 次）</span>
      </CollapsibleTrigger>
      <CollapsibleContent>
        <ul className="mt-3 space-y-2">
          {order.priceEntries.map((entry) => (
            <PriceHistoryRow key={entry.id || entry.createdAt} entry={entry} />
          ))}
        </ul>
      </CollapsibleContent>
    </Collapsible>
  );
}

function PriceHistoryRow({ entry }: { entry: OrderPriceEntry }) {
  return (
    <li className="flex items-baseline justify-between gap-4 rounded-md border px-3 py-2 text-sm">
      <div className="min-w-0">
        <p className="font-medium tabular-nums">{formatPrice(entry.amount, entry.currency) ?? "—"}</p>
        {entry.note ? (
          <p className="truncate text-xs text-muted-foreground">{entry.note}</p>
        ) : null}
      </div>
      <span className="shrink-0 text-xs text-muted-foreground/70">
        {formatRelativeTime(entry.createdAt) || ""}
      </span>
    </li>
  );
}
