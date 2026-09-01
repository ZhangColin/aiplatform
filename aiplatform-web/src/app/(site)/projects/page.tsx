import type { Metadata } from "next";

import { ProjectListView } from "@/components/site/project-list-view";

export const metadata: Metadata = { title: "我的项目" };

/** 项目列表页：过滤 + 归档（issue #17 单站三路由之一）。 */
export default function ProjectsPage() {
  return <ProjectListView />;
}
