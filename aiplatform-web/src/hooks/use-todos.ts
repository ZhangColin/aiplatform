import { useQuery } from "@tanstack/react-query";

import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import { useSseStatus } from "@/lib/sse/provider";
import {
  attentionByProject,
  normalizeTodo,
  type TodoItemResponse,
  type TodoView,
} from "@/lib/todos/todo";

/**
 * 待办列表（issue #21）：实时性失效源跨双通道——stage-changed / task-updated
 * 走通知通道、wait-raised / wait-settled 走 agent 流通道（正本归属）。轮询兜底
 * 因此是「双通道任一未连即 15s（ADR 0003 同值）」而非列表类通用的只看通知
 * 通道：骨架期 agent 通道未挂载（工作台建连）即等价常开，挂载后自然收敛。
 */

export function useTodoList(view: TodoView) {
  const notification = useSseStatus("notification");
  const agent = useSseStatus("agent");
  return useQuery({
    queryKey: queryKeys.todos.list(view),
    queryFn: ({ signal }) =>
      api
        .get<TodoItemResponse[]>("/todos", { query: { view }, signal })
        .then((items) => (items ?? []).map(normalizeTodo)),
    refetchInterval: notification === "connected" && agent === "connected" ? false : 15_000,
  });
}

/**
 * 需求端「需要你」聚合（issue #49）：dev 视角待办（AGENT_WAIT / GATE_PENDING 无
 * user view，收口在此不散进组件）→ 项目级提醒行数据（文案 + 需求端深链）。
 */
export function useProjectAttention() {
  const { data } = useTodoList("dev");
  return attentionByProject(data ?? []);
}
