"use client";

import { CircleCheck, FileSignature, Hourglass, RefreshCw } from "lucide-react";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { CONTRACT_TERMINAL_CODES, type ContractDetail } from "@/lib/orders/contract";
import { formatPrice } from "@/lib/orders/price";

/**
 * 合同预览卡（#336 合同域六态·最小呈现，挂订单面）：预览字段集结构化呈现
 * （双方主体/项目范围/交付物/验收标准/费用/付款计划/交期约定/合同版本——
 * 交付清单、验收方式、工期口径随范围快照冻结面，费用随冻结金额）＋六态
 * ＋双方签署位＋「需更新」标志（范围或报价变动后待后台重录重新冻结）。
 * 全部状态推进归后台人工（六态在这里只呈现不操作）；签署完成后呈现定格
 * （终态合同永不删除）。完整三子步骤签约面重建归 #348，本卡承最小呈现。
 */
export function ContractCard({ contract }: { contract: ContractDetail }) {
  const terminal = contract.status != null && CONTRACT_TERMINAL_CODES.includes(contract.status);

  return (
    <Card className="mt-4 gap-4 py-6">
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base">
          <FileSignature className="size-4" />
          合同 {contract.contractNo ?? ""}
          <span className="text-xs font-normal text-muted-foreground">
            版本 v{contract.version ?? 1}
          </span>
          {terminal ? null : <Hourglass className="size-4 text-muted-foreground" />}
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-4 text-sm">
        <div className="flex items-center justify-between gap-4">
          <p className="font-medium">{contract.statusName ?? "合同处理中"}</p>
          <p className="flex items-center gap-3 text-xs text-muted-foreground">
            <span>甲方 {contract.partyASigned ? "已签" : "未签"}</span>
            <span>乙方 {contract.partyBSigned ? "已签" : "未签"}</span>
          </p>
        </div>
        {contract.needsUpdate ? (
          <p className="flex items-center gap-1.5 rounded-md border border-amber-500/40 bg-amber-500/10 px-3 py-2 text-xs text-amber-600 dark:text-amber-400">
            <RefreshCw className="size-3.5" />
            范围或报价有更新，正在重新准备合同（更新后请重新核对）
          </p>
        ) : null}
        <dl className="space-y-2">
          <PreviewRow label="甲方主体" value={contract.partyA} />
          <PreviewRow label="乙方主体" value={contract.partyB} />
          <PreviewRow label="项目范围" value={contract.projectScope} />
          <PreviewRow label="交付物" value={contract.scopeDeliverables} />
          <PreviewRow label="验收标准" value={contract.scopeAcceptance} />
          <PreviewRow label="费用" value={formatPrice(contract.amount, contract.currency)} />
          <PreviewRow label="付款计划" value={contract.paymentPlan} />
          <PreviewRow label="交期约定" value={contract.scopeTimeline} />
        </dl>
        <p className="text-xs text-muted-foreground/70">
          {contract.partyASigned && contract.partyBSigned ? (
            <span className="inline-flex items-center gap-1">
              <CircleCheck className="size-3.5" /> 双方均已签署
            </span>
          ) : (
            "合同由后台人工推进，签署进度会在这里更新"
          )}
        </p>
      </CardContent>
    </Card>
  );
}

/** 预览字段行：未录入呈现占位符（准备中合同的内容由后台录入）。 */
function PreviewRow({ label, value }: { label: string; value?: string | null }) {
  return (
    <div className="flex items-baseline justify-between gap-4 border-b pb-2 last:border-b-0 last:pb-0">
      <dt className="shrink-0 text-xs text-muted-foreground">{label}</dt>
      <dd className="min-w-0 text-right break-words">{value?.trim() ? value : "待录入"}</dd>
    </div>
  );
}
