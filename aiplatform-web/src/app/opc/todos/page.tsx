import type { Metadata } from "next";

import { TodoListView } from "@/components/todo/todo-list-view";

export const metadata: Metadata = { title: "待办中心" };

/** 待办中心（spec 0003 §3，issue #22）：opc 两型——新任务 / 被驳回，点击进任务详情。 */
export default function OpcTodosPage() {
  return (
    <TodoListView
      view="opc"
      title="待办中心"
      description="新指派和被驳回的任务会出现在这里"
      emptyText="现在没有需要你处理的事"
    />
  );
}
