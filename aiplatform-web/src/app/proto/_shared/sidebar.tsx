"use client";

/**
 * 原型共享：可展开/收起的左侧栏（首页默认展开 ↔ 项目页默认收成 icon rail）。
 * 收起态全图标 + Tooltip；展开态 = 导航 + 「能做这些」+ 历史项目 + 账号。
 * 宽度过渡用 CSS transition（motivated motion：状态切换反馈）。
 */

import * as React from "react";
import Link from "next/link";
import { useTheme } from "next-themes";
import {
  Check,
  ChevronsUpDown,
  Home,
  LogOut,
  Monitor,
  Moon,
  PanelLeftClose,
  PanelLeftOpen,
  Plus,
  Sparkles,
  Sun,
} from "lucide-react";

import { Button } from "@/components/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Separator } from "@/components/ui/separator";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";

import { Brand } from "./chat-parts";

export type ProtoProject = { id: string; name: string; stage: string; updated: string; tint: string };

export const PROTO_PROJECTS: ProtoProject[] = [
  { id: "flower", name: "巷口花店小程序", stage: "迭代中", updated: "2 小时前", tint: "bg-rose-100 text-rose-700 dark:bg-rose-950 dark:text-rose-300" },
  { id: "groupon", name: "社区团购站", stage: "访谈中", updated: "昨天", tint: "bg-amber-100 text-amber-700 dark:bg-amber-950 dark:text-amber-300" },
  { id: "yoga", name: "瑜伽馆预约", stage: "已发布", updated: "3 天前", tint: "bg-emerald-100 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300" },
];

/** 项目头像：首字色块（比 emoji 更产品化）。 */
export function ProjectAvatar({ name, tint, className }: { name: string; tint: string; className?: string }) {
  return (
    <span className={cn("flex size-7 shrink-0 items-center justify-center rounded-lg text-xs font-bold", tint, className)}>
      {name.slice(0, 1)}
    </span>
  );
}

const MODES = [
  { label: "做系统", live: true },
  { label: "做页面", live: false },
  { label: "写文档", live: false },
];

export function ProtoSidebar({
  defaultCollapsed = false,
  active,
  projectHref,
  homeHref,
}: {
  defaultCollapsed?: boolean;
  /** 当前所在面：首页 or 某项目。 */
  active: "home" | "project";
  projectHref: (id: string) => string;
  homeHref: string;
}) {
  const [collapsed, setCollapsed] = React.useState(defaultCollapsed);

  return (
    <aside
      className={cn(
        "flex shrink-0 flex-col border-r bg-muted/30 transition-[width] duration-200 ease-out",
        collapsed ? "w-14 items-center py-3" : "w-60 p-2.5",
      )}
    >
      {collapsed ? (
        <RailContent active={active} onExpand={() => setCollapsed(false)} homeHref={homeHref} projectHref={projectHref} />
      ) : (
        <WideContent active={active} onCollapse={() => setCollapsed(true)} homeHref={homeHref} projectHref={projectHref} />
      )}
    </aside>
  );
}

function RailContent({
  active,
  onExpand,
  homeHref,
  projectHref,
}: {
  active: "home" | "project";
  onExpand: () => void;
  homeHref: string;
  projectHref: (id: string) => string;
}) {
  return (
    <>
      <span className="flex size-7 items-center justify-center rounded-md bg-primary text-xs font-bold text-primary-foreground">
        AI
      </span>
      <RailButton label="展开侧栏" onClick={onExpand}><PanelLeftOpen className="size-4" /></RailButton>
      <RailButton label="新建项目"><Plus className="size-4" /></RailButton>
      <RailLink label="首页" href={homeHref} active={active === "home"}><Home className="size-4" /></RailLink>
      <div className="my-1 h-px w-6 bg-border" />
      {PROTO_PROJECTS.map((p) => (
        <RailLink key={p.id} label={p.name} href={projectHref(p.id)} active={active === "project" && p.id === "flower"}>
          <ProjectAvatar name={p.name} tint={p.tint} className="size-6 rounded-md text-[11px]" />
        </RailLink>
      ))}
      <div className="mt-auto">
        <UserMenu collapsed />
      </div>
    </>
  );
}

function RailButton({ label, children, onClick }: { label: string; children: React.ReactNode; onClick?: () => void }) {
  return (
    <Tooltip>
      <TooltipTrigger
        className="mt-2 flex items-center justify-center rounded-lg p-2 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground active:scale-95"
        onClick={onClick}
        aria-label={label}
      >
        {children}
      </TooltipTrigger>
      <TooltipContent side="right">{label}</TooltipContent>
    </Tooltip>
  );
}

function RailLink({ label, href, active, children }: { label: string; href: string; active?: boolean; children: React.ReactNode }) {
  return (
    <Tooltip>
      <TooltipTrigger
        className={cn(
          "mt-2 flex items-center justify-center rounded-lg p-1.5 transition-colors active:scale-95",
          active ? "bg-muted text-foreground" : "text-muted-foreground hover:bg-muted hover:text-foreground",
        )}
        aria-label={label}
        render={<Link href={href} />}
      >
        {children}
      </TooltipTrigger>
      <TooltipContent side="right">{label}</TooltipContent>
    </Tooltip>
  );
}

