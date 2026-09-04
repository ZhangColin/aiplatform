"use client";

import * as React from "react";
import { Check, ChevronsUpDown, LogOut, Monitor, Moon, Sun } from "lucide-react";
import { useTheme } from "next-themes";

import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Separator } from "@/components/ui/separator";
import { useMe } from "@/hooks/use-me";
import { cn } from "@/lib/utils";

/**
 * 左下角个人菜单（#72 定稿 / #76 落地）：账户信息 + 主题三态（浅色/深色/
 * 跟随系统，next-themes localStorage 持久、刷新保持）+ 退出登录（form POST
 * /auth/logout 杀 identity 会话）。侧栏收起态（collapsed）只显头像。
 */

const THEMES = [
  { value: "light", label: "浅色", icon: <Sun className="size-3.5" /> },
  { value: "dark", label: "深色", icon: <Moon className="size-3.5" /> },
  { value: "system", label: "跟随系统", icon: <Monitor className="size-3.5" /> },
] as const;

export function AccountMenu({ collapsed = false }: { collapsed?: boolean }) {
  const { data: me } = useMe();
  const { theme, setTheme } = useTheme();
  // next-themes 挂载守卫（useSyncExternalStore 写法，避开 setState-in-effect）
  const mounted = React.useSyncExternalStore(() => () => {}, () => true, () => false);
  const displayName = me?.displayName ?? "我的账号";
  const initial = displayName.slice(0, 1).toUpperCase();

  return (
    <Popover>
      <PopoverTrigger
        className={cn(
          "flex w-full items-center gap-2 rounded-md text-sm text-muted-foreground transition-colors hover:bg-muted/60 hover:text-foreground",
          collapsed ? "justify-center p-1.5" : "px-2 py-1.5",
        )}
        aria-label="个人菜单"
      >
        <span className="flex size-7 shrink-0 items-center justify-center rounded-full bg-foreground text-xs font-bold text-background">
          {initial}
        </span>
        {!collapsed ? (
          <>
            <span className="min-w-0 flex-1 truncate text-left">{displayName}</span>
            <ChevronsUpDown className="size-3.5 shrink-0 text-muted-foreground/60" />
          </>
        ) : null}
      </PopoverTrigger>
      <PopoverContent side={collapsed ? "right" : "top"} align="start" className="w-60 p-1.5">
        <div className="flex items-center gap-2.5 px-2 py-2">
          <span className="flex size-9 items-center justify-center rounded-full bg-foreground text-xs font-bold text-background">
            {initial}
          </span>
          <div className="min-w-0">
            <div className="truncate text-sm font-medium">{displayName}</div>
          </div>
        </div>
        <Separator className="my-1" />
        <div className="px-2 pb-1 pt-1.5 text-xs text-muted-foreground">主题</div>
        <div className="space-y-0.5 px-1 pb-1">
          {THEMES.map((t) => (
            <button
              key={t.value}
              type="button"
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
        <form action="/auth/logout" method="post">
          {/* 原生 submit button：菜单关闭后 form POST 照常走（Base UI 无嵌套问题，button 在 form 内） */}
          <button
            type="submit"
            className="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-sm text-destructive transition-colors hover:bg-destructive/10"
          >
            <LogOut className="size-3.5" /> 退出登录
          </button>
        </form>
      </PopoverContent>
    </Popover>
  );
}
