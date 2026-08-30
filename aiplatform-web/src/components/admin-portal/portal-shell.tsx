"use client";

import { Settings2 } from "lucide-react";
import type { ReactNode } from "react";

import { PortalSidebar, type PortalNavGroup } from "@/components/layout/portal-sidebar";
import { SidebarProvider } from "@/components/ui/sidebar";

/**
 * 简易后台场景装配（CONTEXT「简易后台」，#56）：v1 单页起步（引擎配置），
 * 全账号可见。菜单结构 = 后台内容的目录——后续配置项在 groups 增组/增项即可，
 * 不动壳；将来整体迁正式管理后台（admin）门户。
 */

/** 后台菜单：v1 一项；无 label 分组留扩展位（增项时按域分组）。 */
const ADMIN_PORTAL_GROUPS: PortalNavGroup[] = [
  {
    items: [{ key: "engine-config", label: "引擎配置", icon: <Settings2 />, href: "/admin" }],
  },
];

export function AdminPortalShell({ children }: { children: ReactNode }) {
  return (
    <SidebarProvider>
      <PortalSidebar portal="后台" groups={ADMIN_PORTAL_GROUPS} />
      {children}
    </SidebarProvider>
  );
}
