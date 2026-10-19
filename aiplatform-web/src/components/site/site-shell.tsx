"use client";

import type { ReactNode } from "react";
import { usePathname } from "next/navigation";

import { ProjectSpaceShell } from "@/components/shell/project-space-shell";

/**
 * 站点场景装配：项目空间（/projects/[id] 及阶段子路径）走阶段侧栏＋全局顶栏；
 * 首页 P01 与我的项目 P08 自管顶栏，不再装配旧侧栏（#349 旧壳共存窗口结束）。
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
  return <>{children}</>;
}
