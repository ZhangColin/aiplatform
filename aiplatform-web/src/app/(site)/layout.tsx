import type { ReactNode } from "react";

import { SiteShell } from "@/components/site/site-shell";

/** 站点 app 级 layout（issue #17 单站）：sidebar 装配归此，页面自管 inset。 */
export default function SiteLayout({ children }: { children: ReactNode }) {
  return <SiteShell>{children}</SiteShell>;
}
