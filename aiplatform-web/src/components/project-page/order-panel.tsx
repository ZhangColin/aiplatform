"use client";

import { Archive, ChevronRight, CircleCheck, Hourglass, PackageCheck } from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
import { Skeleton } from "@/components/ui/skeleton";
import { useConfirmScope, useContract } from "@/hooks/use-contract";
import { usePaymentPlan } from "@/hooks/use-payment";
import { useDeliveryStatus } from "@/hooks/use-production";
import { useCancelOrder, useOrder } from "@/hooks/use-order";
import { errorText } from "@/lib/api/api-error";
import { ORDER_STATUS } from "@/lib/orders/lock";
import {
  DELIVERABLE_DESIGN,
  DELIVERABLE_SYSTEM_DESIGN,
  type OrderDetail,
  type OrderPriceEntry,
} from "@/lib/orders/detail";
import { formatPrice } from "@/lib/orders/price";
import { formatRelativeTime } from "@/lib/utils/time";

import { ContractCard } from "./contract-card";
import { PanelPlaceholder } from "./panel-placeholder";
import { PaymentPanel } from "./payment-panel";
import { ProductionCard } from "./production-card";

/**
 * 订单面板（#28 交易环① + #29 交易环②，项目模式主区域；#335 待确认报价
 * 呈现态＋下单基准行；#336 范围确认面＋合同预览卡；#337 付款面板·mock 支付
 * 闭环退役；#338 生产状态卡·前台六态）：待报价 = 状态 + 「待确认报价」占位态（价格位以待定态名占位，
 * 不以 0 元或示例价代替；报价落定后翻价格）+ 下单基准（随单冻结的 PRD/Demo
 * 确认版本）+ 取消；已报价（=待支付）= 总价 + 后台备注 + 范围文本三段与
 * 「确认范围并准备合同」（确认即冻结范围快照＋创建合同）+ 折叠改价历史 +
 * 取消 + 合同预览卡（六态/签署位/预览字段集）+ 付款面板（节点五态/凭证上传，
 * 支付唯一用户面）+ 生产状态卡（前台六态/承接字段仅真实值显示）；已归档
 * 终态 = 支付完成说明 + 完整记录（总价/全时间点组/报价记录）+ 源码包下载。
 * 内容向三子步骤（核对范围与报价/签署合同/付款）重组的完整呈现归签约付款
 * 页重建（#348），本面板承数据面最小呈现。后台报/改价经机机接口（无前端
 * 入口，联调走脚本），等待期详情轮询使新价实时可见。订单态变化的 toast 归
 * SSE 桥（#30），本组件只出动作反馈（失败）与终态呈现。
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
        对系统满意时，点右上角「确认下单」，订单会在这里呈现
      </PanelPlaceholder>
    );
  }
  return <OrderCard orderId={orderId} />;
}

function OrderCard({ orderId }: { orderId: string }) {
  const { data: order, isPending } = useOrder(orderId);
  // 合同域（#336）：无合同 404 在 hook 归一为 null（缺席＝不渲染卡片）；
  // 非终态挂轮询（后台人工推进六态）
  const { data: contract } = useContract(orderId);
  const cancel = useCancelOrder();
  const confirmScope = useConfirmScope();
  // 付款域（#337）：无计划 404 在 hook 归一为 null（缺席＝不渲染面板）；
  // 有未到账节点挂轮询（后台人工核验）
  const { data: paymentPlan } = usePaymentPlan(orderId, order?.status);
  // 交付状态（#338 前台六态）：无未终结订单 404 归一为 null、待报价 state
  // null（缺席＝不渲染生产状态卡）；开工转已支付经 SSE 随 orders 域失效、
  // 承接推进经轮询兜底
  const { data: deliveryStatus } = useDeliveryStatus(order?.projectId ?? null);

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
            <div className="space-y-2">
              {/* 「待确认报价」占位呈现态（#335）：价格位以待定态名占位——不以
                  0 元或示例价代替；后台落定报价（＋范围文本，#336 起）后翻价格 */}
              <p className="text-2xl font-semibold">待确认报价</p>
              <p className="text-muted-foreground">
                已收到您的订单，后台正在评估范围与报价；报价确认后会在这里呈现价格与说明。
              </p>
            </div>
          ) : (
            <QuotedFacts order={order} />
          )}
          {/* 范围文本（#336 三子步骤「核对范围与报价」的确认面）：未支付态＋
              范围录入后呈现，尚无合同时带确认动作（确认即冻结范围快照＋创建
              合同——已支付/终态同后端门收口，不再出确认入口）；合同进场后范围
              以合同预览卡的范围快照为准，不再重复 */}
          {unpaid && !waitingQuote && order.scopeDeliverables && !contract ? (
            <ScopeFacts
              order={order}
              confirmPending={confirmScope.isPending}
              onConfirm={() =>
                confirmScope.mutate(orderId, {
                  // 前置门拦截（ORD_025/026）已在 hook 出 toast，此处不双报
                })
              }
            />
          ) : null}
          {archived ? (
            <ArchivedRecord order={order} />
          ) : (
            <p className="text-xs text-muted-foreground/70">
              下单于 {formatRelativeTime(order.createdAt) || "未知时间"}
              {orderBasisSuffixOf(order)}
              {order.quotedAt ? ` · 报价于 ${formatRelativeTime(order.quotedAt) || "未知时间"}` : ""}
              {order.cancelledAt ? " · 已取消" : ""}
            </p>
          )}
          {unpaid ? (
            <div className="flex items-center gap-2">
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

      {/* 合同预览卡（#336 最小呈现）：订单名下最近合同（含终态定格呈现） */}
      {contract ? <ContractCard contract={contract} /> : null}

      {/* 付款面板（#337 凭证核验路径）：支付的唯一用户面（mock 支付弹窗已退役） */}
      {paymentPlan ? <PaymentPanel plan={paymentPlan} /> : null}

      {/* 生产状态卡（#338 前台六态·最小呈现）：state 缺席（待报价/无单）不渲染 */}
      {deliveryStatus && deliveryStatus.state != null ? (
        <ProductionCard status={deliveryStatus} />
      ) : null}
    </div>
  );
}

