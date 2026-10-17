"use client";

import {
  CircleCheck,
  CircleDollarSign,
  Copy,
  Hourglass,
  Landmark,
  Loader2,
  ShieldCheck,
  Upload,
} from "lucide-react";
import { useRef } from "react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

import { useUploadVoucher } from "@/hooks/use-payment";
import { formatPrice } from "@/lib/orders/price";
import { formatRelativeTime } from "@/lib/utils/time";
import {
  nodeAcceptsVoucher,
  outstandingPaymentOf,
  PAYMENT_NODE_STATUS,
  voucherFileAcceptable,
  VOUCHER_UPLOAD_ACCEPT,
  type PaymentNode,
  type PaymentPlan,
} from "@/lib/orders/payment";

/**
 * 付款面板（#337 付款计划与凭证核验·最小呈现，挂订单面）：节点清单（序号/
 * 名称/金额＋占比/五态/附言码＋复制/开工门标志）＋「还需支付」推导（不足留痕
 * 后的差额与对应节点）＋转账凭证上传（凭证核验路径第一跳——上传仅代表已
 * 提交，核验结论在后台留痕行）＋本人凭证清单（追加保留）。v1 渠道只走线下
 * 转账（扫码占位不出入口）；mock 支付弹窗已退役——本面板即支付的唯一用户面。
 * 完整三子步骤付款面重建归 #348。
 */
export function PaymentPanel({ plan }: { plan: PaymentPlan }) {
  const outstanding = outstandingPaymentOf(plan);

  return (
    <Card className="mt-4 gap-4 py-6" data-slot="payment-panel">
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base">
          <CircleDollarSign className="size-4" />
          付款计划
          {plan.contractNo ? (
            <span className="text-xs font-normal text-muted-foreground">
              合同 {plan.contractNo}
            </span>
          ) : null}
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-4 text-sm">
        {plan.nodes.length === 0 ? (
          <p className="text-muted-foreground">付款节点待后台录入，录入后可按节点付款。</p>
        ) : (
          <>
            <p className="flex items-baseline justify-between gap-4">
              <span className="text-xs text-muted-foreground">计划总额</span>
              <span className="font-semibold tabular-nums">
                {formatPrice(plan.totalAmount) ?? "—"}
                <span className="ml-2 text-xs font-normal text-muted-foreground">
                  已到账 {formatPrice(plan.totalSettled) ?? "—"}
                </span>
              </span>
            </p>
            {outstanding ? (
              <p className="rounded-md border border-amber-500/40 bg-amber-500/10 px-3 py-2 text-xs text-amber-600 dark:text-amber-400">
                还需支付 {formatPrice(outstanding.total) ?? "—"}
                {outstanding.nodes.length < plan.nodes.length
                  ? `（${
                      outstanding.nodes
                        .map((node) => node.name || `节点 ${node.seq ?? ""}`)
                        .join("、")
                    }）`
                  : ""}
                ，按节点金额补齐后由后台核验到账
              </p>
            ) : (
              <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
                <CircleCheck className="size-3.5" /> 款项已全部到账
              </p>
            )}
            <ul className="space-y-3">
              {plan.nodes.map((node) => (
                <PaymentNodeRow key={node.id} node={node} orderId={plan.orderId ?? ""} />
              ))}
            </ul>
            <p className="text-xs text-muted-foreground/70">
              线下转账请备注附言码，转账后上传凭证；到账由后台核验，上传不代表已到账。
            </p>
          </>
        )}
      </CardContent>
    </Card>
  );
}

