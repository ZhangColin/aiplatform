"use client";

import { ChevronRight, FolderCog, Terminal } from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";

import { BrandHeader } from "@/components/brand/brand-header";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import {
  activeStageOf,
  type DeliveryStageKey,
  type StageNavItem,
} from "@/lib/projects/delivery-stages";
import { cn } from "@/lib/utils";

/**
 * 阶段侧栏（#343，spec #320 ⑥ 全局壳重建，结构/话术照原型）：品牌头（回
 * 首页——新建项目的去向）＋「交付生命周期阶段」组（四阶段：序号/完成 ✓/
 * 当前脉冲/未满足条件灰置＋点击说明原因）＋「工作空间」组（我的交付项目/
 * 系统架构与规约——装饰入口照留、点击无落地）＋底部「调度引擎状态」卡
 * （GRAVITY-CORE v4.1＋4/5 进度条，静态照原型）。可达性判定输入
 * （{@link StageNavItem}）由壳装配层单点推导（deliveryStageNav），侧栏只
 * 呈现；个人菜单迁顶栏头像、能做这些已删（#318 拆解）。
 */

export function StageSidebar({
  projectId,
  nav,
  currentStage,
}: {
  projectId: string;
  nav: StageNavItem[];
  /** 当前阶段（首个未完成——脉冲位）。 */
  currentStage: DeliveryStageKey;
}) {
  const pathname = usePathname();
  const activeStage = activeStageOf(projectId, pathname);

  return (
    <aside className="fixed left-0 top-0 z-50 hidden h-full w-64 flex-col justify-between border-r border-slate-soft/80 bg-glass-card backdrop-blur-md lg:flex shadow-e1">
      <div className="flex min-h-0 flex-col overflow-y-auto">
        {/* 品牌头＝回首页（新建项目的去向） */}
        <Link
          href="/"
          aria-label="回首页"
          className="flex h-16 shrink-0 items-center border-b border-slate-soft/60 px-5"
        >
          <BrandHeader />
        </Link>

        <div className="px-5 pb-2 pt-5">
          <span className="text-label-sm font-semibold uppercase tracking-wider text-ink-faint">
            交付生命周期阶段
          </span>
        </div>
        <nav className="flex flex-col gap-1 px-3" aria-label="交付生命周期阶段">
          {nav.map((item) => (
            <StageRow
              key={item.key}
              item={item}
              active={activeStage === item.key}
              current={currentStage === item.key}
            />
          ))}
        </nav>

        <div className="px-5 pb-2 pt-6">
          <span className="text-label-sm font-semibold uppercase tracking-wider text-ink-faint">
            工作空间
          </span>
        </div>
        <nav className="flex flex-col gap-1 px-3" aria-label="工作空间">
          <Link
            href="/projects"
            className="flex items-center gap-2.5 rounded-xl px-3.5 py-2 text-label-md font-medium text-ink-muted transition-colors hover:bg-surface-dawn/70 hover:text-ink-primary"
          >
            <FolderCog className="size-4.5 text-ink-faint" aria-hidden="true" />
            <span>我的交付项目</span>
          </Link>
          {/* 装饰入口（原型照留）：点击无落地，形实施期定（#318）——非控件，
              不占 disable 语义位（aria-disabled 只属灰置阶段行） */}
          <span className="flex cursor-default select-none items-center gap-2.5 rounded-xl px-3.5 py-2 text-label-md font-medium text-ink-faint">
            <Terminal className="size-4.5 text-ink-faint" aria-hidden="true" />
            <span>系统架构与规约</span>
          </span>
        </nav>
      </div>

      {/* 调度引擎状态卡（静态照原型） */}
      <div className="border-t border-slate-soft/60 p-4">
        <div className="flex flex-col gap-2 rounded-xl border border-slate-soft/60 bg-linear-to-br from-surface-dawn to-surface-violet-tint/40 p-3">
          <div className="flex items-center justify-between">
            <span className="text-label-sm font-medium text-ink-muted">调度引擎状态</span>
            <span className="flex items-center gap-1.5">
              <span className="relative flex size-2">
                <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-emerald-active opacity-75" />
                <span className="relative inline-flex size-2 rounded-full bg-emerald-active" />
              </span>
              <span className="font-mono text-code-sm font-semibold text-emerald-active">
                ONLINE
              </span>
            </span>
          </div>
          <div className="flex items-baseline justify-between">
            <span className="font-mono text-code-md font-semibold tracking-tight text-ink-primary">
              GRAVITY-CORE
            </span>
            <span className="font-mono text-code-sm font-medium text-tech-blue">v4.1</span>
          </div>
          <div className="h-1.5 w-full overflow-hidden rounded-full bg-slate-soft/80">
            <div className="h-full w-4/5 rounded-full bg-linear-to-r from-electric-indigo to-mint-teal" />
          </div>
        </div>
      </div>
    </aside>
  );
}

/** 单阶段行：可达＝链接；灰置＝按钮＋点击说明原因（交接包「未满足条件的步骤要说明原因」）。 */
function StageRow({
  item,
  active,
  current,
}: {
  item: StageNavItem;
  active: boolean;
  current: boolean;
}) {
  // 当前阶段脉冲位（原型 ai_2 形：脉冲点替代 chevron）
  const tail = current ? (
    <span
      className={cn(
        "size-2 shrink-0 animate-pulse rounded-full",
        active ? "bg-white" : "bg-electric-indigo",
      )}
      aria-hidden="true"
    />
  ) : (
    <ChevronRight className="size-4 shrink-0 text-ink-faint/60" aria-hidden="true" />
  );

  const content = (
    <>
      <span className="flex min-w-0 items-center gap-2.5">
        <span
          className={cn(
            "flex size-5 shrink-0 items-center justify-center rounded-full font-mono text-code-sm font-semibold",
            item.completed
              ? "border border-emerald-active/40 bg-surface-mist text-emerald-active"
              : active
                ? "bg-white/25 text-white"
                : item.locked
                  ? "bg-surface-dawn text-ink-faint"
                  : "bg-surface-dawn text-ink-muted",
          )}
          aria-hidden="true"
        >
          {item.completed ? "✓" : item.seq}
        </span>
        <span
          className={cn(
            "truncate text-label-md tracking-tight",
            active && "font-semibold text-white",
            !active && item.locked && "text-ink-faint",
            !active && !item.locked && "font-medium text-ink-muted",
          )}
        >
          {item.label}
        </span>
      </span>
      {tail}
    </>
  );

  const rowClass = cn(
    "group flex w-full items-center justify-between rounded-xl px-3.5 py-2.5 transition-all",
    active
      ? "bg-linear-to-r from-electric-indigo to-tech-blue font-medium text-white shadow-e1"
      : item.locked
        ? "text-ink-faint"
        : "text-ink-muted hover:bg-surface-dawn/70 hover:text-ink-primary",
  );

  if (item.locked) {
    return (
      <Popover>
        <PopoverTrigger
          type="button"
          aria-disabled="true"
          className={cn(rowClass, "cursor-not-allowed")}
        >
          {content}
        </PopoverTrigger>
        <PopoverContent side="right" align="start" className="w-64">
          <p className="text-body-sm text-ink-secondary">{item.lockReason}</p>
        </PopoverContent>
      </Popover>
    );
  }

  return (
    <Link href={item.href} aria-current={active ? "page" : undefined} className={rowClass}>
      {content}
    </Link>
  );
}
