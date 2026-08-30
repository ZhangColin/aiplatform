import type { ReactNode } from "react";

import { UserPortalShell } from "@/components/user-portal/portal-shell";

/** 需求端门户 app 级 layout（spec 0002 §1）：sidebar 场景装配归此，页面自管 inset。 */
export default function PortalLayout({ children }: { children: ReactNode }) {
  return <UserPortalShell>{children}</UserPortalShell>;
}