/** 节点行：金额/占比/五态/开工门/附言码＋凭证清单与上传入口。 */
function PaymentNodeRow({ node, orderId }: { node: PaymentNode; orderId: string }) {
  const upload = useUploadVoucher(orderId);
  const inputRef = useRef<HTMLInputElement>(null);

  const onPickFile = () => inputRef.current?.click();

  const onFileChange = (file: File | undefined) => {
    if (!file) return;
    // 前端预检省注定 400 的上传（判定以后端为准）；同文件可重选（value 复位）
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
    <li className="space-y-2 rounded-md border p-3">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="font-medium">
            {node.seq ? `${node.seq}. ` : undefined}
            {node.name ?? "付款节点"}
            {node.kickoffGate ? (
              <span className="ml-2 inline-flex items-center gap-1 rounded-full bg-muted px-2 py-0.5 text-[11px] font-normal text-muted-foreground">
                <ShieldCheck className="size-3" /> 开工款
              </span>
            ) : null}
          </p>
          {node.remarkCode ? (
            <p className="mt-0.5 flex items-center gap-2 text-xs text-muted-foreground">
              <RemarkCode code={node.remarkCode} />
              {node.channelRefNo ? <span>流水号 {node.channelRefNo}</span> : null}
            </p>
          ) : null}
        </div>
        <div className="shrink-0 text-right">
          <p className="font-medium tabular-nums">
            {formatPrice(node.amount) ?? "—"}
            {node.percentage != null ? (
              <span className="ml-1 text-xs font-normal text-muted-foreground">
                （{node.percentage}%）
              </span>
            ) : null}
          </p>
          <NodeStatusBadge status={node.status} statusName={node.statusName} />
        </div>
      </div>
      {node.status === PAYMENT_NODE_STATUS.pending && (node.settledAmount ?? 0) > 0 ? (
        <p className="text-xs text-amber-600 dark:text-amber-400">
          已核到账 {formatPrice(node.settledAmount) ?? "—"}，还需支付{" "}
          {formatPrice(node.remainingAmount) ?? "—"}（补齐后重新上传凭证）
        </p>
      ) : null}
      {node.vouchers.length > 0 ? (
        <ul className="space-y-1">
          {node.vouchers.map((voucher) => (
            <li
              key={voucher.id}
              className="flex items-center justify-between gap-3 text-xs text-muted-foreground"
            >
              <span className="inline-flex min-w-0 items-center gap-1.5">
                <Landmark className="size-3 shrink-0" />
                <span className="truncate">{voucher.fileName ?? "转账凭证"}</span>
              </span>
              <span className="shrink-0 text-muted-foreground/70">
                {formatRelativeTime(voucher.createdAt) || ""}
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
              event.target.value = ""; // 同文件可重选（失败重试）
            }}
          />
          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              disabled={upload.isPending}
              onClick={onPickFile}
            >
              {upload.isPending ? (
                <Loader2 className="size-3.5 animate-spin" />
              ) : (
                <Upload className="size-3.5" />
              )}
              {node.vouchers.length > 0 ? "再传一张凭证" : "上传转账凭证"}
            </Button>
            {node.status === PAYMENT_NODE_STATUS.awaitingVerification ? (
              <span className="text-xs text-muted-foreground">凭证核验中，请耐心等待</span>
            ) : null}
          </div>
        </>
      ) : null}
    </li>
  );
}

/** 附言码（转账回执关联位）：一键复制（线下转账必填备注）。 */
function RemarkCode({ code }: { code: string }) {
  const onCopy = async () => {
    try {
      await navigator.clipboard.writeText(code);
      toast.success("附言码已复制，转账时请填写在备注中");
    } catch {
      toast.error("复制失败，请手动复制");
    }
  };
  return (
    <button
      type="button"
      onClick={onCopy}
      className="inline-flex items-center gap-1 rounded bg-muted px-1.5 py-0.5 font-mono text-[11px] transition-colors hover:bg-muted/70"
      title="复制附言码（转账备注必填）"
    >
      <span>{code}</span>
      <Copy className="size-3" />
    </button>
  );
}

/** 节点五态徽标：已到账绿、待核验黄、其余中性。 */
function NodeStatusBadge({ status, statusName }: { status?: number; statusName?: string }) {
  const text = statusName ?? "待支付";
  if (status === PAYMENT_NODE_STATUS.received) {
    return (
      <span className="mt-0.5 inline-flex items-center gap-1 text-xs text-emerald-600 dark:text-emerald-400">
        <CircleCheck className="size-3.5" />
        {text}
      </span>
    );
  }
  if (status === PAYMENT_NODE_STATUS.awaitingVerification) {
    return (
      <span className="mt-0.5 inline-flex items-center gap-1 text-xs text-amber-600 dark:text-amber-400">
        <Hourglass className="size-3.5" />
        {text}
      </span>
    );
  }
  return <span className="mt-0.5 block text-xs text-muted-foreground">{text}</span>;
}
