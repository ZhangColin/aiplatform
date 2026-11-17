import { MessageCircleQuestion } from "lucide-react";

import type { InterviewQuotaResponse } from "@/lib/projects/interview-quota";

/**
 * 五问额度进度 chip（#538 访谈①，ADR-0048）：「已答 N/5 问」——服务端单源呈现，
 * 前端不自算（数与判定同读 GET /projects/{id}/interview-quota）。非访谈期
 * （付费翻转 / PRD 已产出 / 已生成）读面归 null，chip 不呈现。
 */
export function InterviewQuotaChip({
  quota,
}: {
  quota: InterviewQuotaResponse | null | undefined;
}) {
  if (!quota) return null;
  return (
    <p className="pb-1 text-center">
      <span
        data-interview-quota
        className="inline-flex items-center gap-1 rounded-full border border-foreground/10 bg-muted/50 px-2.5 py-1 text-xs text-muted-foreground"
      >
        <MessageCircleQuestion className="size-3.5" aria-hidden="true" />
        已答 {quota.answered}/{quota.total} 问
      </span>
    </p>
  );
}
