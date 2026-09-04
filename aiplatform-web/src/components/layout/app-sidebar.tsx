"use client";

import { Home, LayoutGrid, PanelLeftIcon, Plus, Sparkles } from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";

import { AccountMenu } from "@/components/account-menu";
import { ProjectAvatar } from "@/components/project-avatar";
import { Button } from "@/components/ui/button";
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupContent,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarInset,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  useSidebar,
} from "@/components/ui/sidebar";
import { useRecentProjects } from "@/hooks/use-projects";
import { PLATFORM_MODES } from "@/lib/modes";
import { cn } from "@/lib/utils";

/**
 * 侧栏定稿形态（#72 / #76）：新建项目 + 首页 +「能做这些」模式位 + 历史项目
 * 色块头像，左下角个人菜单（主题三态 + 退出登录）。默认展开；收起成 icon
 * rail 的完整形态（Tooltip、项目头像条）归 #79 项目页版面票，本票先由
 * shadcn collapsible="icon" 骨架兜底（图标剩位、空白处点开）。
 */

/** 侧栏历史项目条数（列表全量在 /projects）。 */
const SIDEBAR_PROJECT_LIMIT = 8;

export function AppSidebar() {
  const pathname = usePathname();
  const projects = useRecentProjects().slice(0, SIDEBAR_PROJECT_LIMIT);
  // 收起态判定补 !isMobile：mobile 走 Sheet 始终按展开态渲染。
  const { state, isMobile, setOpen, toggleSidebar } = useSidebar();
  const collapsed = state === "collapsed" && !isMobile;

  return (
    <Sidebar collapsible="icon">
      {/* 图标条空白处 = 展开：垫在导航之下的整条按钮（先例注释见 git 历史，
          交互归 icon rail 精化票 #79）。 */}
      <button
        type="button"
        aria-label="展开菜单"
        tabIndex={-1}
        onClick={() => setOpen(true)}
        className="absolute inset-0 hidden cursor-pointer group-data-[collapsible=icon]:block"
      />
      <SidebarHeader className="relative z-10">
        <SidebarMenu>
          <SidebarMenuItem>
            {collapsed ? (
              <SidebarMenuButton
                size="lg"
                tooltip="展开菜单"
                aria-label="展开菜单"
                onClick={() => setOpen(true)}
              >
                <BrandMark />
                <BrandName />
              </SidebarMenuButton>
            ) : (
              <div className="flex w-full items-center gap-1">
                <SidebarMenuButton size="lg" className="min-w-0 flex-1 gap-2">
                  <BrandMark />
                  <BrandName className="truncate" />
                </SidebarMenuButton>
                <Button
                  variant="ghost"
                  size="icon-sm"
                  aria-label="收起菜单"
                  onClick={toggleSidebar}
                  className="shrink-0 text-muted-foreground"
                >
                  <PanelLeftIcon />
                </Button>
              </div>
            )}
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarHeader>
      <SidebarContent>
        <SidebarGroup>
          <SidebarGroupContent>
            <SidebarMenu>
              <SidebarMenuItem>
                {collapsed ? (
                  <SidebarMenuButton tooltip="新建项目" render={<Link href="/" />}>
                    <Plus />
                    <span>新建项目</span>
                  </SidebarMenuButton>
                ) : (
                  <Button
                    className="w-full justify-start transition-transform active:scale-[0.98]"
                    size="sm"
                    nativeButton={false}
                    render={<Link href="/" />}
                  >
                    <Plus className="size-4" /> 新建项目
                  </Button>
                )}
              </SidebarMenuItem>
              <SidebarMenuItem>
                <SidebarMenuButton
                  tooltip="首页"
                  isActive={pathname === "/"}
                  render={<Link href="/" />}
                >
                  <Home />
                  <span>首页</span>
                </SidebarMenuButton>
              </SidebarMenuItem>
            </SidebarMenu>
          </SidebarGroupContent>
        </SidebarGroup>

        <SidebarGroup>
          <SidebarGroupLabel>能做这些</SidebarGroupLabel>
          <SidebarGroupContent>
            <SidebarMenu>
              {PLATFORM_MODES.map((m) => (
                <SidebarMenuItem key={m.label}>
                  <SidebarMenuButton
                    tooltip={m.label}
                    isActive={m.live}
                    disabled={!m.live}
                    aria-label={m.live ? m.label : `${m.label}（敬请期待）`}
                  >
                    <Sparkles />
                    <span>{m.label}</span>
                    {!m.live ? (
                      <span className="ml-auto text-xs text-muted-foreground/60 group-data-[collapsible=icon]:hidden">
                        敬请期待
                      </span>
                    ) : null}
                  </SidebarMenuButton>
                </SidebarMenuItem>
              ))}
            </SidebarMenu>
          </SidebarGroupContent>
        </SidebarGroup>

        <SidebarGroup>
          <SidebarGroupLabel>历史项目</SidebarGroupLabel>
          <SidebarGroupContent>
            <SidebarMenu>
              {projects.map((project) => {
                const name = project.name || "未命名项目";
                return (
                  <SidebarMenuItem key={project.id}>
                    <SidebarMenuButton
                      tooltip={name}
                      isActive={pathname === `/projects/${project.id}`}
                      render={<Link href={`/projects/${project.id}`} />}
                    >
                      <ProjectAvatar name={name} className="size-6 rounded-md" />
                      <span className="truncate">{name}</span>
                    </SidebarMenuButton>
                  </SidebarMenuItem>
                );
              })}
              <SidebarMenuItem>
                <SidebarMenuButton
                  tooltip="全部项目"
                  isActive={pathname === "/projects"}
                  render={<Link href="/projects" />}
                >
                  <LayoutGrid />
                  <span>全部项目</span>
                </SidebarMenuButton>
              </SidebarMenuItem>
            </SidebarMenu>
          </SidebarGroupContent>
        </SidebarGroup>
      </SidebarContent>
      <SidebarFooter className="relative z-10">
        <SidebarMenu>
          <SidebarMenuItem>
            <AccountMenu collapsed={collapsed} />
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarFooter>
    </Sidebar>
  );
}

/** 品牌位 Logo（展开/收起两分支共用；收起态文字被 collapsible=icon 样式裁掉）。 */
function BrandMark() {
  return (
    <span className="flex size-6 shrink-0 items-center justify-center rounded-md bg-primary text-xs font-bold text-primary-foreground">
      AI
    </span>
  );
}

/** 品牌名（两分支共用；展开分支补 truncate）。 */
function BrandName({ className }: { className?: string }) {
  return <span className={cn("text-sm font-semibold", className)}>AI 开发平台</span>;
}

/** 非项目页同壳：页头（标题 + 说明）由各页自带，收起/展开归品牌行。 */
export function AppSidebarContent({ children }: { children: ReactNode }) {
  return (
    <SidebarInset className="h-svh min-h-0 flex-col">
      <main className="min-h-0 flex-1 overflow-y-auto">{children}</main>
    </SidebarInset>
  );
}
