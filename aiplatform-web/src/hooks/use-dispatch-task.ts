import { useMutation } from "@tanstack/react-query";

import type { ProjectAgentTaskCommand } from "@/lib/agent/task-command";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/schema";

/**
 * 下任务（issue #40，spec 0001 §4.1）：POST /projects/{id}/agent/task。
 * runId 随响应返回（挂 streams store 的锚），过程事件走 agent 流通道、由桥写全量
 * 分段（本 hook 不写 store——store 唯一写入方是桥）。不失效项目域：任务计数/门禁
 * 刷新归后续门票，消息流呈现完全由 SSE 驱动。
 */

type ProjectAgentTaskResponse = components["schemas"]["ProjectAgentTaskResponse"];

export function useDispatchTask(projectId: string) {
  return useMutation({
    mutationFn: (command: ProjectAgentTaskCommand) =>
      api.post<ProjectAgentTaskResponse>(`/projects/${projectId}/agent/task`, command),
  });
}
