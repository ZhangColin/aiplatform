import type { Metadata } from "next";

import { MembersView } from "@/components/dev-portal/members-view";

export const metadata: Metadata = { title: "成员" };

/** 成员页（spec 0003 §4，issue #22）：只读表格，随 A4 账号端点挂入。 */
export default function DevMembersPage() {
  return <MembersView />;
}
