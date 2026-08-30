import type { ReactNode } from "react";

import { AdminPortalShell } from "@/components/admin-portal/portal-shell";

/** 简易后台 app 级 layout（CONTEXT「简易后台」，#56）：场景菜单归此装配。 */
export default function AdminLayout({ children }: { children: ReactNode }) {
  return <AdminPortalShell>{children}</AdminPortalShell>;
}
