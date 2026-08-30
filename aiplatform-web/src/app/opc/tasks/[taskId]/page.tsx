import type { Metadata } from "next";

import { TaskDetailView } from "@/components/opc-portal/task-detail-view";

export const metadata: Metadata = { title: "任务详情" };

/** 任务详情（spec 0003 §2.2，issue #22）：OPC 测试工作单页。 */
export default async function TaskDetailPage(props: PageProps<"/opc/tasks/[taskId]">) {
  const { taskId } = await props.params;
  return <TaskDetailView taskId={taskId} />;
}
