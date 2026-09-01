import type { Metadata } from "next";

import { ProjectPageView } from "@/components/project-page/project-page-view";

export const metadata: Metadata = { title: "项目" };

/** 项目页 = 指令区 + 成果区两槽位壳（issue #17 单站三路由之一）。 */
export default async function ProjectPage(props: PageProps<"/projects/[id]">) {
  const { id } = await props.params;
  return <ProjectPageView projectId={id} />;
}
