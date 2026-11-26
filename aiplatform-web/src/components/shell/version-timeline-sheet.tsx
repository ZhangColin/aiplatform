"use client";

import { ExternalLink, History, RotateCcw } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
import { useProjectPreview } from "@/hooks/use-project-preview";
import { useGitVersions, useRollbackVersion } from "@/hooks/use-version";
import type { components } from "@/lib/api/schema";
import { useVersionRecordStore } from "@/lib/store/version-record";
import { formatRelativeTime } from "@/lib/utils/time";

type GitVersion = components["schemas"]["VersionResponse"];

/**
 * 单轴版本时间线（#626，ADR-0056）：替掉旧双轴版本记录抽屉（PRD 版本组×Demo
 * 版本组）。每轮成功生成探活通过后平台自动成版（版本序列＝带版本标记的 git log，
 * 新→旧）；每项＝时间＋用户那句话＋预览入口（打开当前运行中的系统预览）＋
 * 「回到此版」，标出当前版/已确认版。开合走既有 version-record store——顶栏
 * 「版本记录」与版本 chip 同一入口。
 */
export function VersionTimelineSheet({ projectId }: { projectId: string }) {
  const open = useVersionRecordStore((s) => s.open);
  const openVersionRecord = useVersionRecordStore((s) => s.openVersionRecord);
  const closeVersionRecord = useVersionRecordStore((s) => s.closeVersionRecord);
  const { data: versions, isPending, isError } = useGitVersions(projectId);
  // 预览入口＝当前运行中的系统预览（URL 由 run 收口 preview-ready 推送；未就绪禁用）
  const preview = useProjectPreview(projectId, open);
  const previewUrl = preview.data?.url;
  const rollback = useRollbackVersion(projectId);

  function openPreview() {
    if (!previewUrl) return;
    void preview.refetch();
    window.open(previewUrl, "_blank", "noopener");
  }

  return (
    <Sheet
      open={open}
      onOpenChange={(next) => (next ? openVersionRecord() : closeVersionRecord())}
    >
      <SheetContent
        side="right"
        className="flex flex-col gap-0 p-0 data-[side=right]:w-full data-[side=right]:sm:max-w-md"
      >
        <SheetHeader className="border-b">
          <SheetTitle className="flex items-center gap-2">
            <History className="size-4 text-ink-faint" aria-hidden="true" />
            版本
          </SheetTitle>
          <SheetDescription>
            每轮成功生成后自动记录一版；按时间从新到旧，可回看当时那句话、打开当前预览或回到某一版。
          </SheetDescription>
        </SheetHeader>

        <div className="min-h-0 flex-1 overflow-y-auto">
          {isError ? (
            <p className="px-4 py-6 text-body-sm text-error">版本列表加载失败，请稍后重试。</p>
          ) : isPending || versions === undefined ? (
            <p className="px-4 py-6 text-body-sm text-ink-faint">正在加载版本…</p>
          ) : versions.length === 0 ? (
            <p className="px-4 py-6 text-body-sm text-ink-faint">
              尚无版本——每轮生成成功后会自动记录一个版本。
            </p>
          ) : (
            <ul className="flex flex-col gap-1.5 px-3 py-3">
              {versions.map((version) => (
                <VersionTimelineRow
                  key={version.commitHash ?? String(version.versionNo)}
                  version={version}
                  previewUrl={previewUrl}
                  onOpenPreview={openPreview}
                  rollbackPending={rollback.isPending}
                  onRollback={() => version.commitHash && rollback.mutate(version.commitHash)}
                />
              ))}
            </ul>
          )}
        </div>
      </SheetContent>
    </Sheet>
  );
}

/**
 * 时间线单条：版本号＋当前/已确认标记＋时间＋用户那句话＋预览入口＋回到此版。
 * 「回到此版」对当前版也不禁用（#627）：回滚失败后「再点一次最新版的回到此版
 * 回到最新版」是恢复路之一，故当前版必须可点。
 */
function VersionTimelineRow({
  version,
  previewUrl,
  onOpenPreview,
  rollbackPending,
  onRollback,
}: {
  version: GitVersion;
  previewUrl: string | undefined;
  onOpenPreview: () => void;
  rollbackPending: boolean;
  onRollback: () => void;
}) {
  const label = version.subject?.trim() || "（无说明）";
  const versionText = version.versionNo != null ? `v${version.versionNo}` : "版本";
  const time = formatRelativeTime(version.committedAt);
  return (
    <li>
      <div className="flex flex-col gap-2 rounded-xl border border-slate-soft/60 bg-surface-dawn/50 p-3">
        <div className="flex min-w-0 flex-wrap items-center gap-2">
          <span className="font-mono text-sm font-semibold text-ink-primary">{versionText}</span>
          {version.current ? <Badge variant="secondary">当前</Badge> : null}
          {version.confirmed ? (
            <Badge variant="outline" className="border-emerald-active/40 text-emerald-active">
              已确认
            </Badge>
          ) : null}
          {time ? <span className="ml-auto shrink-0 text-xs text-ink-faint">{time}</span> : null}
        </div>
        <p className="break-words text-body-sm text-ink-secondary">{label}</p>
        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            onClick={onOpenPreview}
            disabled={!previewUrl}
            aria-label={`打开当前预览（${versionText}）`}
            className="inline-flex items-center gap-1.5 rounded-lg border border-slate-soft/80 bg-white px-2.5 py-1 text-label-sm font-medium text-ink-secondary shadow-xs transition-colors hover:bg-surface-dawn disabled:pointer-events-none disabled:opacity-45"
          >
            <ExternalLink className="size-3.5" aria-hidden="true" />
            打开预览
          </button>
          <button
            type="button"
            onClick={onRollback}
            disabled={rollbackPending || !version.commitHash}
            aria-label={`回到 ${versionText}`}
            className="inline-flex items-center gap-1.5 rounded-lg border border-slate-soft/80 bg-white px-2.5 py-1 text-label-sm font-medium text-ink-secondary shadow-xs transition-colors hover:bg-surface-dawn disabled:pointer-events-none disabled:opacity-45"
          >
            <RotateCcw className="size-3.5" aria-hidden="true" />
            回到此版
          </button>
        </div>
      </div>
    </li>
  );
}
