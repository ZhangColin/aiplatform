"use client";

import type { ReactNode } from "react";
import { usePathname } from "next/navigation";

import { ProjectSpaceShell } from "@/components/shell/project-space-shell";
import { AppSidebar } from "@/components/layout/app-sidebar";
import { SidebarProvider } from "@/components/ui/sidebar";

/**
 * 站点场景装配（#76 侧栏定稿形态 / #343 项目空间新壳分流）：项目空间
 * （/projects/[id] 及阶段子路径）走阶段侧栏＋全局顶栏的新壳（spec #320 ⑥
 * 全局壳重建）；首页/列表仍旧壳（共存窗口——首页 P01 重建归 #345、
 * 我的项目 P08 归 #349）。
 */

/** 项目空间路由（/projects/[id] 与其下阶段页路径段）——新壳档。 */
function projectIdInSpace(pathname: string): string | null {
  const match = /^\/projects\/([^/]+)(?:\/.*)?$/.exec(pathname);
  return match ? (match[1] as string) : null;
}

export function SiteShell({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const spaceProjectId = projectIdInSpace(pathname);
  if (spaceProjectId) {
    return <ProjectSpaceShell projectId={spaceProjectId}>{children}</ProjectSpaceShell>;
  }
  return (
    <SidebarProvider defaultOpen>
      <AppSidebar />
      {children}
    </SidebarProvider>
  );
}
