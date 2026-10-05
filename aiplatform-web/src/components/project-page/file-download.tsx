"use client";

import { Download } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import type { ApiEnvelopeMeta } from "@/lib/api/api-error";
import { downloadFileNameOf, downloadFileUrl } from "@/lib/projects/files";

/**
 * 单文件下载按钮（#287 通用下载＋支付门，ADR-0027「平台上随便体验、带走才
 * 付费」）：文件区一切文件皆可带走，文本/图片/PRD 三个点看视图头部共用。
 * 支付门只盖下载面、判定在后端（项目曾有已支付/已归档订单即开放）——前端
 * 不预判门态（不做第二套口径），被拦时直出后端信封 message 如实告知门语义
 * （402 ORD_015）。二进制不走 api client（其响应一律按 JSON 解包）：同源
 * 直链 fetch 取 blob 后临时锚点落盘。
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
      const res = await fetch(downloadFileUrl(projectId, path));
      if (!res.ok) {
        // 错误面与 api client 同形（信封 message 直出，门语义如实告知）
        const payload: ApiEnvelopeMeta | null = await res.json().catch(() => null);
        toast.error(payload?.message ?? `下载失败（HTTP ${res.status}）`);
        return;
      }
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = downloadFileNameOf(path) || "download";
      anchor.click();
      URL.revokeObjectURL(url);
    } catch {
      // 网络层失败（fetch 抛出）：如实提示，不假装成功
      toast.error("下载失败，请稍后重试");
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
