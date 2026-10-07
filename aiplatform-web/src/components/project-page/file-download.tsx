"use client";

import { Download } from "lucide-react";
import { useState } from "react";

import { Button } from "@/components/ui/button";
import { downloadWorkspaceFile } from "@/lib/projects/download";
import { downloadFileNameOf, downloadFileUrl } from "@/lib/projects/files";

/**
 * 单文件下载按钮（#287 通用下载＋支付门，ADR-0027「平台上随便体验、带走才
 * 付费」）：文件区一切文件皆可带走，文本/图片/PRD 三个点看视图头部共用。
 * 支付门只盖下载面、判定在后端（项目曾有已支付/已归档订单即开放）——前端
 * 不预判门态（不做第二套口径），被拦时直出后端信封 message 如实告知门语义
 * （402 ORD_015）。浏览器腿取件落盘归 {@link downloadWorkspaceFile} 单点。
 */
export function FileDownloadButton({
  projectId,
  path,
}: {
  projectId: string;
  path: string;
}) {
  const [pending, setPending] = useState(false);

  const onDownload = async () => {
    setPending(true);
    try {
      await downloadWorkspaceFile(downloadFileUrl(projectId, path), downloadFileNameOf(path));
    } finally {
      setPending(false);
    }
  };

  return (
    <Button
      variant="ghost"
      size="icon-sm"
      aria-label="下载文件"
      title="下载"
      className="ml-auto shrink-0"
      disabled={pending}
      onClick={onDownload}
      data-file-download={path}
    >
      <Download className="size-3.5" />
    </Button>
  );
}
