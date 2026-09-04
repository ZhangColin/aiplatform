"use client";

import type { ReactNode } from "react";
import { usePathname } from "next/navigation";

import { AppSidebar } from "@/components/layout/app-sidebar";
import { SidebarProvider } from "@/components/ui/sidebar";

/**
 * 站点场景装配（#76 侧栏定稿形态 / #79 项目页 icon rail 接入）：新建项目 /
 * 首页 /「能做这些」模式位 / 历史项目（数据驱动）/ 个人菜单全部内建在
 * AppSidebar。项目页默认收成 icon rail（Tooltip、可展开——对话与成果让出
 * 最大空间）；其余面默认展开。key 按档分：跨档导航（首页↔项目页）重挂
 * provider 使缺省态随页生效；同档内（项目↔项目）不重挂，保留当次收展。
 */

/** 项目详情路由（/projects/[id]）——侧栏收成 icon rail 的档。 */
function isProjectPage(pathname: string): boolean {
  return /^\/projects\/[^/]+$/.test(pathname);
}

export function SiteShell({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const rail = isProjectPage(pathname);
  return (
    <SidebarProvider key={rail ? "rail" : "wide"} defaultOpen={!rail}>
      <AppSidebar />
      {children}
    </SidebarProvider>
  );
}
