import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import { useSseFallbackPolling } from "@/lib/sse/provider";
import {
  normalizeWait,
  type ProjectWaitResponse,
  type ProjectWaitSettleCommand,
} from "@/lib/agent/wait";

/**
 * HITL 等待点数据层（issue #45，spec 0001 §5）：`GET /projects/{id}/agent/waits`
 * （跨会话 PENDING）为队列与对话流底的共同数据源；settle 三型载荷由卡体构造。
 * 实时性失效源 = agent 流通道 wait-raised / wait-settled（#45 已挂桥失效），
 * 轮询兜底看 agent 通道（15s）；写操作成功本地同样失效（正确性以 REST 为准）。
 */

export function useProjectWaits(projectId: string | undefined) {
  const fallbackPolling = useSseFallbackPolling("agent");
  return useQuery({
    queryKey: queryKeys.projects.waits(projectId ?? ""),
    queryFn: ({ signal }) =>
      api
        .get<ProjectWaitResponse[]>(`/projects/${projectId}/agent/waits`, { signal })
        .then((items) => (items ?? []).map(normalizeWait)),
    enabled: projectId !== undefined,
    refetchInterval: fallbackPolling,
  });
}

/** 答复等待点（问答 / 权限 / 转任务）；成功 = 失效 waits + todos + projects 三域。 */
export function useSettleWait(projectId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ waitId, command }: { waitId: string; command: ProjectWaitSettleCommand }) =>
      api.post<void>(`/projects/${projectId}/agent/waits/${waitId}/settle`, command),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.projects.all });
      void queryClient.invalidateQueries({ queryKey: queryKeys.todos.all });
    },
  });
}
