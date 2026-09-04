"use client";

import { LoaderCircle, Monitor, TriangleAlert } from "lucide-react";

import { useProjectPreview } from "@/hooks/use-project-preview";
import { isPreviewNotServing } from "@/lib/preview/state";

/**
 * 预览新窗口独立页主体（#80「在新窗口打开」的落地）：只渲染用户系统本身——
 * 全幅 iframe、整页浅色锁定（预览内容是用户产物，不随平台 Light/Dark 翻转；
 * 页在 (site) 壳外，无侧栏无浏览器条——窗栏就是真浏览器的）。地址自取（探活
 * 轮询直到 URL 到）；无生成纪元流（SSE 通道归项目页挂载），开窗呈现系统现状，
 * 后续更新靠浏览器刷新。未就绪（WSP_012）视同接通中，真故障走打不开口径
 * （探活轮询自会重试）。
 */
export function PreviewWindow({ projectId }: { projectId: string }) {
  const preview = useProjectPreview(projectId, true);
  const url = preview.data?.url;
  const trouble = preview.error != null && !isPreviewNotServing(preview.error);

  return (
    <div className="light-lock flex h-svh flex-col bg-background text-foreground">
      {url ? (
        <iframe src={url} title="系统预览" className="w-full flex-1 border-0 bg-white" />
      ) : trouble ? (
        <WindowHint>
          <TriangleAlert className="size-5 text-destructive" />
          <p>预览暂时打不开，稍后会自动重试</p>
        </WindowHint>
      ) : (
        <WindowHint>
          {preview.error == null ? (
            <LoaderCircle className="size-5 animate-spin text-muted-foreground" />
          ) : (
            <Monitor className="size-5 text-muted-foreground" />
          )}
          <p>正在接通系统…</p>
        </WindowHint>
      )}
    </div>
  );
}

/** 接通中 / 打不开的内容提示（与系统面板同口径的一句话）。 */
function WindowHint({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex flex-1 flex-col items-center justify-center gap-3 px-6 text-center text-sm text-muted-foreground">
      {children}
    </div>
  );
}
