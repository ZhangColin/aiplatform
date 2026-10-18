"use client";

import {
  CircleCheck,
  CircleDollarSign,
  Copy,
  Hourglass,
  Landmark,
  Loader2,
  QrCode,
  Upload,
} from "lucide-react";
import { useRef, useState } from "react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { useUploadVoucher } from "@/hooks/use-payment";
import {
  nodeAcceptsVoucher,
  outstandingPaymentOf,
  PAYMENT_NODE_STATUS,
  voucherFileAcceptable,
  VOUCHER_UPLOAD_ACCEPT,
  type PaymentNode,
  type PaymentPlan,
} from "@/lib/orders/payment";
import { formatPrice } from "@/lib/orders/price";
import { cn } from "@/lib/utils";

/**
 * 子步三「开工款支付与核验」（#348，spec #320 ⑥ 照原型）：凭证核验路径——渠道
 * 枚举（企业公对公转账推荐／微信·支付宝企业扫码占位）＋附言码（真实节点
 * remarkCode，一键复制）＋凭证上传（四格式/10MB 预检，上传仅代表已提交）＋节点
 * 五态（待支付→处理中→失败或关闭→线下转账待核验→已到账）＋金额不足差额呈现
 * （未到账节点 remainingAmount 合计）。银行账号/开户行＝原型虚构演示数据照搬
 * （正式对公账户待业务方案确定）；v1 渠道只走线下转账（扫码占位不出入口）。
 */
export function PaymentCard({ plan }: { plan: PaymentPlan }) {
  const [method, setMethod] = useState<"bank" | "qr">("bank");
  const outstanding = outstandingPaymentOf(plan);
  const kickoffNode = plan.nodes.find((node) => node.kickoffGate) ?? plan.nodes[0];
  // 「当前应付总额」＝开工门节点（本次应付）还需支付额——非全节点合计（全节点差额归
  // 「还需支付」推导）。开工门节点足额到账后不显示残留金额。
  const kickoffSettled = kickoffNode != null && kickoffNode.status === PAYMENT_NODE_STATUS.received;
  const payable = kickoffNode ? (kickoffNode.remainingAmount ?? kickoffNode.amount) : undefined;

  return (
    <section
      aria-label="开工款支付与核验"
      className="flex flex-col gap-4 rounded-2xl border border-slate-soft/80 bg-white p-5 shadow-e1"
    >
      <div className="flex flex-col gap-2 border-b border-slate-soft/60 pb-3 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <div className="flex items-center gap-2.5">
            <div className="flex size-8 items-center justify-center rounded-lg bg-surface-mist text-tech-blue">
              <CircleDollarSign className="size-4.5" aria-hidden="true" />
            </div>
            <h2 className="text-headline-md font-bold text-ink-primary">
              首期开工款结算节点
            </h2>
          </div>
          <p className="mt-1 text-body-sm text-ink-muted">
            清算系统将根据专属子账号与附言自动完成银企互联核账
          </p>
        </div>
        <div className="flex flex-col rounded-xl border border-subtle-blue bg-linear-to-br from-surface-mist to-surface-violet-tint/40 px-4 py-2">
          <span className="text-label-sm text-ink-muted">当前应付总额</span>
          {kickoffSettled ? (
            <span className="text-headline-sm font-bold text-success-deep">已到账</span>
          ) : (
            <span className="font-mono text-headline-xl font-extrabold tracking-tight text-electric-indigo">
              {formatPrice(payable) ?? "—"}
            </span>
          )}
        </div>
      </div>

      {/* 渠道枚举（照原型双 tab；v1 扫码占位） */}
      <div className="flex max-w-md items-center rounded-xl border border-slate-soft/80 bg-surface-dawn p-1">
        <MethodTab active={method === "bank"} onClick={() => setMethod("bank")} icon={<Landmark className="size-4" aria-hidden="true" />}>
          企业公对公转账 (推荐)
        </MethodTab>
        <MethodTab active={method === "qr"} onClick={() => setMethod("qr")} icon={<QrCode className="size-4" aria-hidden="true" />}>
          微信 / 支付宝企业扫码
        </MethodTab>
      </div>

      {method === "bank" ? (
        <BankPanel remarkCode={kickoffNode?.remarkCode} />
      ) : (
        <QrPanel />
      )}

      {plan.nodes.length === 0 ? (
        <p className="text-body-sm text-ink-faint">付款节点待后台录入，录入后可按节点付款。</p>
      ) : (
        <>
          <OutstandingNote outstanding={outstanding} nodeCount={plan.nodes.length} />
          <ul className="flex flex-col gap-3">
            {plan.nodes.map((node) => (
              <PaymentNodeRow key={node.id} node={node} orderId={plan.orderId ?? ""} />
            ))}
          </ul>
          <p className="text-label-sm text-ink-faint">
            线下转账请备注附言码，转账后上传凭证；到账由后台核验，上传不代表已到账。
          </p>
        </>
      )}
    </section>
  );
}

