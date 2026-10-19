"use client";

import { BadgeCheck, CircleCheck, CircleDashed, Download, FileSignature, RefreshCw } from "lucide-react";

import { cn } from "@/lib/utils";
import { CONTRACT_STATUS, CONTRACT_TERMINAL_CODES, type ContractDetail } from "@/lib/orders/contract";
import { formatPrice } from "@/lib/orders/price";

/**
 * 子步二「签署合同」（#348，spec #320 ⑥ 照原型）：合同预览呈现——六态
 * （准备中/待核对与签署/签署进行中/签署完成/失败或过期/撤回或作废）＋双方签署
 * 位＋预览字段（双方主体/项目范围/交付物/验收标准/费用/付款计划/交期约定）＋
 * 版本号＋附件位。六态全部后台人工推进、这里只呈现不操作；签署完成后呈现定格
 * （终态合同永不删除）。存证哈希/证书＝原型虚构演示数据照搬（电子签渠道真接
 * 后提供下载——按钮禁用占位，不伪造可下载文件）。
 */
export function ContractSignCard({ contract }: { contract: ContractDetail | null }) {
  return (
    <section
      id="contract"
      aria-label="签署合同"
      className="flex flex-col gap-4 rounded-2xl border border-slate-soft/80 bg-white p-5 shadow-e1"
    >
      <div className="flex items-center justify-between border-b border-slate-soft/60 pb-3">
        <div className="flex items-center gap-2.5">
          <div className="flex size-8 items-center justify-center rounded-lg bg-emerald-active/10 text-emerald-active">
            <BadgeCheck className="size-4.5" aria-hidden="true" />
          </div>
          <h2 className="text-headline-sm font-bold text-ink-primary">电子签名存证状态</h2>
        </div>
        {contract ? <StatusPill contract={contract} /> : null}
      </div>

      {!contract ? (
        <p className="text-body-sm text-ink-faint">
          确认范围并准备合同后，合同会在这里生成并推进签署；签署进度由后台人工推进。
        </p>
      ) : (
        <>
          <div className="flex items-center justify-between gap-3">
            <p className="flex items-center gap-2 text-body-md font-medium text-ink-secondary">
              <FileSignature className="size-4 text-ink-faint" aria-hidden="true" />
              {contract.statusName ?? "合同处理中"}
            </p>
            <p className="text-label-sm text-ink-faint">版本 v{contract.version ?? 1}</p>
          </div>

          {contract.needsUpdate ? (
            <p className="flex items-center gap-1.5 rounded-md border border-amber-400/40 bg-warning/10 px-3 py-2 text-label-sm text-warning-strong">
              <RefreshCw className="size-3.5" aria-hidden="true" />
              范围或报价有更新，正在重新准备合同（更新后请重新核对）
            </p>
          ) : null}

          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <Signer
              label="甲方签署人 (客户方)"
              name={contract.partyA?.trim() || "客户方"}
              signed={contract.partyASigned}
              signedNote="已验签"
            />
            <Signer
              label="乙方签署人 (交付方)"
              name={contract.partyB?.trim() || "语义引力数字交付中心"}
              signed={contract.partyBSigned}
              signedNote="已验签 · 法人电子印章"
            />
          </div>

          <dl className="flex flex-col gap-2">
            <PreviewRow label="项目范围" value={contract.projectScope} />
            <PreviewRow label="交付物" value={contract.scopeDeliverables} />
            <PreviewRow label="验收标准" value={contract.scopeAcceptance} />
            <PreviewRow label="费用" value={formatPrice(contract.amount, contract.currency)} />
            <PreviewRow label="付款计划" value={contract.paymentPlan} />
            <PreviewRow label="交期约定" value={contract.scopeTimeline} />
          </dl>

          <AttachmentSlot signed={contract.status === CONTRACT_STATUS.signed} />
        </>
      )}
    </section>
  );
}

