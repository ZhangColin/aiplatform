import type { Metadata } from "next";

import { TaskListView } from "@/components/opc-portal/task-list-view";

export const metadata: Metadata = { title: "我的任务" };

/**
 * 任务平台落地页 = 我的任务（spec 0003 §1/§2.1，issue #22）：任务卡片网格
 * （新→旧）替换 #21 的恒空骨架；待办中心移至 /opc/todos。
 */
export default function OpcHomePage() {
  return <TaskListView />;
}
