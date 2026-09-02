"use client";

import { LoaderCircle, Monitor, TriangleAlert } from "lucide-react";
import type { ReactNode } from "react";

import {
  previewEpochOf,
  retryMessageOf,
  useGenerationStore,
  type CoderRunStatus,
} from "@/lib/store/generation";
import { liveSegmentsOf, useLiveStore } from "@/lib/store/live";
import { previewActive, systemPanelPhase } from "@/lib/preview/state";
import { useProjectPreview } from "@/hooks/use-project-preview";

import { RestartFixButton } from "./restart-fix";
import { StartSystemButton } from "./start-generation";

/**
 * 系统模式主区域（#22 片2-1 + #26 迭代环① + #45 渐进预览第一片 + #48 修正
 * 超限终态恢复出口）：恒为预览的容器。门禁解除——run 开始（含发起成功的乐观
 * 登记）即取预览地址并挂机制，不等 run-finish 纪元；后端探活通过才返回 URL，
 * 有 URL 即上真页面（空白页可接受）。空态两档（推导归 lib/preview/state 纯
 * 函数，本组件只呈现）：无应用 = 占位随直播事件推进的步骤提示（自述优先、动作
 * 摘要兜底，无信号「正在初始化」）；有应用且 run 中 = 保留页面 + 「更新中」
 * 轻提示（生长期与修正期同一套）。跨会话与重试不闪断：有 URL 就不退占位；run
 * 收口纪元驱动 iframe 重挂（url+epoch 为 key）；超限终态给人工兜底入口——从未
 * 生成「重新发起」、修正轮「重新修改」，正常态全无。
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
  const segments = useLiveStore((s) => liveSegmentsOf(s, projectId));
  // 门禁解除（#45）：run 开始或已有生成事实即取预览地址——不等收口纪元
  const active = previewActive(coderStatus, generatedAt);
  const preview = useProjectPreview(projectId, active);
  const url = preview.data?.url;
  const phase = systemPanelPhase({
    coderStatus,
    generatedAt,
    url,
    error: preview.error,
    liveSegments: segments,
    retryMessage,
  });
  // 超限终态的人工兜底入口（页面轻提示与占位终态两处共用）：从未生成「重新发起」、
  // 修正轮「重新修改」（#48，重派终态那场的交接物）
  const restart = (
    <StartSystemButton projectId={projectId} onGenerated={onGenerated} label="重新发起" />
  );
  const refix = <RestartFixButton projectId={projectId} />;

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
            {active ? (url ?? "正在接通系统…") : "你的系统"}
          </span>
        </div>

        {/* 内容区 */}
        <div className="relative min-h-0 flex-1">
          {phase.kind === "page" && phase.notice ? (
            // 进行中轻提示（一套话术面：进行中「更新中」/ 重试 / 失败）——预览全程
            // 保持可见，中间态不闪断
            <div
              className={`absolute inset-x-0 top-0 z-10 flex items-center justify-center gap-2 border-b px-3 py-1.5 text-xs backdrop-blur ${
                phase.notice.failed
                  ? "bg-destructive/10 text-destructive"
                  : "bg-background/95 text-muted-foreground"
              }`}
            >
              {phase.notice.failed ? (
                <TriangleAlert className="size-3.5 shrink-0" />
              ) : (
                <LoaderCircle className="size-3.5 shrink-0 animate-spin" />
              )}
              {phase.notice.text}
              {phase.notice.recovery === "restart" ? restart : null}
              {phase.notice.recovery === "refix" ? refix : null}
            </div>
          ) : null}
          {phase.kind === "page" && url ? (
            // key 含预览纪元：run 完成信号驱动重挂（同 URL 也强制重建 iframe）
            <iframe
              key={previewFrameKey(url, epoch)}
              src={url}
              title="系统预览"
              className="h-full w-full border-0 bg-white"
            />
          ) : phase.kind === "hint" ? (
            <PanelHint>
              <LoaderCircle className="size-5 animate-spin text-muted-foreground" />
              <p className="max-w-full truncate">{phase.text}</p>
            </PanelHint>
          ) : phase.kind === "failed" ? (
            <PanelHint>
              <TriangleAlert className="size-5 text-destructive" />
              <p>{phase.text}</p>
              {phase.recovery === "restart" ? restart : null}
              {phase.recovery === "refix" ? refix : null}
            </PanelHint>
          ) : phase.kind === "connecting" ? (
            <PanelHint>
              {phase.trouble ? (
                <span className="text-destructive">预览暂时打不开，稍后会自动重试</span>
              ) : (
                <LoaderCircle className="size-5 animate-spin text-muted-foreground" />
              )}
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