function MethodTab({
  active,
  onClick,
  icon,
  children,
}: {
  active: boolean;
  onClick: () => void;
  icon: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        "flex flex-1 items-center justify-center gap-1.5 rounded-lg px-3 py-2 text-label-md transition-all",
        active
          ? "bg-tech-blue font-semibold text-white shadow-xs"
          : "font-medium text-ink-muted hover:text-ink-primary",
      )}
    >
      {icon}
      {children}
    </button>
  );
}

/** 银行转账面板（照原型）：开户全称/开户银行/子账号为虚构演示数据照搬，附言码取真实节点。 */
function BankPanel({ remarkCode }: { remarkCode?: string }) {
  return (
    <div className="flex flex-col gap-3 rounded-xl border border-subtle-blue bg-linear-to-br from-surface-mist via-canvas-base to-emerald-active/5 p-4">
      <div className="flex items-center justify-between">
        <span className="text-label-sm font-bold tracking-wide text-tech-blue uppercase">
          专属企业虚拟结算账户 (自动核账匹配)
        </span>
        <span className="inline-flex items-center gap-1 rounded-full border border-emerald-active/30 bg-emerald-active/10 px-2.5 py-0.5 text-label-sm font-semibold text-success-deep">
          <span className="size-1.5 animate-pulse rounded-full bg-emerald-active" aria-hidden="true" />
          银企直连即时监听
        </span>
      </div>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <AccountField label="开户全称" value="北京语义引力数字交付科技有限公司" />
        <AccountField label="开户银行" value="招商银行北京分行大运村支行" />
        <div className="flex flex-col gap-1.5 rounded-xl border border-subtle-blue bg-white p-3.5 sm:col-span-2">
          <div className="flex items-center justify-between">
            <span className="text-label-sm text-ink-muted">专属银行子账号</span>
            <CopyButton text="8102 9482 9103 8102 94" label="复制账号" />
          </div>
          <span className="font-mono text-headline-sm font-bold tracking-wider text-tech-blue select-all">
            8102 9482 9103 8102 94
          </span>
        </div>
        <div className="flex flex-col gap-1.5 rounded-xl border border-subtle-blue bg-white p-3.5 sm:col-span-2">
          <div className="flex items-center justify-between">
            <span className="text-label-sm text-ink-muted">转账汇款附言 (必填)</span>
            {remarkCode ? <CopyButton text={remarkCode} label="复制附言" /> : null}
          </div>
          {remarkCode ? (
            <div className="flex flex-col justify-between gap-1 sm:flex-row sm:items-center">
              <span className="font-mono text-headline-sm font-bold tracking-wide text-ink-primary select-all">
                {remarkCode}
              </span>
              <span className="inline-block rounded border border-emerald-active/25 bg-emerald-active/10 px-2 py-0.5 text-label-sm text-success-deep">
                系统将在入账后 10 秒内自动触发核对
              </span>
            </div>
          ) : (
            <span className="text-body-sm text-ink-faint">附言码待后台录入付款节点后生成。</span>
          )}
        </div>
      </div>
    </div>
  );
}

function AccountField({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex flex-col gap-1 rounded-lg border border-subtle-blue/70 bg-white/80 p-2.5">
      <span className="text-label-sm text-ink-faint">{label}</span>
      <span className="text-body-md font-bold text-ink-primary select-all">{value}</span>
    </div>
  );
}

