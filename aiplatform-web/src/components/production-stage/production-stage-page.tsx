"use client";

import {
  ArrowUpRight,
  CalendarClock,
  FileSignature,
  FileText,
  MessageCircleQuestion,
  MonitorPlay,
  ReceiptText,
  UserRound,
} from "lucide-react";
import Link from "next/link";

import { useDeliveryStatus } from "@/hooks/use-production";
import { useProject } from "@/hooks/use-project";
import { DELIVERY_STATE } from "@/lib/orders/production";
import { formatRelativeTime } from "@/lib/utils/time";

const STATE_DESCRIPTIONS: Record<number, string> = {
  [DELIVERY_STATE.awaitingContract]: "已确认 Demo；等待完成范围和合同。",
  [DELIVERY_STATE.awaitingPayment]: "合同已签署；请按付款节点完成开工款支付。",
  [DELIVERY_STATE.verifyingPayment]: "付款凭证已提交，等待平台核验；确认后安排生产。",
  [DELIVERY_STATE.awaitingProduction]: "开工条件已满足；排期或负责人待安排。",
  [DELIVERY_STATE.inProduction]: "正式生产已启动；显示已确认的进展与下一步。",
  [DELIVERY_STATE.needsUserSupplement]: "需要您补充资料或确认事项；处理后恢复推进。",
};

/** 生产承接页 P07：只呈现四源推导结果与人工措辞，不展示内部过程日志。 */
export function ProductionStagePage({ projectId }: { projectId: string }) {
  const { data: detail } = useProject(projectId);
  const { data: deliveryStatus } = useDeliveryStatus(projectId);
  const handoff = deliveryStatus?.handoff;
  const description = (deliveryStatus?.state != null
    ? STATE_DESCRIPTIONS[deliveryStatus.state]
    : undefined) ?? "交付进展会在这里持续更新。";

  return (
    <div className="mx-auto w-full max-w-[1400px] px-4 py-6 lg:px-8">
      <header className="mb-5">
        <p className="text-label-sm font-semibold tracking-wider text-electric-indigo">生产承接</p>
        <h1 className="mt-1 text-headline-lg font-bold text-ink-primary">
          {detail?.name || "项目交付进展"}
        </h1>
      </header>

      {deliveryStatus?.needsUserSupplement ? (
        <section
          role="alert"
          className="mb-5 flex flex-col gap-3 rounded-2xl border border-warning/30 bg-warning/10 p-4 sm:flex-row sm:items-center"
        >
          <MessageCircleQuestion className="size-5 shrink-0 text-warning-strong" aria-hidden="true" />
          <div className="min-w-0 flex-1">
            <h2 className="text-body-md font-semibold text-warning-strong">需要您补充或确认</h2>
            <p className="mt-1 text-body-sm text-ink-secondary">
              {handoff?.nextStep || handoff?.progressNote || "请回到对话区查看需要补充的资料或确认事项。"}
            </p>
          </div>
          <Link
            href={`/projects/${projectId}`}
            className="inline-flex shrink-0 items-center justify-center rounded-lg bg-white px-3 py-2 text-label-md font-semibold text-warning-strong shadow-xs"
          >
            前往对话区处理
          </Link>
        </section>
      ) : null}

      <div className="grid grid-cols-1 items-start gap-5 xl:grid-cols-12">
        <div className="flex flex-col gap-5 xl:col-span-8">
          <section className="rounded-2xl border border-slate-soft/80 bg-white p-5 shadow-e1">
            <p className="text-label-sm font-semibold text-ink-faint">当前阶段</p>
            <h2 className="mt-2 text-headline-md font-bold text-ink-primary">
              {deliveryStatus?.stateName ?? "交付处理中"}
            </h2>
            <p className="mt-2 text-body-md text-ink-muted">{description}</p>
          </section>

          <div className="grid grid-cols-1 gap-5 md:grid-cols-2">
            <section className="rounded-2xl border border-slate-soft/80 bg-white p-5 shadow-e1">
              <h2 className="text-headline-sm font-bold text-ink-primary">最近更新</h2>
              <p className="mt-3 whitespace-pre-wrap text-body-md leading-7 text-ink-secondary">
                {handoff?.progressNote || "暂无新的进展更新。"}
              </p>
            </section>
            <section className="rounded-2xl border border-slate-soft/80 bg-white p-5 shadow-e1">
              <h2 className="text-headline-sm font-bold text-ink-primary">下一步事项</h2>
              <p className="mt-3 whitespace-pre-wrap text-body-md leading-7 text-ink-secondary">
                {handoff?.nextStep || "等待交付负责人更新下一步。"}
              </p>
            </section>
          </div>
        </div>

        <aside className="flex flex-col gap-5 xl:col-span-4" aria-label="交付联系与排期">
          <section className="rounded-2xl border border-slate-soft/80 bg-white p-5 shadow-e1">
            <div className="flex items-center gap-2">
              <UserRound className="size-4 text-electric-indigo" aria-hidden="true" />
              <h2 className="text-headline-sm font-bold text-ink-primary">交付联系人</h2>
            </div>
            <p className="mt-3 text-body-md text-ink-secondary">{handoff?.contact || "待安排"}</p>
            <div className="mt-4 flex items-start gap-2 border-t border-slate-soft/60 pt-4">
              <CalendarClock className="mt-0.5 size-4 shrink-0 text-ink-faint" aria-hidden="true" />
              <div>
                <p className="text-label-sm text-ink-faint">预计开始</p>
                <p className="mt-1 text-body-md text-ink-secondary">
                  {handoff?.estimatedStartAt ? formatRelativeTime(handoff.estimatedStartAt) : "待安排"}
                </p>
              </div>
            </div>
          </section>
        </aside>
      </div>

      <section className="mt-5 rounded-2xl border border-slate-soft/80 bg-white p-5 shadow-e1" aria-label="成果入口">
        <h2 className="text-headline-sm font-bold text-ink-primary">成果入口</h2>
        <div className="mt-4 grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
          <ArtifactLink href={`/projects/${projectId}`} label="PRD" description="查看需求文档" icon={<FileText />} />
          <ArtifactLink href={`/projects/${projectId}/demo`} label="Demo" description="体验已确认成果" icon={<MonitorPlay />} />
          <ArtifactLink href={`/projects/${projectId}/contract#contract`} label="合同" description="查看合同约定" icon={<FileSignature />} />
          <ArtifactLink href={`/projects/${projectId}/contract#payment`} label="付款记录" description="查看节点与凭证" icon={<ReceiptText />} />
        </div>
      </section>
    </div>
  );
}

function ArtifactLink({
  href,
  label,
  description,
  icon,
}: {
  href: string;
  label: string;
  description: string;
  icon: React.ReactNode;
}) {
  return (
    <Link
      href={href}
      className="group flex items-center gap-3 rounded-xl border border-slate-soft/80 bg-canvas-base p-4 transition-colors hover:border-electric-indigo/40 hover:bg-surface-violet-tint"
    >
      <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-white text-electric-indigo shadow-xs [&>svg]:size-4.5">
        {icon}
      </span>
      <span className="min-w-0">
        <span className="block text-body-md font-semibold text-ink-primary">{label}</span>
        <span className="mt-0.5 block text-label-sm text-ink-faint">{description}</span>
      </span>
      <ArrowUpRight className="ml-auto size-4 shrink-0 text-ink-faint transition-transform group-hover:-translate-y-0.5 group-hover:translate-x-0.5" aria-hidden="true" />
    </Link>
  );
}
