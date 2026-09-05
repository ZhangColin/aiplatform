import { useMutation, useQueryClient } from "@tanstack/react-query";

import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import type {
  CreateProjectCommand,
  ProjectCreatedResponse,
} from "@/lib/projects/create";

/**
 * 一句话建项目（issue #39）：POST /api/projects → 200 返回
 * {project, runId}（异步起开场运行，runId 即该运行全部 agent 流事件的
 * 锚，挂 /api/events?runId=）。成功失效项目域（列表 / sidebar 徽章随建
 * 刷新）；直进项目页由调用侧按 project.id 导航——项目页按 projectId 挂 agent 流
 * （ADR 0003，比 runId 过滤更宽、天然覆盖开场运行），故 runId 不在此显式消费，
 * 主智能体的 run-start 事件到达即播种运行注册表。
 */
export function useCreateProject() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (command: CreateProjectCommand) =>
      api.post<ProjectCreatedResponse>("/projects", command),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.projects.all });
    },
  });
}
