import type { Metadata } from "next";

import { ProjectListView } from "@/components/user-portal/project-list-view";

export const metadata: Metadata = { title: "我的项目" };

/** 项目列表页（spec 0002 §3.2）：四态过滤 + 归档（issue #20）。 */
export default function ProjectsPage() {
  return <ProjectListView />;
}