/**
 * 范围文本确认面（#336）：交付清单/验收方式/工期口径三段（后台结构化录入的
 * 工作副本）＋「确认范围并准备合同」动作——确认即冻结范围快照＋报价版本引用
 * 并创建合同（此后合同六态在预览卡推进）。范围未录入时本节不出（等待后台）。
 */
function ScopeFacts({
  order,
  confirmPending,
  onConfirm,
}: {
  order: OrderDetail;
  confirmPending: boolean;
  onConfirm: () => void;
}) {
  return (
    <div className="space-y-2 rounded-md border p-3">
      <p className="text-xs font-medium text-muted-foreground">范围与报价</p>
      <ul className="space-y-1 text-sm">
        <ScopeRow label="交付清单" value={order.scopeDeliverables} />
        <ScopeRow label="验收方式" value={order.scopeAcceptance} />
        <ScopeRow label="工期口径" value={order.scopeTimeline} />
      </ul>
      <div className="flex items-center gap-2 pt-1">
        <Button size="sm" disabled={confirmPending} onClick={onConfirm}>
          确认范围并准备合同
        </Button>
        <span className="text-xs text-muted-foreground">
          确认后冻结范围与报价版本，平台开始准备合同
        </span>
      </div>
    </div>
  );
}

function ScopeRow({ label, value }: { label: string; value?: string | null }) {
  return (
    <li className="flex items-baseline justify-between gap-4">
      <span className="shrink-0 text-xs text-muted-foreground">{label}</span>
      <span className="min-w-0 text-right break-words">{value?.trim() || "—"}</span>
    </li>
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
 * 下单基准后缀（#335 双版本引用——「核对范围与报价」子步骤的数据面）：随单
 * 冻结的 PRD/Demo 确认版本号；确认制前遗留单缺省不虚构（空串）。
 */
function orderBasisSuffixOf(order: OrderDetail): string {
  if (order.prdVersionNo == null || order.demoVersionNo == null) {
    return "";
  }
  return ` · 基准 PRD v${order.prdVersionNo} · Demo v${order.demoVersionNo}`;
}

/**
 * 归档终态「完整记录」（#30）：支付完成说明 + 全时间点组（下单/报价/支付/归档）
 * + 交付物下载（#297 按下单冻结的交付物类型分岔：系统＝源码包实时取〔排除
 * node_modules/.env〕、设计＝设计资产包冻结件、系统＋设计＝统一部件容器冻结件
 * ——源码与选定设计稿同包，目录即类型边界）。
 */
function ArchivedRecord({ order }: { order: OrderDetail }) {
  const paidAt = formatRelativeTime(order.paidAt) || formatRelativeTime(order.archivedAt);
  const deliverable = deliverableDownloadOf(order);
  return (
    <div className="space-y-3">
      <p className="text-muted-foreground">
        支付完成，订单与项目已归档。{deliverable.hint}，完整记录保留如下。
      </p>
      <p className="text-xs text-muted-foreground/70">
        下单于 {formatRelativeTime(order.createdAt) || "未知时间"}
        {orderBasisSuffixOf(order)}
        {order.quotedAt ? ` · 报价于 ${formatRelativeTime(order.quotedAt) || "未知时间"}` : ""}
        {paidAt ? ` · 支付于 ${paidAt}` : ""}
        {order.archivedAt ? ` · 归档于 ${formatRelativeTime(order.archivedAt) || "未知时间"}` : ""}
      </p>
      {deliverable.href ? (
        <Button
          variant="outline"
          size="sm"
          nativeButton={false}
          render={<a href={deliverable.href} />}
        >
          {deliverable.label}
        </Button>
      ) : null}
    </div>
  );
}

/** 归档终态下载口：按交付物类型出端点与文案（锚定形＝订单冻结件按单取；
 * 缺省/未知＝系统——存量单口径）。 */
function deliverableDownloadOf(order: OrderDetail): { href: string | null; label: string; hint: string } {
  if (order.deliverableType === DELIVERABLE_DESIGN) {
    return {
      href: `/api/orders/${order.id}/design-package`,
      label: "下载设计资产包",
      hint: "设计资产包可随时下载（选定稿＋设计规范＋衍生）",
    };
  }
  if (order.deliverableType === DELIVERABLE_SYSTEM_DESIGN) {
    return {
      href: `/api/orders/${order.id}/design-package`,
      label: "下载交付物包",
      hint: "交付物包可随时下载（系统源码＋选定设计稿同包）",
    };
  }
  return {
    href: order.projectId ? `/api/projects/${order.projectId}/source-package` : null,
    label: "下载源码包",
    hint: "系统源码包可随时下载",
  };
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
