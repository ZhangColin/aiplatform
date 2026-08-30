"use client";

import { ExternalLink, PanelTop, RotateCw } from "lucide-react";
import { useState } from "react";

import { Button } from "@/components/ui/button";
import { useProjectPreview } from "@/hooks/use-project";
import { useProjectNoticesStore } from "@/lib/store/project-notices";
import { cn } from "@/lib/utils";

/**
 * 预览面板（共享层资产，spec 0001 §5 / spec 0002 §6）：
 * - `PreviewPanel` = 项目详情场景：`GET …/preview` 200 且带 url 才点亮（点亮信号链 =
 *   SSE preview-ready → 桥失效 → 重拉）。
 * - `PreviewChrome` = url 已知的消费侧（OPC 任务详情 ProjectBrief.previewUrl，#22）。
 * 两者共用同一套浏览器铬：iframe sandbox 三允许、**不给 allow-same-origin**；「有
 * 更新 · 点击刷新」= 重挂 iframe 手动刷新不自动。信号源 = project-notices store
 * 的 previewUpdate 位（桥置位）；iframe 每次 onLoad（首次点亮 / 换址 / 手动刷新）
 * 都消费掉信号——「有更新」只对已加载过的同一 url 成立，首亮不误报。独立浏览器
 * 页走 window.open。
 */

/** spec 口径：允许脚本 / 表单 / 弹窗，不给同源（隔离 agent 产物）。 */
const PREVIEW_SANDBOX = "allow-scripts allow-forms allow-popups";

export function PreviewPanel({
  projectId,
  title = "预览",
  className,
}: {
  projectId: string;
  title?: string;
  className?: string;
}) {
  const preview = useProjectPreview(projectId);
  return (
    <PreviewChrome
      projectId={projectId}
      url={preview.data ?? null}
      title={title}
      className={className}
    />
  );
}

export function PreviewChrome({
  projectId,
  url,
  title = "预览",
  className,
}: {
  /** 「有更新」信号按项目键控（SSE preview-ready 的 projectId）。 */
  projectId: string;
  /** null = 预览未就绪占位。 */
  url: string | null;
  title?: string;
  className?: string;
}) {
  const rawUpdate = useProjectNoticesStore((s) => s.notices[projectId]?.previewUpdate === true);
  const ackPreviewUpdate = useProjectNoticesStore((s) => s.ackPreviewUpdate);
  // 手动刷新 = 重挂 iframe：mountSeq 递增，不自动
  const [mountSeq, setMountSeq] = useState(0);
  // 已加载完成的 url：「有更新」只对已看过的同一 url 有意义
  const [loadedUrl, setLoadedUrl] = useState<string | null>(null);

  const hasUpdate = rawUpdate && url === loadedUrl;
  const refresh = () => {
    setMountSeq((n) => n + 1);
    ackPreviewUpdate(projectId);
  };

  if (!url) {
    return (
      <div
        className={cn(
          "flex min-h-0 flex-1 flex-col items-center justify-center gap-2 p-6 text-center",
          className,
        )}
      >
        <PanelTop className="size-8 text-muted-foreground/40" />
        <p className="text-sm font-medium text-muted-foreground">预览还未就绪</p>
        <p className="text-xs text-muted-foreground/70">就绪后此处自动点亮</p>
      </div>
    );
  }

  return (
    <div className={cn("flex min-h-0 flex-1 flex-col", className)}>
      {/* 浏览器铬：地址 + 手动刷新 + 独立打开 */}
      <div className="flex h-10 shrink-0 items-center gap-2 border-b px-3">
        <span className="min-w-0 flex-1 truncate font-mono text-xs text-muted-foreground">
          {url}
        </span>
        {hasUpdate ? (
          <Button size="sm" variant="secondary" className="text-amber-600" onClick={refresh}>
            <RotateCw /> 有更新 · 点击刷新
          </Button>
        ) : (
          <Button size="icon-sm" variant="ghost" onClick={refresh} aria-label="刷新预览">
            <RotateCw />
          </Button>
        )}
        <Button
          size="sm"
          variant="ghost"
          onClick={() => window.open(url, "_blank", "noopener,noreferrer")}
        >
          <ExternalLink /> 浏览器打开
        </Button>
      </div>
      <iframe
        key={`${url}#${mountSeq}`}
        src={url}
        sandbox={PREVIEW_SANDBOX}
        title={title}
        onLoad={() => {
          // 加载到的就是最新内容：无论首亮 / 换址 / 刷新，信号就此消费
          setLoadedUrl(url);
          ackPreviewUpdate(projectId);
        }}
        className="min-h-0 w-full flex-1 border-0 bg-white"
      />
    </div>
  );
}
