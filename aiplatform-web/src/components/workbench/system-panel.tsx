"use client";

import { LoaderCircle, Monitor, TriangleAlert } from "lucide-react";
import type { ReactNode } from "react";

import {
  previewEpochOf,
  retryMessageOf,
  useGenerationStore,
  type CoderRunStatus,
} from "@/lib/store/generation";
import { useProjectPreview } from "@/hooks/use-project-preview";

import { StartSystemButton } from "./start-generation";

/** 重试话术的本地回落（帧丢失防御位；正本随 task-retrying 帧下发）。 */
const FALLBACK_RETRY_MESSAGE = "遇到问题，正在重试";

/**
 * 系统模式主区域（#22 片2-1）：恒为预览的容器。生成待期 = 空白浏览器窗 + 一句
 * 提示（无进度剧场）；run 完成信号（generation store 预览纪元）驱动预览自动
 * 重挂 iframe——url+epoch 为 key，替掉手动「点击刷新」；重试待期播帧内话术
 * （「遇到问题，正在重试」）；超限终态给重新发起入口（人工兜底）。ready 判据 =
 * generatedAt（REST 事实，跨会话）或本会话收口信号（即时性）；收口后的详情
 * 重拉（generated_at）归 bridge 失效，本组件只消费。
 */
export function SystemPanel({
  projectId,
  generatedAt,
  coderStatus,
  onGenerated,
}: {
  projectId: string;
  /** 首次生成时点（REST 事实；null = 未生成过）。 */
  generatedAt?: string | null;
  /** 本会话编码 run 状态（undefined = 未见）。 */
  coderStatus?: CoderRunStatus;
  /** 发起成功回调（切系统模式呈现等待态），归装配层。 */
  onGenerated: () => void;
}) {
  const epoch = useGenerationStore((s) => previewEpochOf(s, projectId));
  const retryMessage = useGenerationStore((s) => retryMessageOf(s, projectId));
  const ready = generatedAt != null || epoch > 0;
  const preview = useProjectPreview(projectId, ready);

  const url = preview.data?.url;

  return (
    <div className="flex h-full min-h-0 flex-col p-4">
      <div className="flex min-h-0 flex-1 flex-col overflow-hidden rounded-xl border bg-background shadow-sm">
        {/* 浏览器窗栏 */}
        <div className="flex h-9 shrink-0 items-center gap-3 border-b bg-muted/60 px-3">
          <span className="flex gap-1.5">
            <span className="size-2.5 rounded-full bg-muted-foreground/25" />
            <span className="size-2.5 rounded-full bg-muted-foreground/25" />
            <span className="size-2.5 rounded-full bg-muted-foreground/25" />
          </span>
          <span className="min-w-0 flex-1 truncate rounded-md bg-background px-2.5 py-1 text-center text-xs text-muted-foreground">
            {ready ? (url ?? "正在接通系统…") : "你的系统"}
          </span>
        </div>

        {/* 内容区 */}
        <div className="min-h-0 flex-1">
          {ready ? (
            url ? (
              // key 含预览纪元：run 完成信号驱动重挂（同 URL 也强制重建 iframe）
              <iframe
                key={previewFrameKey(url, epoch)}
                src={url}
                title="系统预览"
                className="h-full w-full border-0 bg-white"
              />
            ) : (
              <PanelHint>
                {preview.isError ? (
                  <span className="text-destructive">预览暂时打不开，稍后会自动重试</span>
                ) : (
                  <LoaderCircle className="size-5 animate-spin text-muted-foreground" />
                )}
              </PanelHint>
            )
          ) : coderStatus === "retrying" ? (
            <PanelHint>
              <LoaderCircle className="size-5 animate-spin text-muted-foreground" />
              <p>{retryMessage ?? FALLBACK_RETRY_MESSAGE}</p>
            </PanelHint>
          ) : coderStatus === "running" ? (
            <PanelHint>
              <LoaderCircle className="size-5 animate-spin text-muted-foreground" />
              <p>正在为您生成系统…</p>
            </PanelHint>
          ) : coderStatus === "error" ? (
            <PanelHint>
              <TriangleAlert className="size-5 text-destructive" />
              <p>生成遇到了问题</p>
              <StartSystemButton projectId={projectId} onGenerated={onGenerated} label="重新发起" />
            </PanelHint>
          ) : (
            <PanelHint>
              <Monitor className="size-5 text-muted-foreground" />
              <p>开始做系统后，这里会出现可以操作的你的系统</p>
            </PanelHint>
          )}
        </div>
      </div>
    </div>
  );
}

/** 空白浏览器窗的内容提示（一句提示，无进度剧场）。 */
function PanelHint({ children }: { children: ReactNode }) {
  return (
    <div className="flex h-full flex-col items-center justify-center gap-3 px-6 text-center text-sm text-muted-foreground">
      {children}
    </div>
  );
}

/**
 * 预览 iframe 的重挂 key：url + 预览纪元——编码 run 每次收口纪元 +1，同 URL 也
 * 强制重建 iframe（run 完成信号驱动预览自动刷新的唯一机制，替掉手动点击刷新）。
 */
export function previewFrameKey(url: string, epoch: number): string {
  return `${url}#${epoch}`;
}
