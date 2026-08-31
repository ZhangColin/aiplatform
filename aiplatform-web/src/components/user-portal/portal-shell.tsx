"use client";

import { Home, LayoutGrid } from "lucide-react";
import type { ReactNode } from "react";

import { PortalSidebar, type PortalNavGroup } from "@/components/layout/portal-sidebar";
import { SidebarProvider } from "@/components/ui/sidebar";

/**
 * 门户场景装配（issue #17 单门户三路由）：菜单两项——「首页」（hero 落地页，
 * 新建项目唯一入口）+「我的项目」（项目列表页）。项目不直列、无数据依赖。
 */

/** 门户菜单：静态两项、无分组标签。 */
const PORTAL_GROUPS: PortalNavGroup[] = [
  {
    items: [
      { key: "home", label: "首页", icon: <Home />, href: "/" },
      { key: "projects", label: "我的项目", icon: <LayoutGrid />, href: "/projects" },
    ],
  },
];

export function PortalShell({ children }: { children: ReactNode }) {
  return (
    <SidebarProvider>
      <PortalSidebar groups={PORTAL_GROUPS} />
      {children}
    </SidebarProvider>
  );
}
