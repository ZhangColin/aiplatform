import type { Metadata } from "next";

import { TodoListView } from "@/components/todo/todo-list-view";

export const metadata: Metadata = { title: "待办中心" };

/** 待办中心（spec 0003 §3，issue #21）：dev 两型，AGENT_WAIT / GATE_PENDING。 */
export default function DevTodosPage() {
  return (
    <TodoListView
      view="dev"
      title="待办中心"
      description="智能体等答复、阶段门待拍板的事都在这里"
      emptyText="现在没有需要你处理的事"
    />
  );
}
