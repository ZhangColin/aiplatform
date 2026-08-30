"use client";

import { BellRing, ListChecks } from "lucide-react";
import type { ReactNode } from "react";

import { PortalSidebar, type PortalNavItem } from "@/components/layout/portal-sidebar";
import { SidebarProvider } from "@/components/ui/sidebar";
import { useTodoList } from "@/hooks/use-todos";

/**
 * 任务平台（OPC）场景装配（spec 0003 §1/§2）：不见项目只见任务——平台组 =
 * 「我的任务」（落地页）+「待办中心」（opc 两型：新任务 / 被驳回，issue #22）。
 * 待办徽章 (N) = opc 待办计数。
 */
export function OpcPortalShell({ children }: { children: ReactNode }) {
  return (
    <SidebarProvider>
      <PortalSidebar portal="任务平台" groups={useOpcPortalGroups()} />
      {children}
    </SidebarProvider>
  );
}

function useOpcPortalGroups() {
  const { data: todos } = useTodoList("opc");

  const platformItems: PortalNavItem[] = [
    { key: "my-tasks", label: "我的任务", icon: <ListChecks />, href: "/opc" },
    {
      key: "todos",
      label: "待办中心",
      icon: <BellRing />,
      badge: todos && todos.length > 0 ? String(todos.length) : undefined,
      badgeTone: "amber",
      href: "/opc/todos",
    },
  ];

  return [{ label: "平台", items: platformItems }];
}
