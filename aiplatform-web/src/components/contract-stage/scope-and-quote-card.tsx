"use client";

import { Bookmark, CircleCheck, Lock, RotateCcw } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import type { ContractDetail } from "@/lib/orders/contract";
import type { OrderDetail } from "@/lib/orders/detail";
import { ORDER_STATUS } from "@/lib/orders/lock";
import type { PaymentPlan } from "@/lib/orders/payment";
import { formatPrice } from "@/lib/orders/price";
import { useVersionRecordStore } from "@/lib/store/version-record";

/**
 * 子步一「核对范围与报价」（#348，spec #320 ⑥ 照原型）：现订单面板/报价卡内容
 * 重组迁入——三态分支（都以真实订单/合同/付款事实驱动，不虚构演示金额）：
 *
 * <ul>
 * <li>待报价：{@code 「待确认报价」}占位态（价格位以待定态占位，不以 0 元或示例价
 * 代替，#335 口径）；</li>
 * <li>已报价未冻结：总价＋后台备注＋范围文本三段（交付清单/验收方式/工期口径）
 * ＋「确认范围并准备合同」（冻结范围快照＋创建合同，#336）＋「取消订单」（回到
 * 迭代继续沟通——「申请调整」无后端改价申请接口，落地为现有取消路径）；</li>
 * <li>合同进场（范围已冻结）：交付成果清单＋财务阶段结算计划（真实付款节点）＋
 * PRD/Demo 快照版本引用（点开版本记录抽屉可回看）。</li>
 * </ul>
 */
export function ScopeAndQuoteCard({
  projectName,
  order,
  contract,
  paymentPlan,
  confirmPending,
  onConfirmScope,
  onCancelOrder,
  cancelPending,
}: {
  projectName: string;
  order?: OrderDetail | null;
  contract?: ContractDetail | null;
  paymentPlan?: PaymentPlan | null;
  confirmPending: boolean;
  onConfirmScope: () => void;
  onCancelOrder: () => void;
  cancelPending: boolean;
}) {
  const openVersionRecord = useVersionRecordStore((s) => s.openVersionRecord);

  if (!order) {
    return (
      <section aria-label="核对范围与报价" className="rounded-2xl border border-slate-soft/80 bg-white p-5 shadow-e1">
        <CardHeading icon={<Lock />} title="交付范围与合同冻结快照" />
        <Skeleton className="mt-4 h-20 w-full" />
      </section>
    );
  }

  const waitingQuote = order.status === ORDER_STATUS.pendingQuote;
  const frozen = contract != null;

  const prdVersionNo = contract?.prdVersionNo ?? order.prdVersionNo;
  const demoVersionNo = contract?.demoVersionNo ?? order.demoVersionNo;

  return (
    <section
      aria-label="核对范围与报价"
      className="flex flex-col gap-4 rounded-2xl border border-slate-soft/80 bg-white p-5 shadow-e1"
    >
      <div className="flex items-center justify-between border-b border-slate-soft/60 pb-3">
        <CardHeading icon={<Lock />} title="交付范围与合同冻结快照" />
        <StatePill frozen={frozen} waitingQuote={waitingQuote} />
      </div>

      <ProjectIdentity
        projectName={projectName}
        orderId={order.id}
        contractNo={contract?.contractNo}
      />

      {/* PRD/Demo 快照版本引用（照原型；点开版本记录抽屉可回看） */}
      {prdVersionNo != null || demoVersionNo != null ? (
        <div className="flex flex-wrap items-center gap-2 text-label-md">
          {prdVersionNo != null ? (
            <VersionRef onClick={openVersionRecord}>PRD v{prdVersionNo} 不可变需求快照</VersionRef>
          ) : null}
          {prdVersionNo != null && demoVersionNo != null ? (
            <span className="text-ink-faint/50">/</span>
          ) : null}
          {demoVersionNo != null ? (
            <VersionRef onClick={openVersionRecord}>Demo v{demoVersionNo} 终审交互沙盒</VersionRef>
          ) : null}
        </div>
      ) : null}

      {waitingQuote ? (
        <PendingQuoteState />
      ) : frozen ? (
        <FrozenSnapshot contract={contract} paymentPlan={paymentPlan} />
      ) : (
        <QuotedScopeState
          order={order}
          confirmPending={confirmPending}
          onConfirmScope={onConfirmScope}
          onCancelOrder={onCancelOrder}
          cancelPending={cancelPending}
        />
      )}
    </section>
  );
}

