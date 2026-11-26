"use client";

import { Bookmark } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { useGitVersions } from "@/hooks/use-version";
import { useVersionRecordStore } from "@/lib/store/version-record";

/**
 * 版本 chip（#333 最小呈现／#344 点开抽屉／#626 改接单轴时间线）：Demo 页项目
 * 横幅与浏览器条挂「版本 vN」——最新用户面版本号（每轮成功生成探活通过后自动
 * 成版）＋确认标记（已确认用于签约时缀「已确认」）。从未成版（空列表/加载中）
 * 不出 chip——诚实优先于占位。点击＝打开单轴版本时间线（与顶栏「版本记录」同一
 * 入口；旧双轴 PRD/Demo 版本组不再呈现）。
 */
export function VersionChip({
  projectId,
  variant = "default",
}: {
  projectId: string;
  /** banner＝Demo 页项目横幅的原型芯片形；default＝浏览器条紧凑徽章。 */
  variant?: "default" | "banner";
}) {
  const { data: versions } = useGitVersions(projectId);
  const openVersionRecord = useVersionRecordStore((s) => s.openVersionRecord);
  const latest = versions?.[0];
  if (!latest) return null;
  const versionText = latest.versionNo != null ? `v${latest.versionNo}` : "版本";
  const ariaLabel = `查看版本（当前 ${[versionText, latest.confirmed ? "已确认" : null].filter(Boolean).join(" · ")}）`;
  if (variant === "banner") {
    return (
      <button
        type="button"
        onClick={openVersionRecord}
        title="查看版本"
        aria-label={ariaLabel}
        className="flex shrink-0 items-center gap-1.5 rounded-lg border border-slate-soft/80 bg-white px-2.5 py-1 shadow-xs outline-none transition-colors hover:bg-surface-dawn focus-visible:ring-2 focus-visible:ring-ring/50"
      >
        <Bookmark className="size-3.5 text-electric-indigo" aria-hidden="true" />
        <span className="font-mono text-code-sm font-semibold text-ink-secondary">
          版本 {versionText}
        </span>
        {latest.confirmed ? (
          <span className="text-label-sm font-medium text-success-deep">已确认</span>
        ) : null}
      </button>
    );
  }
  return (
    <button
      type="button"
      onClick={openVersionRecord}
      title="查看版本"
      aria-label={ariaLabel}
      className="shrink-0 rounded-full outline-none transition-transform focus-visible:ring-2 focus-visible:ring-ring/50 active:scale-95"
    >
      <Badge variant="outline" className="gap-1 font-normal text-muted-foreground">
        版本 {versionText}
        {latest.confirmed ? " · 已确认" : null}
      </Badge>
    </button>
  );
}

/**
 * 兼容旧名（Demo 版本 chip 的现名已统一为「版本」）：内部调用点不改名，语义
 * 随 #626 单轴时间线顺延。新代码请用 {@link VersionChip}。
 */
export const DemoVersionChip = VersionChip;