function WideContent({
  active,
  onCollapse,
  homeHref,
  projectHref,
}: {
  active: "home" | "project";
  onCollapse: () => void;
  homeHref: string;
  projectHref: (id: string) => string;
}) {
  return (
    <>
      <div className="flex items-center px-1.5 py-1">
        <Brand />
        <button
          className="ml-auto rounded-md p-1 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
          onClick={onCollapse}
          aria-label="收起侧栏"
        >
          <PanelLeftClose className="size-4" />
        </button>
      </div>
      <Button className="mt-2.5 w-full justify-start transition-transform active:scale-[0.98]" size="sm">
        <Plus className="size-4" /> 新建项目
      </Button>
      <Link
        href={homeHref}
        className={cn(
          "mt-1 flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-sm transition-colors",
          active === "home" ? "bg-muted font-medium" : "text-muted-foreground hover:bg-muted/60",
        )}
      >
        <Home className="size-4" /> 首页
      </Link>
      <div className="mt-4 px-2 text-[13px] font-medium text-muted-foreground">能做这些</div>
      {MODES.map((m) => (
        <button
          key={m.label}
          className={cn(
            "mt-0.5 flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm transition-colors",
            m.live ? "bg-muted font-medium" : "text-muted-foreground hover:bg-muted/60",
          )}
        >
          <Sparkles className="size-3.5" /> {m.label}
          {!m.live && <span className="ml-auto text-xs text-muted-foreground/60">敬请期待</span>}
        </button>
      ))}
      <div className="mt-4 px-2 text-[13px] font-medium text-muted-foreground">历史项目</div>
      {PROTO_PROJECTS.map((p) => (
        <Link
          key={p.id}
          href={projectHref(p.id)}
          className={cn(
            "group mt-0.5 flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm transition-colors",
            active === "project" && p.id === "flower" ? "bg-muted font-medium" : "text-muted-foreground hover:bg-muted/60",
          )}
        >
          <ProjectAvatar name={p.name} tint={p.tint} className="size-6 rounded-md text-[11px]" />
          <span className="min-w-0 flex-1 truncate">{p.name}</span>
          <span className="text-xs text-muted-foreground/60 opacity-0 transition-opacity group-hover:opacity-100">{p.updated}</span>
        </Link>
      ))}
      <div className="mt-auto">
        <UserMenu />
      </div>
    </>
  );
}

/** 左下角个人菜单（Lovable 式浮出）：账户信息 + 主题（浅色/深色/跟随系统）+ 退出登录。 */
function UserMenu({ collapsed = false }: { collapsed?: boolean }) {
  const { theme, setTheme } = useTheme();
  /* next-themes 挂载守卫（useSyncExternalStore 写法，避开 setState-in-effect） */
  const mounted = React.useSyncExternalStore(() => () => {}, () => true, () => false);
  const THEMES = [
    { value: "light", label: "浅色", icon: <Sun className="size-3.5" /> },
    { value: "dark", label: "深色", icon: <Moon className="size-3.5" /> },
    { value: "system", label: "跟随系统", icon: <Monitor className="size-3.5" /> },
  ];
  return (
    <Popover>
      <PopoverTrigger
        className={cn(
          "flex w-full items-center gap-2 rounded-md text-sm text-muted-foreground transition-colors hover:bg-muted/60 hover:text-foreground",
          collapsed ? "mt-2 justify-center p-1.5" : "px-2 py-1.5",
        )}
      >
        <span className="flex size-7 shrink-0 items-center justify-center rounded-full bg-foreground text-[11px] font-bold text-background">N</span>
        {!collapsed ? (
          <>
            <span className="min-w-0 flex-1 truncate text-left">我的账号</span>
            <ChevronsUpDown className="size-3.5 shrink-0 text-muted-foreground/60" />
          </>
        ) : null}
      </PopoverTrigger>
      <PopoverContent side={collapsed ? "right" : "top"} align="start" className="w-60 p-1.5">
        <div className="flex items-center gap-2.5 px-2 py-2">
          <span className="flex size-9 items-center justify-center rounded-full bg-foreground text-xs font-bold text-background">N</span>
          <div className="min-w-0">
            <div className="truncate text-sm font-medium">我的账号</div>
            <div className="truncate text-xs text-muted-foreground">me@example.com</div>
          </div>
        </div>
        <Separator className="my-1" />
        <div className="px-2 pb-1 pt-1.5 text-xs text-muted-foreground">主题</div>
        <div className="space-y-0.5 px-1 pb-1">
          {THEMES.map((t) => (
            <button
              key={t.value}
              onClick={() => setTheme(t.value)}
              className="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-sm transition-colors hover:bg-muted/60"
            >
                  <span className="text-muted-foreground">{t.icon}</span>
              <span className="flex-1 text-left">{t.label}</span>
              {mounted && theme === t.value ? <Check className="size-3.5 text-primary" /> : null}
            </button>
          ))}
        </div>
        <Separator className="my-1" />
        <button className="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-sm text-destructive transition-colors hover:bg-destructive/10">
          <LogOut className="size-3.5" /> 退出登录
        </button>
      </PopoverContent>
    </Popover>
  );
}