/** 六态胶囊：签署完成＝薄荷绿「双方全部盖章有效」；终态（失败/撤回）＝中性「已终止」。 */
function StatusPill({ contract }: { contract: ContractDetail }) {
  const signed = contract.status === CONTRACT_STATUS.signed;
  const terminal =
    contract.status != null && CONTRACT_TERMINAL_CODES.includes(contract.status);
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1.5 rounded-full border px-3 py-1 text-label-sm font-semibold",
        signed
          ? "border-emerald-active/30 bg-emerald-active/10 text-success-deep"
          : terminal
            ? "border-slate-soft bg-surface-dawn text-ink-muted"
            : "border-tech-blue/25 bg-surface-mist text-tech-blue",
      )}
    >
      <span
        className={cn(
          "size-2 rounded-full",
          signed ? "bg-emerald-active" : terminal ? "bg-ink-faint" : "bg-tech-blue animate-pulse",
        )}
        aria-hidden="true"
      />
      {signed ? "双方全部盖章有效" : contract.statusName ?? "处理中"}
    </span>
  );
}

/** 签署人格：实体名（真实 partyA/partyB）＋已验签/未签署。 */
function Signer({
  label,
  name,
  signed,
  signedNote,
}: {
  label: string;
  name: string;
  signed: boolean;
  signedNote: string;
}) {
  return (
    <div className="flex flex-col gap-1 rounded-xl border border-slate-soft/70 bg-surface-dawn/50 p-4">
      <span className="text-label-sm text-ink-faint">{label}</span>
      <span className="text-headline-sm font-bold text-ink-primary">{name}</span>
      <div
        className={cn(
          "mt-1 flex items-center gap-1.5 text-label-sm font-medium",
          signed ? "text-success-deep" : "text-ink-faint",
        )}
      >
        {signed ? (
          <CircleCheck className="size-4" aria-hidden="true" />
        ) : (
          <CircleDashed className="size-4" aria-hidden="true" />
        )}
        {signed ? signedNote : "未签署"}
      </div>
    </div>
  );
}

/** 附件位（照原型虚构存证哈希＋禁用的证书下载——电子签真接后提供）。 */
function AttachmentSlot({ signed }: { signed: boolean }) {
  const HASH = "7f83b1657ff1fc53b92dc18148a1d65dfc2d4b1fa3d677284addd200126d9069";
  return (
    <div className="flex flex-col gap-2 rounded-xl border border-slate-soft/70 bg-surface-dawn p-4">
      <div className="flex items-center justify-between">
        <span className="text-label-sm font-bold tracking-wider text-ink-faint uppercase">
          SHA-256 哈希存证证书
        </span>
        <span className="rounded border border-emerald-active/25 bg-emerald-active/10 px-2 py-0.5 font-mono text-code-sm font-semibold text-success-deep">
          E-SIGN ANCHOR VERIFIED
        </span>
      </div>
      <p className="break-all rounded-lg border border-slate-soft bg-white p-2.5 font-mono text-code-sm text-ink-secondary">
        {HASH}
      </p>
      <div className="flex justify-end">
        <button
          type="button"
          disabled
          title={signed ? "电子签渠道接入后提供下载" : "签署完成后生成存证证书"}
          className="inline-flex cursor-not-allowed items-center gap-1.5 rounded-lg border border-slate-soft bg-white px-3 py-1.5 text-label-md font-medium text-ink-faint"
        >
          <Download className="size-4" aria-hidden="true" />
          存证证书 ⬇
        </button>
      </div>
    </div>
  );
}

/** 预览字段行：未录入呈现「待录入」占位（准备中合同内容由后台录入）。 */
function PreviewRow({ label, value }: { label: string; value?: string | null }) {
  return (
    <div className="flex items-baseline justify-between gap-4 border-b border-slate-soft/50 pb-2 last:border-b-0 last:pb-0">
      <dt className="shrink-0 text-label-sm text-ink-faint">{label}</dt>
      <dd className="min-w-0 text-right break-words text-body-sm text-ink-secondary">
        {value?.trim() ? value : "待录入"}
      </dd>
    </div>
  );
}
