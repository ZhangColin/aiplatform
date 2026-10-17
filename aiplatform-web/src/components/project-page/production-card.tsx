"use client";

import { CalendarClock, Factory, MessageCircleQuestion } from "lucide-react";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { DELIVERY_STATE, type DeliveryStatus } from "@/lib/orders/production";
import { formatRelativeTime } from "@/lib/utils/time";

/**
 * 生产状态卡（#338 生产承接·前台六态最小呈现，挂订单面）：六态（待签约/
 * 待付款/款项核验中/待安排生产/生产中/需用户补充）＝后端四源纯推导投影，
 * 这里只呈现不推导；承接字段（交付联系人/下一步说明/进展更新〔人工措辞〕/
 * 预计开始时间）<b>仅真实值显示</b>——排期未定显示「待安排」、空字段不渲染，
 * 不被假日期糊弄（AC67）。「需用户补充」盖帽态以横幅双呈现（处理入口＝
 * 对话区沟通）。全部状态推进归后台人工（六态在这里只呈现不操作）；完整
 * 交付生命周期页重建归后续 IA 票（#349），本卡承数据面最小呈现。
 */
export function ProductionCard({ status }: { status: DeliveryStatus }) {
  const handoff = status.handoff;

  return (
    <Card className="mt-4 gap-4 py-6">
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base">
          <Factory className="size-4" />
          生产承接
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-4 text-sm">
        <div className="flex items-center justify-between gap-4">
          <p className="font-medium">{status.stateName ?? "交付处理中"}</p>
          {handoff?.statusName ? (
            <p className="text-xs text-muted-foreground">{handoff.statusName}</p>
          ) : null}
        </div>
        {status.state === DELIVERY_STATE.awaitingProduction ? (
          <p className="text-muted-foreground">
            开工条件已满足，平台正在安排生产；排期确定后会在这里更新。
          </p>
        ) : null}
        {status.state === DELIVERY_STATE.inProduction ? (
          <p className="text-muted-foreground">生产进行中，进展会在这里持续更新。</p>
        ) : null}
        {status.needsUserSupplement ? (
          <p className="flex items-start gap-1.5 rounded-md border border-amber-500/40 bg-amber-500/10 px-3 py-2 text-xs text-amber-600 dark:text-amber-400">
            <MessageCircleQuestion className="mt-0.5 size-3.5 shrink-0" />
            需要您提供一些资料或确认一些事项，请回到对话区继续沟通，处理后会恢复推进
          </p>
        ) : null}
        {handoff ? (
          <dl className="space-y-2">
            <HandoffRow label="交付联系人" value={handoff.contact} />
            <HandoffRow label="下一步" value={handoff.nextStep} />
            <HandoffRow label="进展更新" value={handoff.progressNote} />
            {handoff.estimatedStartAt ? (
              <div className="flex items-baseline justify-between gap-4 border-b pb-2 last:border-b-0 last:pb-0">
                <dt className="flex shrink-0 items-center gap-1 text-xs text-muted-foreground">
                  <CalendarClock className="size-3" />
                  预计开始
                </dt>
                <dd className="min-w-0 text-right">
                  {formatRelativeTime(handoff.estimatedStartAt)}
                </dd>
              </div>
            ) : null}
          </dl>
        ) : null}
      </CardContent>
    </Card>
  );
}

/** 承接字段行：仅真实值显示——空段不渲染（不虚构「待录入」占位）。 */
function HandoffRow({ label, value }: { label: string; value?: string }) {
  if (!value?.trim()) {
    return null;
  }
  return (
    <div className="flex items-baseline justify-between gap-4 border-b pb-2 last:border-b-0 last:pb-0">
      <dt className="shrink-0 text-xs text-muted-foreground">{label}</dt>
      <dd className="min-w-0 text-right break-words">{value}</dd>
    </div>
  );
}
