"use client";

import { ArrowRight, MessagesSquare } from "lucide-react";
import Link from "next/link";

import { Skeleton } from "@/components/ui/skeleton";
import { cn } from "@/lib/utils";
import { prdBadgeText, type PrdBadge } from "@/lib/projects/prd-badge";

import { PrdExportMenu } from "./prd-export-menu";

/**
 * 需求分析页粘性二级 header（#346，spec #320 ⑥ 照原型）：代号胶囊（真实项目
 * 标识＋STAGE-01——原型「SG-PRJ-20261015-0881」是虚构演示数据，胶囊位照留、
 * 内容用真实项目 id）＋项目名＋PRD 版本徽标（推导归 lib/projects/prd-badge
 * 单点）＋「对话调优」（聚焦澄清输入）＋「导出 (PDF/Word)」＋主 CTA。
 *
 * <p>主 CTA（#462 去确认门，ADR-0039）：草稿/新草稿态不再出确认门——PRD 产出
 * 即自动进入生成（首次 savePrd 触发首个构建 run，版本徽标随构建基准序翻转）；
 * 首个构建基准记行后（徽标 confirmed）＝「前往 Demo 体验」阶段推进位（链 Demo
 * 阶段页）。无 PRD 不出 CTA（PRD 在场才出门）。</p>
 */
export function RequirementAnalysisHeader({
  projectId,
  projectName,
  badge,
  hasPrd,
  onFocusChat,
  getArticleHtml,
  loading = false,
}: {
  projectId: string;
  projectName: string;
  /** PRD 版本徽标（null＝无 PRD 不出徽标）。 */
  badge: PrdBadge | null;
  /** PRD 产出在否（导出的呈现判据）。 */
  hasPrd: boolean;
  /** 「对话调优」：聚焦澄清输入（并滚动到位）。 */
  onFocusChat: () => void;
  /** 导出取件面（PRD 正文渲染 HTML）。 */
  getArticleHtml: () => string | null;
  /** 项目详情加载中（项目名位出骨架）。 */
  loading?: boolean;
}) {
  const confirmed = hasPrd && badge?.kind === "confirmed";

  return (
    <header className="sticky top-16 z-30 flex shrink-0 flex-col justify-between gap-4 border-b border-slate-soft/70 bg-glass-card px-4 py-4 backdrop-blur-md shadow-xs md:flex-row md:items-center lg:px-8">
      <div className="flex min-w-0 flex-wrap items-center gap-4">
        {/* 代号胶囊（真实项目标识＋阶段位） */}
        <div className="flex items-center gap-1.5 rounded-lg border border-slate-soft/60 bg-surface-dawn/70 px-2.5 py-1 font-mono text-code-md text-ink-muted">
          <span className="max-w-40 truncate font-medium text-ink-primary" title={projectId}>
            {projectId}
          </span>
          <span className="text-ink-faint/60">/</span>
          <span className="font-semibold text-electric-indigo">STAGE-01</span>
        </div>
        <div className="hidden h-4 w-px bg-slate-soft sm:block" aria-hidden="true" />
        <div className="flex min-w-0 items-center gap-3">
          {loading ? (
            <Skeleton className="h-6 w-40" />
          ) : (
            <h1 className="truncate text-[18px] font-bold tracking-tight text-ink-primary">
              {projectName || "未命名项目"}
            </h1>
          )}
          {badge ? (
            <span
              className={cn(
                "shrink-0 rounded-full border px-2.5 py-0.5 text-label-sm font-semibold",
                badge.kind === "confirmed"
                  ? "border-emerald-active/40 bg-emerald-active/10 text-success-deep"
                  : "border-electric-indigo/30 bg-surface-violet-tint text-electric-indigo",
              )}
            >
              {prdBadgeText(badge)}
            </span>
          ) : null}
        </div>
      </div>

      {/* 动作行：窄屏可换行（#350——对话调优/导出/CTA 合计超 390px，
          不换行即横向出血）；≥md 恢复单行收缩 */}
      <div className="flex shrink-0 flex-wrap items-center gap-2.5 md:flex-nowrap">
        <button
          type="button"
          onClick={onFocusChat}
          className="flex items-center gap-1.5 rounded-xl border border-slate-soft/80 bg-white px-3.5 py-2 text-label-md font-medium text-ink-muted shadow-xs transition-colors hover:bg-surface-dawn/60 hover:text-ink-primary"
        >
          <MessagesSquare className="size-4 text-ink-faint" aria-hidden="true" />
          对话调优
        </button>
        <PrdExportMenu
          projectId={projectId}
          disabled={!hasPrd}
          fileBaseName={`${projectName || "未命名项目"}-PRD`}
          getArticleHtml={getArticleHtml}
        />
        {confirmed ? (
          <Link
            href={`/projects/${projectId}/demo`}
            className="flex items-center gap-2 rounded-xl bg-linear-to-r from-electric-indigo to-tech-blue px-5 py-2 text-label-md font-semibold text-white shadow-md transition-all hover:brightness-105 active:scale-[0.99]"
          >
            前往 Demo 体验
            <ArrowRight className="size-4.5" aria-hidden="true" />
          </Link>
        ) : null}
      </div>
    </header>
  );
}
