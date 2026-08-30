"use client";

import { BellRing, FolderKanban, LayoutGrid, Users } from "lucide-react";
import type { ReactNode } from "react";

import { PortalSidebar, type PortalNavItem } from "@/components/layout/portal-sidebar";
import { SidebarProvider } from "@/components/ui/sidebar";
import { useSidebarProjects } from "@/hooks/use-projects";
import { useTodoList } from "@/hooks/use-todos";

/**
 * 开发平台场景装配（spec 0003 §1）：全量菜单——项目组（未归档项目 + 阶段徽章）
 * + 平台组（项目列表 / 待办中心 / 成员）。待办徽章 (N) = dev 待办计数（四型：
 * AGENT_WAIT / GATE_PENDING / TASK_SUBMITTED / RETEST_READY，issue #22）。
 */

export function DevPortalShell({ children }: { children: ReactNode }) {
  return (
    <SidebarProvider>
      <PortalSidebar portal="开发平台" groups={useDevPortalGroups()} />
      {children}
    </SidebarProvider>
  );
}

function useDevPortalGroups() {
  const projects = useSidebarProjects();
  const { data: todos } = useTodoList("dev");

  const projectItems: PortalNavItem[] = projects.map((p) => ({
    key: p.id,
    label: p.name || "未命名项目",
    icon: <FolderKanban />,
    badge: p.stageLabel || undefined,
    href: `/dev/projects/${p.id}`,
  }));

  const platformItems: PortalNavItem[] = [
    { key: "projects", label: "项目列表", icon: <LayoutGrid />, href: "/dev" },
    {
      key: "todos",
      label: "待办中心",
      icon: <BellRing />,
      badge: todos && todos.length > 0 ? String(todos.length) : undefined,
      badgeTone: "amber",
      href: "/dev/todos",
    },
    { key: "members", label: "成员", icon: <Users />, href: "/dev/members" },
  ];

  return [
    { label: "项目", items: projectItems },
    { label: "平台", items: platformItems },
  ];
}
