"use client";

import { Download, X } from "lucide-react";
import { useState } from "react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { downloadWorkspaceFile } from "@/lib/projects/download";
import { downloadFileNameOf, downloadFileUrl, rawFileUrl, relativeFormOf } from "@/lib/projects/files";
import { FRAME_H, FRAME_W, type CanvasDraft } from "@/lib/projects/design-canvas";

/**
 * 稿卡点开预览（#294，ADR-0025「点开预览：界面类可交互、平面类大图」）：界面类
 * ＝1280×800 固定画幅帧等比缩放进弹窗、指针放开（卡上呈现是拖排面，点开才是
 * 观看面——帧内 CSS 交互可达；服务端 CSP 禁脚本的结构性口径不变，稿是帧不是
 * 网站）；平面类＝大图。底部下载动作（所见即所下，ADR-0027 支付门只盖下载面）：
 * 下载图＝界面类 PNG 位图化（chromium、1280×800 与画布同帧）/平面类即图片本体
 * （通用单文件下载）；下载 HTML＝帧源文件。门判定在后端——前端不预判门态，被拦
 * 时直出信封 message 如实告知（402 ORD_015）。
 */

/** 下载 URL 面：界面类图走位图化端点，其余走通用单文件下载。 */
function draftPngUrl(projectId: string, path: string): string {
  return `/api/projects/${projectId}/design-drafts/png?path=${encodeURIComponent(relativeFormOf(path))}`;
}

/** 位图化下载的落盘名（词干＋画幅——所见即所下的事实进文件名；与后端控制器
 * fileNameOf 同契约互指）。 */
function draftPngNameOf(path: string): string {
  const name = downloadFileNameOf(path);
  const dot = name.lastIndexOf(".");
  const stem = dot > 0 ? name.slice(0, dot) : name;
  return `${stem}-${FRAME_W}x${FRAME_H}.png`;
}

export function DesignPreviewModal({
  projectId,
  draft,
  name,
  onClose,
}: {
  projectId: string;
  draft: CanvasDraft;
  /** 呈现名（draftDisplayName 已派生）。 */
  name: string;
  onClose: () => void;
}) {
  const [pending, setPending] = useState<"png" | "html" | null>(null);
  const live = draft.media === "html";

  const onDownload = async (kind: "png" | "html") => {
    const bitmap = kind === "png" && live;
    setPending(kind);
    try {
      await downloadWorkspaceFile(
        bitmap ? draftPngUrl(projectId, draft.path) : downloadFileUrl(projectId, draft.path),
        bitmap ? draftPngNameOf(draft.path) : downloadFileNameOf(draft.path),
      );
    } finally {
      setPending(null);
    }
  };

  return (
    <div
      data-design-preview={draft.path}
      className="fixed inset-0 z-40 flex flex-col bg-black/50 backdrop-blur-sm"
      onClick={onClose}
    >
      <div
        className="mx-auto mt-10 flex max-h-[78vh] w-[min(1080px,92vw)] flex-col overflow-hidden rounded-2xl border bg-background shadow-2xl"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="flex h-11 shrink-0 items-center gap-2 border-b px-4">
          <span className="text-sm font-medium">{name}</span>
          <Badge variant="secondary" className="text-[10px]">
            {live ? "界面类候选 · 1280×800" : "平面类候选"}
          </Badge>
          <span className="flex-1" />
          <Button variant="ghost" size="icon" className="size-8" onClick={onClose} aria-label="关闭预览">
            <X className="size-4" />
          </Button>
        </div>
        <div className="min-h-0 flex-1 bg-muted/30 p-4">
          {live ? (
            <div className="relative mx-auto h-full w-full max-w-[1400px] overflow-hidden rounded-lg border bg-white shadow-lg">
              <FitFrame projectId={projectId} path={draft.path} title={name} />
            </div>
          ) : (
            // eslint-disable-next-line @next/next/no-img-element -- 平台文件服务直出的设计稿，非静态资源（Next Image 不适用）
            <img
              src={rawFileUrl(projectId, draft.path)}
              alt={name}
              data-preview-image={draft.path}
              className="mx-auto max-h-full rounded-lg shadow-lg"
            />
          )}
        </div>
        <div className="flex shrink-0 flex-wrap items-center gap-2 border-t px-4 py-2">
          <span className="text-xs text-muted-foreground">
            {live ? "固定画幅帧——下载的图就是看到的这帧" : "平面类候选 · 出稿即成品位图"}
          </span>
          <span className="flex-1" />
          <Button
            variant="outline"
            size="sm"
            className="h-7 text-xs"
            disabled={pending !== null}
            data-download-png={draft.path}
            onClick={() => void onDownload("png")}
          >
            <Download className="size-3.5" /> 下载图{live ? "（PNG）" : "（源文件）"}
          </Button>
          {live ? (
            <Button
              variant="outline"
              size="sm"
              className="h-7 text-xs"
              disabled={pending !== null}
              data-download-html={draft.path}
              onClick={() => void onDownload("html")}
            >
              <Download className="size-3.5" /> 下载 HTML
            </Button>
          ) : null}
        </div>
      </div>
    </div>
  );
}

/** 弹窗内的等比缩放帧：容器实高定缩放（iframe 恒 1280×800，交互面放开）。 */
function FitFrame({ projectId, path, title }: { projectId: string; path: string; title: string }) {
  const [scale, setScale] = useState(0.5);
  return (
    <div
      ref={(el) => {
        if (!el || typeof ResizeObserver === "undefined") return;
        // 每挂载新建观察器（ref 回调形态——弹窗短生命周期，无泄漏面）
        const observer = new ResizeObserver((entries) => {
          const rect = entries[0]?.contentRect;
          if (rect && rect.height) {
            setScale(Math.min(rect.width / FRAME_W, rect.height / FRAME_H));
          }
        });
        observer.observe(el);
      }}
      className="absolute inset-0"
    >
      <iframe
        title={title}
        src={rawFileUrl(projectId, path)}
        data-preview-frame={path}
        className="absolute left-1/2 top-1/2 origin-center border-0 bg-white"
        style={{
          width: FRAME_W,
          height: FRAME_H,
          transform: `translate(-50%, -50%) scale(${scale})`,
        }}
        sandbox=""
        tabIndex={-1}
      />
    </div>
  );
}
