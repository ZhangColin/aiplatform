"use client";

import { CircleCheck, Hourglass, PackageCheck } from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { useCancelOrder, useOrder } from "@/hooks/use-order";
import { errorText } from "@/lib/api/api-error";
import { ORDER_STATUS } from "@/lib/orders/lock";
import { formatRelativeTime } from "@/lib/utils/time";

import { PanelPlaceholder } from "./panel-placeholder";

/**
 * 订单面板（#28 交易环①，项目模式主区域）：当前态卡——待报价 = 状态 + 等待
 * 文案 + 取消（未支付态随时取消、取消即解冻回迭代）；无未终结订单 = 引导占位
 * （「确认下单」常驻指令区输入条上方）。总价/后台备注/改价历史随报价切片
 * （#29）接入，归档终态记录随支付切片（#30）。
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
          <p className="text-muted-foreground">
            {waitingQuote
              ? "已收到您的订单，后台正在评估报价；报价出来后会在这里呈现价格与说明。"
              : "订单当前状态如上，后续进展会在这里更新。"}
          </p>
          <p className="text-xs text-muted-foreground/70">
            下单于 {formatRelativeTime(order.createdAt) || "未知时间"}
            {order.cancelledAt ? " · 已取消" : ""}
          </p>
          {unpaid ? (
            <div className="flex gap-2">
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
              <span className="self-center text-xs text-muted-foreground">
                取消后回到迭代，可继续修改再重新下单
              </span>
            </div>
          ) : null}
        </CardContent>
      </Card>
    </div>
  );
}
