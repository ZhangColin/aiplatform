"use client";

import { Home, LayoutGrid } from "lucide-react";
import type { ReactNode } from "react";

import { PortalSidebar, type PortalNavGroup } from "@/components/layout/portal-sidebar";
import { SidebarProvider } from "@/components/ui/sidebar";

/**
 * 需求端场景装配（spec 0002 §1 场景层 / §2 2026-08-25 修订，issue #49）：菜单收
 * 两项——「首页」（hero 落地页，新建项目唯一入口）+「我的项目」（项目列表页）。
 * 项目不直列、平台组各项去除；「需要你」可见性走首页/列表卡 amber 行 + 工作台
 * 徽章，待办深链保留（不进菜单）。
 */

/** 需求端菜单：静态两项、无分组标签，无数据依赖。 */
const USER_PORTAL_GROUPS: PortalNavGroup[] = [
  {
    items: [
      { key: "home", label: "首页", icon: <Home />, href: "/" },
      { key: "projects", label: "我的项目", icon: <LayoutGrid />, href: "/projects" },
    ],
  },
];

export function UserPortalShell({ children }: { children: ReactNode }) {
  return (
    <SidebarProvider>
      <PortalSidebar portal="需求端" groups={USER_PORTAL_GROUPS} />
      {children}
    </SidebarProvider>
  );
}
