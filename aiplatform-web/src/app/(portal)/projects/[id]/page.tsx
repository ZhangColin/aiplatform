import type { Metadata } from "next";

import { WorkbenchView } from "@/components/workbench/workbench-view";

export const metadata: Metadata = { title: "项目" };

/** 项目页 = 指令区 + 成果区两槽位壳（issue #17 单门户三路由之一）。 */
export default async function ProjectPage(props: PageProps<"/projects/[id]">) {
  const { id } = await props.params;
  return <WorkbenchView projectId={id} />;
}
