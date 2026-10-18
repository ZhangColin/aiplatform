"use client";

import { Download } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import type { ApiEnvelopeMeta } from "@/lib/api/api-error";
import { PRD_PATH, downloadFileUrl } from "@/lib/projects/files";

/**
 * PRD 导出菜单（#346，spec #320 ⑥ 需求分析页 header「导出 (PDF/Word)」按钮位
 * 照原型；实现归属＝票注「首版最小可用形态」定为前端打印/客户端 .doc）：
 * 两项动作都先过既有支付门——GET 文件下载端点探测（门判定在后端，前端不预判
 * 门态、不做第二套口径；402 直出后端信封 message 如实告知，file-download 先例）。
 * 门开才动手：
 *
 * - 「打印 / 存为 PDF」＝浏览器打印（globals.css @media print 只留 #prd-print-root
 *   正文，用户选「另存为 PDF」即 PDF）；
 * - 「下载 Word (.doc)」＝正文 innerHTML 包 Word HTML 壳落盘（Word/WPS 直开）。
 */
export function PrdExportMenu({
  projectId,
  disabled = false,
  fileBaseName,
  getArticleHtml,
}: {
  projectId: string;
  /** 无 PRD 无可导出——入口禁用。 */
  disabled?: boolean;
  /** 落盘文件名基（不带扩展名）。 */
  fileBaseName: string;
  /** 取 PRD 正文渲染 HTML（页面装配层持 ref 供给——打印与 Word 同源）。 */
  getArticleHtml: () => string | null;
}) {
  const [pending, setPending] = useState(false);

  /** 支付门探测（后端判定）：门开=true。被拦/失败如实 toast。 */
  async function gateOpen(): Promise<boolean> {
    try {
      const res = await fetch(downloadFileUrl(projectId, PRD_PATH));
      if (!res.ok) {
        const payload: ApiEnvelopeMeta | null = await res.json().catch(() => null);
        toast.error(payload?.message ?? `导出失败（HTTP ${res.status}）`);
        return false;
      }
      return true;
    } catch {
      toast.error("导出失败，请稍后重试");
      return false;
    }
  }

  async function run(action: "print" | "word") {
    if (pending) return;
    setPending(true);
    try {
      if (!(await gateOpen())) return;
      if (action === "print") {
        window.print();
        return;
      }
      const html = getArticleHtml();
      if (!html) {
        toast.error("正文还没渲染好，请稍后再试");
        return;
      }
      downloadWord(html, fileBaseName);
    } finally {
      setPending(false);
    }
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={disabled || pending}
            className="h-9 gap-1.5 rounded-xl text-label-md font-medium"
          />
        }
      >
        <Download className="size-4 text-ink-faint" aria-hidden="true" />
        导出 (PDF/Word)
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-48">
        <DropdownMenuItem onClick={() => void run("print")}>打印 / 存为 PDF</DropdownMenuItem>
        <DropdownMenuItem onClick={() => void run("word")}>下载 Word (.doc)</DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/** Word HTML 壳落盘（.doc＝HTML 载体，Word/WPS 直开——首版最小可用形态）。 */
function downloadWord(articleHtml: string, fileBaseName: string) {
  const shell = [
    '<html xmlns:o="urn:schemas-microsoft-com:office:office" xmlns:w="urn:schemas-microsoft-com:office:word">',
    '<head><meta charset="utf-8"><title>PRD</title></head>',
    `<body style="font-family:'PingFang SC','Microsoft YaHei',sans-serif;line-height:1.75;">`,
    articleHtml,
    "</body></html>",
  ].join("");
  const blob = new Blob([shell], { type: "application/msword" });
  const objectUrl = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = objectUrl;
  anchor.download = `${fileBaseName || "需求文档"}.doc`;
  anchor.click();
  URL.revokeObjectURL(objectUrl);
}
