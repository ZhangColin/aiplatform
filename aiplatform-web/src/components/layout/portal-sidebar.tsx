"use client";

import { PanelLeftIcon } from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";

import { ModeToggle } from "@/components/mode-toggle";
import { UserMenu } from "@/components/user-menu";
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
import { cn } from "@/lib/utils";

/**
 * 主 Layout 的通用层实现（issue #17 单门户清场）：sidebar 框架（品牌位 + 分组
 * 菜单 + footer）归通用层，菜单内容（groups）归场景层配置。active 态由路由推导，
 * href 项渲染为 Link。收起交互归品牌行：展开态品牌行 = 品牌 + 右侧收起按钮；
 * 收起态 = 图标条，点 Logo 或空白处展开。
 */

export type PortalNavItem = {
  key: string;
  label: string;
  icon?: ReactNode;
  href: string;
};

/** 场景菜单分组：label 可省（首页 + 我的项目两项无分组标签）。 */
export type PortalNavGroup = { label?: string; items: PortalNavItem[] };

export type PortalSidebarProps = {
  groups: PortalNavGroup[];
};

/**
 * active 态 = 全部菜单项 href 的最长前缀匹配（pathname 命中多条前缀时只有最长者
 * 高亮——落地页 /projects 与详情 /projects/{id} 不双亮）。
 */
function useActiveKey(groups: PortalSidebarProps["groups"]) {
  const pathname = usePathname();
  const items = groups.flatMap((group) => group.items);
  let best: PortalNavItem | null = null;
  for (const item of items) {
    const matched = pathname === item.href || pathname.startsWith(`${item.href}/`);
    if (matched && (best === null || item.href.length > best.href.length)) {
      best = item;
    }
  }
  return best?.key ?? null;
}

export function PortalSidebar({ groups }: PortalSidebarProps) {
  const activeKey = useActiveKey(groups);
  // 收起态判定补 !isMobile：mobile 走 Sheet 始终按展开态渲染，图标条语义只在
  // 桌面 collapsible="icon" 生效。
  const { state, isMobile, setOpen, toggleSidebar } = useSidebar();
  const collapsed = state === "collapsed" && !isMobile;

  return (
    <Sidebar collapsible="icon">
      {/* 图标条空白处 = 展开：垫在导航之下的整条按钮，点导航图标仍导航（菜单项
          均为定位元素，绘制在其上），点空白落到此层展开。裸 <button> 同
          ui/sidebar 的 SidebarRail 先例——不可见命中层无设计系统观感可取，
          Button 的 hover/focus 样式整条泛光反是干扰。 */}
      <button
        type="button"
        aria-label="展开菜单"
        tabIndex={-1}
        onClick={() => setOpen(true)}
        className="absolute inset-0 hidden cursor-pointer group-data-[collapsible=icon]:block"
      />
      {/* 品牌/脚手在空白展开层之上（z-10），自身按钮不被其盖住 */}
      <SidebarHeader className="relative z-10">
        <SidebarMenu>
          <SidebarMenuItem>
            {collapsed ? (
              // 收起态品牌位 = 展开（点 Logo 展开）
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
              // 展开态品牌行 = 品牌 + 右侧收起按钮
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
        {groups.map((group) =>
          group.items.length === 0 ? null : (
            <SidebarGroup key={group.label ?? group.items[0]?.key ?? "group"}>
              {group.label ? <SidebarGroupLabel>{group.label}</SidebarGroupLabel> : null}
              <SidebarGroupContent>
                <SidebarMenu>
                  {group.items.map((item) => (
                    <SidebarNavItem key={item.key} item={item} active={item.key === activeKey} />
                  ))}
                </SidebarMenu>
              </SidebarGroupContent>
            </SidebarGroup>
          ),
        )}
      </SidebarContent>
      <SidebarFooter className="relative z-10">
        <SidebarMenu>
          <SidebarMenuItem>
            {/* 两个独立触发器（UserMenu/ModeToggle 各自是按钮），不能裹进
                SidebarMenuButton——button 套 button 是非法嵌套，hydration 必炸。 */}
            <div className="flex items-center gap-1 px-1 text-muted-foreground group-data-[collapsible=icon]:flex-col group-data-[collapsible=icon]:px-0">
              <UserMenu />
              <ModeToggle />
            </div>
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

function SidebarNavItem({ item, active }: { item: PortalNavItem; active: boolean }) {
  return (
    <SidebarMenuItem>
      <SidebarMenuButton isActive={active} tooltip={item.label} render={<Link href={item.href} />}>
        {item.icon}
        <span className="truncate">{item.label}</span>
      </SidebarMenuButton>
    </SidebarMenuItem>
  );
}

/** 非工作台页同壳：页头（标题 + 说明）由各页自带，收起/展开归品牌行。 */
export function PortalContent({ children }: { children: ReactNode }) {
  return (
    <SidebarInset className="h-svh min-h-0 flex-col">
      <main className="min-h-0 flex-1 overflow-y-auto">{children}</main>
    </SidebarInset>
  );
}
