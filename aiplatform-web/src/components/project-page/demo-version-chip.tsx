"use client";

import { Bookmark } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { useDemoVersions } from "@/hooks/use-demo-version";
import { useVersionRecordStore } from "@/lib/store/version-record";

/**
 * Demo 版本 chip（#333 最小呈现，#344 起可点开版本记录抽屉——完整 Demo 横幅
 * 归 #347 迁移后同接抽屉）：Demo/预览面浏览器条挂「Demo vN · 基于 PRD vM
 * 快照」——收口自动成版的最新版标识＋起跑时 PRD 确认版本锚（无确认版起跑
 * 〔legacy/设计类〕只出版本号，不虚构关联）。最新版带确认标记时缀「已确认」
 * （#335——确认态在版本可见处可见；完整确认卡归 #347）。从未成版（空列表/
 * 加载中）不出 chip——诚实优先于占位。确认标记态经阶段门（stageGateFactsOf）
 * 消费。点击＝版本记录抽屉的第二挂点（与顶栏「版本记录」同一抽屉，#344）。
 */
export function DemoVersionChip({
  projectId,
  variant = "default",
}: {
  projectId: string;
  /** banner＝Demo 页项目横幅的原型芯片形；default＝旧浏览器条紧凑徽章。 */
  variant?: "default" | "banner";
}) {
  const { data: versions } = useDemoVersions(projectId);
  const openVersionRecord = useVersionRecordStore((s) => s.openVersionRecord);
  const latest = versions?.[0];
  if (!latest) return null;
  const ariaLabel = `查看版本记录（当前 ${["Demo v" + latest.versionNo, latest.prdVersionNo != null ? `基于 PRD v${latest.prdVersionNo} 快照` : null, latest.confirmedAt != null ? "已确认" : null].filter(Boolean).join(" · ")}）`;
  if (variant === "banner") {
    return (
      <button
        type="button"
        onClick={openVersionRecord}
        title="查看版本记录"
        aria-label={ariaLabel}
        className="flex shrink-0 items-center gap-1.5 rounded-lg border border-slate-soft/80 bg-white px-2.5 py-1 shadow-xs outline-none transition-colors hover:bg-surface-dawn focus-visible:ring-2 focus-visible:ring-ring/50"
      >
        <Bookmark className="size-3.5 text-electric-indigo" aria-hidden="true" />
        <span className="font-mono text-code-sm font-semibold text-ink-secondary">
          Demo v{latest.versionNo}
        </span>
        {latest.prdVersionNo != null ? (
          <span className="text-label-sm text-ink-faint">(基于 PRD v{latest.prdVersionNo} 快照)</span>
        ) : null}
        {latest.confirmedAt != null ? (
          <span className="text-label-sm font-medium text-success-deep">已确认</span>
        ) : null}
      </button>
    );
  }
  return (
    <button
      type="button"
      onClick={openVersionRecord}
      title="查看版本记录"
      aria-label={ariaLabel}
      className="shrink-0 rounded-full outline-none transition-transform focus-visible:ring-2 focus-visible:ring-ring/50 active:scale-95"
    >
      <Badge variant="outline" className="gap-1 font-normal text-muted-foreground">
        Demo v{latest.versionNo}
        {latest.prdVersionNo != null ? ` · 基于 PRD v${latest.prdVersionNo} 快照` : null}
        {latest.confirmedAt != null ? " · 已确认" : null}
      </Badge>
    </button>
  );
}
