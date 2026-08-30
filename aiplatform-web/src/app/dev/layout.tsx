import type { ReactNode } from "react";

import { DevPortalShell } from "@/components/dev-portal/portal-shell";

/** 开发平台门户 app 级 layout（spec 0003 §1）：场景菜单 + 待办徽章归此装配。 */
export default function DevLayout({ children }: { children: ReactNode }) {
  return <DevPortalShell>{children}</DevPortalShell>;
}
