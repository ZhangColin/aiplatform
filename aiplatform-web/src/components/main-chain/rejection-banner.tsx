"use client";

import { Undo2 } from "lucide-react";

import { Button } from "@/components/ui/button";
import { useProjectNoticesStore } from "@/lib/store/project-notices";
import { cn } from "@/lib/utils";

/**
 * 驳回理由横幅（共享层资产，issue #19 / spec 0002 §6）：SSE `stage-changed`
 * （rejected:true, reason）的载荷经桥写入 project-notices store 后在此页内呈现
 * ——REST 详情无此字段，纯展示性瞬时态；正确性仍以 REST 重查为准。再次拍板
 * 通过（approved）由桥自动清掉，用户也可手动关闭。
 */

export type RejectionBannerCopy = {
  title: string;
  reasonPrefix: string;
  dismissLabel: string;
};

const DEFAULT_COPY: RejectionBannerCopy = {
  title: "确认未通过",
  reasonPrefix: "意见",
  dismissLabel: "知道了",
};

export function StageRejectionBanner({
  projectId,
  copy,
  className,
}: {
  projectId: string;
  copy?: Partial<RejectionBannerCopy>;
  className?: string;
}) {
  const text = { ...DEFAULT_COPY, ...copy };
  const notice = useProjectNoticesStore((s) => s.notices[projectId]?.rejection);
  const clearRejection = useProjectNoticesStore((s) => s.clearRejection);
  if (!notice) return null;

  return (
    <div
      role="status"
      className={cn(
        "flex items-start gap-2 rounded-lg border border-amber-500/40 bg-amber-500/10 px-3 py-2.5 text-sm",
        className,
      )}
    >
      <Undo2 className="mt-0.5 size-4 shrink-0 text-amber-600 dark:text-amber-400" />
      <p className="min-w-0 flex-1 leading-relaxed">
        <span className="font-medium text-amber-700 dark:text-amber-400">
          {notice.stageLabel} · {text.title}
        </span>
        <span className="text-foreground/80">
          {" "}
          {text.reasonPrefix}：{notice.reason}
        </span>
      </p>
      <Button
        size="sm"
        variant="ghost"
        className="shrink-0 text-xs text-muted-foreground"
        onClick={() => clearRejection(projectId)}
      >
        {text.dismissLabel}
      </Button>
    </div>
  );
}
