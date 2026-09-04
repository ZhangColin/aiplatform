"use client";

import type { ReactNode } from "react";

import { AppSidebar } from "@/components/layout/app-sidebar";
import { SidebarProvider } from "@/components/ui/sidebar";

/**
 * 站点场景装配（#76 侧栏定稿形态）：新建项目 / 首页 /「能做这些」模式位 /
 * 历史项目（数据驱动）/ 个人菜单全部内建在 AppSidebar，不再走场景配置。
 */

export function SiteShell({ children }: { children: ReactNode }) {
  return (
    <SidebarProvider>
      <AppSidebar />
      {children}
    </SidebarProvider>
  );
}
