"use client";

import { Save } from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";

import { AccountMenu } from "@/components/account-menu";
import { Button } from "@/components/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { useMe } from "@/hooks/use-me";
import { buildLoginRedirectUrl } from "@/lib/auth/login-redirect";
import {
  activeStageOf,
  type StageNavItem,
} from "@/lib/projects/delivery-stages";
import { useM01Store } from "@/lib/store/m01";
import { useSaveStateStore } from "@/lib/store/save-state";
import { cn } from "@/lib/utils";

/**
 * 全局顶栏（#343，spec #320 ⑥ 全局壳重建，照原型全量）：五行导航（首页＋
 * 四阶段——与侧栏重复亦保留，原型如此设计）＋保存状态胶囊（真实三态）＋
 * 「我的项目」＋「保存项目」（触发 M01）＋头像（访客态＝「登录」文字入口；
 * 个人菜单自旧侧栏迁入）。可达性判定输入与侧栏同源（壳装配层单点推导）。
 */

export function GlobalTopbar({ projectId, nav }: { projectId: string; nav: StageNavItem[] }) {
  const pathname = usePathname();
  const activeStage = activeStageOf(projectId, pathname);

  return (
    <header className="fixed top-0 right-0 left-0 z-40 flex h-16 items-center justify-between gap-4 border-b border-slate-soft/80 bg-glass-card px-4 backdrop-blur-md shadow-e1 lg:left-64 lg:px-8">
      {/* 五行导航（照原型：与侧栏重复亦保留） */}
      <nav
        className="flex min-w-0 items-center gap-0.5 overflow-x-auto rounded-xl border border-slate-soft/60 bg-surface-dawn/80 p-1"
        aria-label="全局导航"
      >
        <Link
          href="/"
          aria-current={pathname === "/" ? "page" : undefined}
          className="rounded-lg px-3 py-1.5 text-label-md font-medium text-ink-muted transition-colors hover:bg-white/60 hover:text-ink-primary"
        >
          首页
        </Link>
        {nav.map((item) =>
          item.locked ? (
            <Popover key={item.key}>
              <PopoverTrigger
                type="button"
                aria-disabled="true"
                className="cursor-not-allowed rounded-lg px-3 py-1.5 text-label-md font-medium text-ink-faint"
              >
                {item.label}
              </PopoverTrigger>
              <PopoverContent side="bottom" className="w-64">
                <p className="text-body-sm text-ink-secondary">{item.lockReason}</p>
              </PopoverContent>
            </Popover>
          ) : (
            <Link
              key={item.key}
              href={item.href}
              aria-current={activeStage === item.key ? "page" : undefined}
              className={cn(
                "rounded-lg px-3 py-1.5 text-label-md transition-colors",
                activeStage === item.key
                  ? "bg-white font-semibold text-electric-indigo shadow-e1"
                  : "font-medium text-ink-muted hover:bg-white/60 hover:text-ink-primary",
              )}
            >
              {item.label}
            </Link>
          ),
        )}
      </nav>

      {/* 右侧状态与动作 */}
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

/** 保存状态胶囊（#316 三态话术；已保存态文案逐字照原型）。导出＝首页顶栏（#345）同源复用。 */
export function SaveStatusPill() {
  const state = useSaveStateStore((s) => s.state);
  if (state === "saving") {
    return (
      <span className="hidden items-center gap-1.5 rounded-full border border-slate-soft/60 bg-surface-dawn/80 px-3.5 py-1.5 lg:inline-flex">
        <span className="size-2 animate-pulse rounded-full bg-ink-faint" aria-hidden="true" />
        <span className="text-label-sm text-ink-muted">保存中…</span>
      </span>
    );
  }
  if (state === "failed") {
    return (
      <span className="hidden items-center gap-1.5 rounded-full border border-error/30 bg-error/10 px-3.5 py-1.5 lg:inline-flex">
        <span className="size-2 shrink-0 rounded-full bg-error" aria-hidden="true" />
        <span className="text-label-sm font-medium text-error">保存失败，请重试</span>
      </span>
    );
  }
  return (
    <span className="hidden items-center gap-1.5 rounded-full border border-emerald-active/30 bg-surface-mist/80 px-3.5 py-1.5 lg:inline-flex">
      <span className="size-2 animate-pulse rounded-full bg-emerald-active" aria-hidden="true" />
      <span className="text-label-sm font-medium text-success-deep">
        浏览器已自动保存 · 绑定可跨设备
      </span>
    </span>
  );
}

/** 「保存项目」（照原型）——M01 登录与项目绑定弹窗的触发挂点（#327/#328 消费）。 */
export function SaveProjectButton() {
  const openM01 = useM01Store((s) => s.openM01);
  return (
    <Button
      type="button"
      variant="outline"
      size="sm"
      className="h-8 gap-1.5 rounded-lg text-label-md font-medium"
      onClick={() => openM01("save-project")}
    >
      <Save className="size-4 text-ink-faint" aria-hidden="true" />
      保存项目
    </Button>
  );
}

/** 头像位：已登录＝个人菜单（旧侧栏迁入）；访客＝「登录」文字入口。导出＝首页顶栏（#345）同源复用。 */
export function TopbarAvatar() {
  const { data: me, isPending } = useMe();
  const pathname = usePathname();

  if (isPending) {
    // 会话探明前占位（不出「登录」闪烁——登录墙下静候既有会话）
    return (
      <span
        aria-hidden="true"
        className="size-8 shrink-0 rounded-full bg-surface-dawn"
      />
    );
  }
  if (!me) {
    return (
      <Link
        href={buildLoginRedirectUrl(pathname, "")}
        className="rounded-lg px-2.5 py-1.5 text-label-md font-medium text-electric-indigo transition-colors hover:bg-surface-violet-tint"
      >
        登录
      </Link>
    );
  }
  return (
    <div className="w-9 shrink-0">
      <AccountMenu collapsed />
    </div>
  );
}