/** 扫码面板（照原型静态占位——v1 渠道真接后启用）。 */
function QrPanel() {
  return (
    <div className="flex flex-col items-center gap-4 rounded-xl border border-subtle-blue bg-linear-to-br from-surface-mist to-surface-dawn p-4 md:flex-row">
      <div className="flex size-44 shrink-0 items-center justify-center rounded-2xl border border-subtle-blue bg-white p-3 shadow-sm">
        <QrCode className="size-full text-ink-secondary" aria-hidden="true" />
      </div>
      <div className="flex flex-col gap-2">
        <span className="text-headline-sm font-bold text-ink-primary">企业级聚合网关</span>
        <p className="text-body-sm text-ink-muted">
          支持微信支付企业版、支付宝企业账户及主流手机银行银联扫码。单笔限额视付款行限额而定。
        </p>
        <div className="flex items-center gap-2">
          <span className="rounded border border-slate-soft bg-white px-2.5 py-1 font-mono text-label-sm text-ink-muted">
            交易附言已内嵌
          </span>
          <span className="rounded border border-subtle-blue bg-surface-mist px-2 py-0.5 font-mono text-code-sm font-semibold text-tech-blue">
            扫码即时承兑
          </span>
        </div>
        <p className="text-label-sm text-ink-faint">扫码渠道尚未开放，请使用企业公对公转账。</p>
      </div>
    </div>
  );
}

/** 差额呈现（#337）：未到账节点还需支付合计＋对应节点清单；全部到账转「已到账」。 */
function OutstandingNote({
  outstanding,
  nodeCount,
}: {
  outstanding: { total: number; nodes: PaymentNode[] } | null;
  nodeCount: number;
}) {
  if (outstanding == null) {
    return (
      <p className="flex items-center gap-1.5 text-body-sm text-ink-muted">
        <CircleCheck className="size-4 text-emerald-active" aria-hidden="true" /> 款项已全部到账
      </p>
    );
  }
  return (
    <p className="rounded-md border border-amber-400/40 bg-warning/10 px-3 py-2 text-body-sm text-warning-strong">
      还需支付 {formatPrice(outstanding.total) ?? "—"}
      {outstanding.nodes.length < nodeCount
        ? `（${outstanding.nodes.map((node) => node.name || `节点 ${node.seq ?? ""}`).join("、")}）`
        : ""}
      ，按节点金额补齐后由后台核验到账
    </p>
  );
}

function CopyButton({ text, label }: { text: string; label: string }) {
  const onCopy = async () => {
    try {
      await navigator.clipboard.writeText(text.replace(/\s/g, ""));
      toast.success(`${label}已复制`);
    } catch {
      toast.error("复制失败，请手动复制");
    }
  };
  return (
    <button
      type="button"
      onClick={onCopy}
      className="inline-flex items-center gap-1 rounded-md border border-subtle-blue bg-surface-mist px-2.5 py-1 text-label-sm font-semibold text-tech-blue transition-colors hover:bg-subtle-blue/50"
    >
      <Copy className="size-3.5" aria-hidden="true" />
      {label}
    </button>
  );
}

