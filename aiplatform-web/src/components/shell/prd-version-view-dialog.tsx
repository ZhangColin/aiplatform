"use client";

import Markdown from "react-markdown";
import remarkGfm from "remark-gfm";

import { prdMarkdownComponents } from "@/components/project-page/prd-doc";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Skeleton } from "@/components/ui/skeleton";
import { usePrdVersion } from "@/hooks/use-prd";
import { formatRelativeTime } from "@/lib/utils/time";

/**
 * PRD 版本快照全文弹窗（#344 版本记录抽屉条目点开面）：确认时工作区 PRD
 * 全文的不可变快照只读呈现——markdown 渲染样式与文档范式同源
 * （{@link prdMarkdownComponents} 单点）。只读＝无任何编辑入口；快照行不可变，
 * 与文档范式（活草稿＋确认门）的分野在此呈现。
 */
export function PrdVersionViewDialog({
  projectId,
  versionNo,
  onOpenChange,
}: {
  projectId: string;
  /** 要查看的版本号（null＝未选条目，弹窗不查不出）。 */
  versionNo: number | null;
  onOpenChange: (open: boolean) => void;
}) {
  const open = versionNo !== null;
  const detail = usePrdVersion(projectId, versionNo);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="flex max-h-[90vh] w-[94vw] max-w-[860px] flex-col gap-0 overflow-hidden p-0 sm:max-w-[860px]">
        <DialogHeader className="border-b px-4 py-3 pr-12">
          <DialogTitle>
            {versionNo !== null ? `PRD v${versionNo} · 快照全文` : "PRD 快照全文"}
          </DialogTitle>
          <DialogDescription>
            {detail.data
              ? confirmedText(detail.data.confirmedAt)
              : "确认此版 PRD 时的全文快照，只读。"}
          </DialogDescription>
        </DialogHeader>
        <div className="min-h-0 flex-1 overflow-y-auto">
          {detail.isPending ? (
            <div className="mx-auto max-w-2xl space-y-4 p-6">
              <Skeleton className="h-6 w-40" />
              <Skeleton className="h-32 w-full" />
            </div>
          ) : detail.isError ? (
            <p className="p-6 text-sm text-destructive">
              快照加载失败，请关闭后重试。
            </p>
          ) : (
            <article className="mx-auto max-w-2xl space-y-5 p-6 text-sm leading-relaxed">
              <Markdown remarkPlugins={[remarkGfm]} components={prdMarkdownComponents}>
                {detail.data?.content ?? ""}
              </Markdown>
            </article>
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
}

/** 确认时间（不可解析不臆造——回落基线话术）。 */
function confirmedText(iso: string | undefined): string {
  const relative = formatRelativeTime(iso);
  return relative ? `确认于 ${relative}，全文只读。` : "确认时的全文快照，只读。";
}
