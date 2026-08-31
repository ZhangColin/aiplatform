import type { ReactNode } from "react";

import { PortalShell } from "@/components/user-portal/portal-shell";

/** 门户 app 级 layout（issue #17 单门户）：sidebar 装配归此，页面自管 inset。 */
export default function PortalLayout({ children }: { children: ReactNode }) {
  return <PortalShell>{children}</PortalShell>;
}