/** 节点行：五态＋附言码＋凭证清单＋上传入口＋差额补齐提示（#337 口径迁移）。 */
function PaymentNodeRow({ node, orderId }: { node: PaymentNode; orderId: string }) {
  const upload = useUploadVoucher(orderId);
  const inputRef = useRef<HTMLInputElement>(null);

  const onFileChange = (file: File | undefined) => {
    if (!file) return;
    const rejection = voucherFileAcceptable(file);
    if (rejection) {
      toast.error(rejection);
      return;
    }
    upload.mutate(
      { nodeId: node.id, file },
      { onSuccess: () => toast.success("凭证已提交，等待后台核验到账") },
    );
  };

  return (
    <li className="space-y-2 rounded-xl border border-slate-soft/70 p-3">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="flex items-center gap-1.5 font-medium text-ink-primary">
            <span>{node.seq ? `${node.seq}. ` : ""}{node.name ?? "付款节点"}</span>
            {node.kickoffGate ? (
              <span className="rounded-full bg-surface-dawn px-2 py-0.5 text-label-sm font-normal text-ink-muted">开工款</span>
            ) : null}
          </p>
          {node.remarkCode ? (
            <p className="mt-0.5 flex items-center gap-2 text-label-sm text-ink-faint">
              附言码 <code className="rounded bg-surface-dawn px-1.5 py-0.5 font-mono">{node.remarkCode}</code>
              {node.channelRefNo ? <span>流水号 {node.channelRefNo}</span> : null}
            </p>
          ) : null}
        </div>
        <div className="shrink-0 text-right">
          <p className="font-medium tabular-nums text-ink-primary">
            {formatPrice(node.amount) ?? "—"}
            {node.percentage != null ? (
              <span className="ml-1 text-label-sm font-normal text-ink-faint">（{node.percentage}%）</span>
            ) : null}
          </p>
          <NodeStatusBadge status={node.status} statusName={node.statusName} />
        </div>
      </div>

      {node.status === PAYMENT_NODE_STATUS.pending && (node.settledAmount ?? 0) > 0 ? (
        <p className="text-label-sm text-warning-strong">
          已核到账 {formatPrice(node.settledAmount) ?? "—"}，还需支付 {formatPrice(node.remainingAmount) ?? "—"}（补齐后重新上传凭证）
        </p>
      ) : null}

      {node.vouchers.length > 0 ? (
        <ul className="space-y-1">
          {node.vouchers.map((voucher) => (
            <li key={voucher.id} className="flex items-center justify-between gap-3 text-label-sm text-ink-muted">
              <span className="inline-flex min-w-0 items-center gap-1.5">
                <Landmark className="size-3.5 shrink-0" aria-hidden="true" />
                <span className="truncate">{voucher.fileName ?? "转账凭证"}</span>
              </span>
            </li>
          ))}
        </ul>
      ) : null}

      {nodeAcceptsVoucher(node) ? (
        <>
          <input
            ref={inputRef}
            type="file"
            accept={VOUCHER_UPLOAD_ACCEPT}
            className="hidden"
            onChange={(event) => {
              onFileChange(event.target.files?.[0]);
              event.target.value = "";
            }}
          />
          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              disabled={upload.isPending}
              onClick={() => inputRef.current?.click()}
            >
              {upload.isPending ? (
                <Loader2 className="size-3.5 animate-spin" aria-hidden="true" />
              ) : (
                <Upload className="size-3.5" aria-hidden="true" />
              )}
              {node.vouchers.length > 0 ? "再传一张凭证" : "上传转账凭证"}
            </Button>
            {node.status === PAYMENT_NODE_STATUS.awaitingVerification ? (
              <span className="text-label-sm text-ink-faint">凭证核验中，请耐心等待</span>
            ) : null}
          </div>
        </>
      ) : null}
    </li>
  );
}

/** 节点五态回退名（statusName 缺省时按 code 推导，不把处理中/失败误标「待支付」）。 */
const NODE_STATUS_FALLBACK: Record<number, string> = {
  [PAYMENT_NODE_STATUS.pending]: "待支付",
  [PAYMENT_NODE_STATUS.processing]: "支付处理中",
  [PAYMENT_NODE_STATUS.failedOrClosed]: "支付失败或关闭",
  [PAYMENT_NODE_STATUS.awaitingVerification]: "线下转账待核验",
  [PAYMENT_NODE_STATUS.received]: "已到账",
};

/** 节点五态徽标：已到账绿、待核验黄、处理中脉冲、其余中性。 */
function NodeStatusBadge({ status, statusName }: { status?: number; statusName?: string }) {
  const text = statusName ?? (status != null ? NODE_STATUS_FALLBACK[status] : undefined) ?? "待支付";
  if (status === PAYMENT_NODE_STATUS.received) {
    return (
      <span className="mt-0.5 inline-flex items-center gap-1 text-label-sm text-success-deep">
        <CircleCheck className="size-3.5" aria-hidden="true" />
        {text}
      </span>
    );
  }
  if (status === PAYMENT_NODE_STATUS.awaitingVerification) {
    return (
      <span className="mt-0.5 inline-flex items-center gap-1 text-label-sm text-warning-strong">
        <Hourglass className="size-3.5" aria-hidden="true" />
        {text}
      </span>
    );
  }
  if (status === PAYMENT_NODE_STATUS.processing) {
    return (
      <span className="mt-0.5 inline-flex items-center gap-1 text-label-sm text-tech-blue">
        <Loader2 className="size-3.5 animate-spin" aria-hidden="true" />
        {text}
      </span>
    );
  }
  return <span className="mt-0.5 block text-label-sm text-ink-faint">{text}</span>;
}