function CardHeading({ icon, title }: { icon: React.ReactNode; title: string }) {
  return (
    <div className="flex items-center gap-2.5">
      <div className="flex size-8 items-center justify-center rounded-lg bg-surface-mist text-tech-blue">
        {icon}
      </div>
      <h2 className="text-headline-sm font-bold text-ink-primary">{title}</h2>
    </div>
  );
}

/** 右上状态胶囊：已冻结＝「已定版不可逆」、待报价＝「待确认报价」、已报价＝「待确认范围」。 */
function StatePill({ frozen, waitingQuote }: { frozen: boolean; waitingQuote: boolean }) {
  const text = frozen ? "已定版不可逆" : waitingQuote ? "待确认报价" : "待确认范围";
  return (
    <span
      className={
        frozen
          ? "inline-flex items-center gap-1.5 rounded-full border border-slate-soft/70 bg-surface-dawn px-2.5 py-1 text-label-sm font-medium text-ink-muted"
          : "inline-flex items-center gap-1.5 rounded-full border border-amber-400/40 bg-warning/10 px-2.5 py-1 text-label-sm font-medium text-warning-strong"
      }
    >
      <span className={frozen ? "size-1.5 rounded-full bg-ink-faint" : "size-1.5 rounded-full bg-warning"} aria-hidden="true" />
      {text}
    </span>
  );
}

/** 项目身份 meta：项目名（真实）＋订单/合同编号（真实标识，替代原型虚构 SG-20261015-089）。 */
function ProjectIdentity({
  projectName,
  orderId,
  contractNo,
}: {
  projectName: string;
  orderId: string;
  contractNo?: string;
}) {
  return (
    <div className="flex flex-col gap-2 rounded-xl border border-slate-soft/70 bg-linear-to-br from-canvas-base to-surface-dawn p-4">
      <div className="flex items-baseline justify-between gap-3">
        <span className="text-label-sm text-ink-faint">项目名称</span>
        <span className="font-headline-sm font-bold text-ink-primary">{projectName || "未命名项目"}</span>
      </div>
      <div className="flex items-baseline justify-between gap-3 border-t border-slate-soft/60 pt-2">
        <span className="text-label-sm text-ink-faint">订单编号</span>
        <span className="font-mono text-code-sm text-tech-blue">{orderId}</span>
      </div>
      {contractNo ? (
        <div className="flex items-baseline justify-between gap-3 border-t border-slate-soft/60 pt-2">
          <span className="text-label-sm text-ink-faint">合同编号</span>
          <span className="font-mono text-code-sm text-tech-blue">{contractNo}</span>
        </div>
      ) : null}
    </div>
  );
}

/** PRD/Demo 快照版本引用（点开版本记录抽屉回看）。 */
function VersionRef({ children, onClick }: { children: React.ReactNode; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="inline-flex items-center gap-1.5 font-medium text-tech-blue transition-colors hover:text-electric-indigo hover:underline"
    >
      {children}
      <Bookmark className="size-3.5" aria-hidden="true" />
    </button>
  );
}

/** 「待确认报价」占位态（#335）：价格位以待定态占位，不虚构金额。 */
function PendingQuoteState() {
  return (
    <div className="space-y-2">
      <p className="text-2xl font-semibold text-ink-primary">待确认报价</p>
      <p className="text-body-sm text-ink-muted">
        已收到您的订单，后台正在评估范围与报价；报价确认后会在这里呈现价格、范围文本与结算计划。
      </p>
    </div>
  );
}

/** 已报价未冻结：总价＋备注＋范围文本三段＋确认范围/取消订单。 */
function QuotedScopeState({
  order,
  confirmPending,
  onConfirmScope,
  onCancelOrder,
  cancelPending,
}: {
  order: OrderDetail;
  confirmPending: boolean;
  onConfirmScope: () => void;
  onCancelOrder: () => void;
  cancelPending: boolean;
}) {
  const price = formatPrice(order.amount, order.currency);
  return (
    <div className="flex flex-col gap-3">
      <div className="space-y-1">
        <p className="text-2xl font-semibold tabular-nums text-ink-primary">{price ?? "价格待定"}</p>
        {order.note ? <p className="text-body-sm text-ink-muted">后台说明：{order.note}</p> : null}
      </div>

      {order.scopeDeliverables || order.scopeAcceptance || order.scopeTimeline ? (
        <div className="flex flex-col gap-2 rounded-xl border border-slate-soft/70 bg-surface-dawn/40 p-3">
          <p className="text-label-sm font-medium text-ink-faint">范围与报价</p>
          <ScopeRow label="交付清单" value={order.scopeDeliverables} />
          <ScopeRow label="验收方式" value={order.scopeAcceptance} />
          <ScopeRow label="工期口径" value={order.scopeTimeline} />
        </div>
      ) : null}

      <div className="flex flex-wrap items-center gap-2 pt-1">
        <Button
          size="sm"
          disabled={confirmPending || !order.scopeDeliverables}
          onClick={onConfirmScope}
        >
          {confirmPending ? "正在准备合同…" : "确认范围并准备合同"}
        </Button>
        <Button
          variant="outline"
          size="sm"
          disabled={cancelPending}
          onClick={() => {
            if (window.confirm("取消订单后回到迭代，可继续修改再重新下单；确定取消？")) {
              onCancelOrder();
            }
          }}
        >
          <RotateCcw className="size-3.5" aria-hidden="true" />
          {cancelPending ? "取消中…" : "取消订单"}
        </Button>
      </div>
      <p className="text-label-sm text-ink-faint">
        确认后冻结范围与报价版本，平台开始准备合同；对范围或价格有意见可取消订单回到迭代继续沟通。
      </p>
    </div>
  );
}

