"use client";

import { Archive, ChevronRight, CircleCheck, Hourglass, PackageCheck } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
import { Skeleton } from "@/components/ui/skeleton";
import { useCancelOrder, useOrder, usePayOrder } from "@/hooks/use-order";
import { errorText } from "@/lib/api/api-error";
import { ORDER_STATUS } from "@/lib/orders/lock";
import type { OrderDetail, OrderPriceEntry } from "@/lib/orders/detail";
import { formatPrice } from "@/lib/orders/price";
import { formatRelativeTime } from "@/lib/utils/time";

import { PanelPlaceholder } from "./panel-placeholder";

/**
 * 订单面板（#28 交易环① + #29 交易环② + #30 交易环③，项目模式主区域）：待报价 =
 * 状态 + 等待文案 + 取消；已报价（=待支付）= 总价 + 后台备注 + 去支付（确认弹窗
 * → mock 支付端点）+ 折叠改价历史 + 取消；已归档终态 = 支付完成说明 + 完整记录
 * （总价/全时间点组/报价记录）+ 源码包下载。后台报/改价经机机接口（无前端入口，
 * 联调走脚本），等待期详情轮询使新价实时可见。订单态变化的 toast 归 SSE 桥
 * （#30），本组件只出动作反馈（失败）与终态呈现。
 */
export function OrderPanel({
  orderId,
  projectArchived = false,
}: {
  orderId?: string | null;
  /** 项目归档终态（无订单时的占位文案口径：已归档不再引导下单）。 */
  projectArchived?: boolean;
}) {
  if (orderId == null) {
    return projectArchived ? (
      <PanelPlaceholder icon={<Archive />} title="项目已归档">
        没有订单记录；如需继续，请新建项目
      </PanelPlaceholder>
    ) : (
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
  const pay = usePayOrder();
  const [payOpen, setPayOpen] = useState(false);

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
  const archived = order.status === ORDER_STATUS.archived;

  const onPay = () => {
    setPayOpen(false);
    // 确认即收弹窗触支付：成功反馈归 SSE 桥的订单态 toast + 本卡转终态呈现
    // （失效重拉），失败全局 error toast——不双报
    pay.mutate(orderId, { onError: (error) => toast.error(errorText(error)) });
  };

  return (
    <div className="h-full min-h-0 overflow-y-auto p-4">
      <Card className="gap-4 py-6">
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-base">
            {waitingQuote ? (
              <Hourglass className="size-4" />
            ) : archived ? (
              <Archive className="size-4" />
            ) : (
              <CircleCheck className="size-4" />
            )}
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
          {archived ? (
            <ArchivedRecord order={order} />
          ) : (
            <p className="text-xs text-muted-foreground/70">
              下单于 {formatRelativeTime(order.createdAt) || "未知时间"}
              {order.quotedAt ? ` · 报价于 ${formatRelativeTime(order.quotedAt) || "未知时间"}` : ""}
              {order.cancelledAt ? " · 已取消" : ""}
            </p>
          )}
          {unpaid ? (
            <div className="flex items-center gap-2">
              {!waitingQuote ? (
                <Button size="sm" onClick={() => setPayOpen(true)}>
                  去支付
                </Button>
              ) : null}
              <Button
                variant="outline"
                size="sm"
                disabled={cancel.isPending}
                onClick={() =>
                  cancel.mutate(orderId, {
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
      {!waitingQuote ? <PriceHistory order={order} terminal={archived} /> : null}

      {/* mock 支付确认（v1 平台内模拟）：确认即同步成功——订单与项目一并归档 */}
      <AlertDialog open={payOpen} onOpenChange={setPayOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认支付 {formatPrice(order.amount, order.currency) ?? ""}？</AlertDialogTitle>
            <AlertDialogDescription>
              支付成功后订单与项目将一并归档，项目转入只读终态——完整记录会保留在这里，
              源码包可随时下载；如还需修改，请先取消订单。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>再想想</AlertDialogCancel>
            <AlertDialogAction variant="destructive" onClick={onPay}>
              确认支付
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

/** 已报价（=待支付）/终态事实面：总价 + 后台报价说明。 */
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
 * 归档终态「完整记录」（#30）：支付完成说明 + 全时间点组（下单/报价/支付/归档）
 * + 源码包下载（交付物经项目源码包端点实时取，排除 node_modules/.env）。
 */
function ArchivedRecord({ order }: { order: OrderDetail }) {
  const paidAt = formatRelativeTime(order.paidAt) || formatRelativeTime(order.archivedAt);
  return (
    <div className="space-y-3">
      <p className="text-muted-foreground">
        支付完成，订单与项目已归档。系统源码包可随时下载，完整记录保留如下。
      </p>
      <p className="text-xs text-muted-foreground/70">
        下单于 {formatRelativeTime(order.createdAt) || "未知时间"}
        {order.quotedAt ? ` · 报价于 ${formatRelativeTime(order.quotedAt) || "未知时间"}` : ""}
        {paidAt ? ` · 支付于 ${paidAt}` : ""}
        {order.archivedAt ? ` · 归档于 ${formatRelativeTime(order.archivedAt) || "未知时间"}` : ""}
      </p>
      {order.projectId ? (
        <Button
          variant="outline"
          size="sm"
          nativeButton={false}
          render={<a href={`/api/projects/${order.projectId}/source-package`} />}
        >
          下载源码包
        </Button>
      ) : null}
    </div>
  );
}

/**
 * 折叠价格历史（时间 + 金额 + 备注，新 → 旧）：只追加不改写。未支付态两次报价
 * 起展示（首次报价不算「改价」）；归档终态（terminal）改为完整报价记录——含
 * 首次报价在内的每一条都展示。
 */
function PriceHistory({ order, terminal = false }: { order: OrderDetail; terminal?: boolean }) {
  const reprices = order.priceEntries.length - 1;
  if (!terminal && reprices < 1) {
    return null;
  }
  return (
    <Collapsible className="mt-4" defaultOpen={terminal}>
      <CollapsibleTrigger
        className="group flex w-full items-center gap-1.5 text-sm text-muted-foreground transition-colors hover:text-foreground"
        aria-label="展开价格历史"
      >
        <ChevronRight className="size-4 transition-transform group-data-[panel-open]:rotate-90" />
        <span className="font-medium">
          {terminal ? `报价记录（${order.priceEntries.length} 条）` : `改价历史（${reprices} 次）`}
        </span>
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
