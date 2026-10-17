"use client";

import Link from "next/link";

import { BrandLogo } from "@/components/brand/brand-logo";
import { OsMark } from "@/components/brand/os-mark";
import {
  SaveProjectButton,
  SaveStatusPill,
  TopbarAvatar,
} from "@/components/shell/global-topbar";
import { useRecentProjects } from "@/hooks/use-projects";
import { SITE_NAME } from "@/lib/brand";
import { DELIVERY_STAGES, stageHrefOf } from "@/lib/projects/delivery-stages";
import { recentProjects } from "@/lib/projects/list";

/**
 * 首页顶栏（#345，spec #320 ⑥「首页无侧栏、顶栏照首页形」）：品牌位（圆底 logo
 * ＋「语义引力」＋ OS 徽标＋「Semantic Gravity · Delivery OS」副行，照原型首页
 * 顶栏，与侧栏品牌头 DELIVERY PIPELINE 形分属两处）＋五行导航（首页＋四阶段）＋
 * 保存状态胶囊/「我的项目」/「保存项目」/头像（访客＝「登录」文字入口，与项目
 * 空间顶栏 #343 同源件复用）。首页无项目上下文：阶段导航指向最近项目的阶段页
 * （有则指过去，无则回首页）。
 */
export function HomeHeader() {
  const recent = recentProjects(useRecentProjects(), 1)[0];

  return (
    <header className="fixed top-0 right-0 left-0 z-40 flex h-16 items-center justify-between gap-4 border-b border-slate-soft/80 bg-glass-card px-4 shadow-e1 backdrop-blur-md lg:px-8">
      <div className="flex min-w-0 items-center gap-6">
        {/* 品牌位（照原型首页顶栏：圆底 logo + 站名 + OS 徽标 + 英文副行） */}
        <Link href="/" aria-label={SITE_NAME} className="flex shrink-0 items-center gap-2.5">
          <div className="flex size-10 items-center justify-center overflow-hidden rounded-xl border border-indigo-100 bg-gradient-to-tr from-indigo-500/10 via-cyan-500/15 to-blue-500/10 p-1.5 shadow-sm">
            <BrandLogo className="size-7" />
          </div>
          <div className="flex min-w-0 flex-col leading-tight">
            <div className="flex items-center gap-1">
              <span className="truncate text-headline-sm font-semibold tracking-tight text-ink-primary">
                {SITE_NAME}
              </span>
              <OsMark />
            </div>
            <span className="truncate font-mono text-[11px] font-medium tracking-wider text-ink-faint uppercase">
              Semantic Gravity · Delivery OS
            </span>
          </div>
        </Link>

        {/* 五行导航（与侧栏重复亦保留，原型如此设计） */}
        <nav
          className="hidden min-w-0 items-center gap-0.5 overflow-x-auto rounded-xl border border-slate-soft/60 bg-surface-dawn/80 p-1 xl:flex"
          aria-label="全局导航"
        >
          <Link
            href="/"
            aria-current="page"
            className="rounded-lg bg-white px-3 py-1.5 text-label-md font-semibold text-electric-indigo shadow-e1"
          >
            首页
          </Link>
          {DELIVERY_STAGES.map((stage) => (
            <Link
              key={stage.key}
              href={recent ? stageHrefOf(recent.id, stage) : "/"}
              className="rounded-lg px-3 py-1.5 text-label-md font-medium text-ink-muted transition-colors hover:bg-white/60 hover:text-ink-primary"
            >
              {stage.label}
            </Link>
          ))}
        </nav>
      </div>

      {/* 右侧状态与动作（与项目空间顶栏同源件） */}
      <div className="flex shrink-0 items-center gap-3">
        <SaveStatusPill />
        <Link
          href="/projects"
          className="hidden px-2 py-1.5 text-label-md font-medium text-ink-muted transition-colors hover:text-ink-primary md:block"
        >
          我的项目
        </Link>
        <SaveProjectButton />
        <TopbarAvatar />
      </div>
    </header>
  );
}