/** 冻结快照（合同进场）：交付成果清单（冻结副本）＋财务阶段结算计划（真实节点）。 */
function FrozenSnapshot({
  contract,
  paymentPlan,
}: {
  contract: ContractDetail;
  paymentPlan?: PaymentPlan | null;
}) {
  const total = paymentPlan?.totalAmount ?? contract.amount;
  const nodes = paymentPlan?.nodes ?? [];
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-2">
        <span className="text-label-sm font-semibold tracking-wider text-ink-faint uppercase">
          交付成果清单 (DELIVERABLES)
        </span>
        <div className="flex items-start gap-2.5 rounded-lg border border-slate-soft/70 bg-surface-dawn/50 p-3">
          <CircleCheck className="mt-0.5 size-4.5 shrink-0 text-emerald-active" aria-hidden="true" />
          <span className="text-body-sm leading-snug text-ink-secondary">
            {contract.scopeDeliverables?.trim() || "待录入"}
          </span>
        </div>
      </div>

      <div className="flex flex-col gap-2">
        <div className="flex items-center justify-between">
          <span className="text-label-sm font-semibold tracking-wider text-ink-faint uppercase">
            财务阶段结算计划
          </span>
          <span className="font-mono text-code-sm text-ink-muted">固定总价包干制</span>
        </div>
        <div className="flex flex-col gap-2 rounded-xl border border-slate-soft/70 bg-linear-to-b from-surface-dawn to-canvas-base p-4">
          <div className="flex items-center justify-between border-b border-slate-soft/70 pb-2">
            <span className="text-body-md font-medium text-ink-secondary">项目总金额</span>
            <span className="text-headline-md font-bold tabular-nums text-ink-primary">
              {formatPrice(total, contract.currency) ?? "—"}
            </span>
          </div>
          {nodes.length > 0 ? (
            <ul className="flex flex-col gap-2">
              {nodes.map((node) => (
                <li
                  key={node.id}
                  className={
                    node.kickoffGate
                      ? "flex items-center justify-between rounded-lg border border-tech-blue/25 bg-white p-2.5 shadow-xs"
                      : "flex items-center justify-between rounded-lg border border-slate-soft/50 bg-white/60 p-2.5"
                  }
                >
                  <div className="flex items-center gap-2">
                    <span className={node.kickoffGate ? "size-2.5 rounded-full bg-tech-blue" : "size-2.5 rounded-full bg-ink-faint/50"} aria-hidden="true" />
                    <span className={node.kickoffGate ? "text-body-sm font-bold text-ink-primary" : "text-body-sm text-ink-muted"}>
                      {node.name ?? `节点 ${node.seq ?? ""}`}
                      {node.percentage != null ? ` (${node.percentage}%)` : ""}
                    </span>
                    {node.kickoffGate ? (
                      <span className="rounded bg-tech-blue px-2 py-0.5 text-label-sm font-semibold text-white">本次应付</span>
                    ) : null}
                  </div>
                  <span className={node.kickoffGate ? "font-mono text-code-md font-bold text-tech-blue" : "font-mono text-code-md text-ink-muted"}>
                    {formatPrice(node.amount) ?? "—"}
                  </span>
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-body-sm text-ink-faint">付款节点待后台录入，录入后按节点付款。</p>
          )}
        </div>
      </div>
    </div>
  );
}

function ScopeRow({ label, value }: { label: string; value?: string | null }) {
  return (
    <div className="flex items-baseline justify-between gap-4">
      <span className="shrink-0 text-label-sm text-ink-faint">{label}</span>
      <span className="min-w-0 text-right break-words text-body-sm text-ink-secondary">
        {value?.trim() || "—"}
      </span>
    </div>
  );
}
