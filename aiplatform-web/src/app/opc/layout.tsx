import type { ReactNode } from "react";

import { OpcPortalShell } from "@/components/opc-portal/portal-shell";

/** 任务平台门户 app 级 layout（spec 0003 §1）：场景菜单归此装配。 */
export default function OpcLayout({ children }: { children: ReactNode }) {
  return <OpcPortalShell>{children}</OpcPortalShell>;
}
