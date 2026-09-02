import { useMutation } from "@tanstack/react-query";
import { toast } from "sonner";

import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/schema";
import { errorText } from "@/lib/api/api-error";
import { useGenerationStore } from "@/lib/store/generation";

type FixRestartResponse = components["schemas"]["FixRestartResponse"];

/**
 * 重新修改（#48 修正 run 超限终态恢复出口）：POST
 * /api/projects/{id}/fix-runs/restart——平台重派终态那场的交接物（同任务清单、
 * 续同 coder 会话）。成功即乐观登记编码 run 在途（SSE role-assigned/run-start
 * 随后到，重放/回声幂等），面板随之离开终态档；无可恢复的修正（服务重启丢账等）
 * 由后端 409 语义指路重提意见。
 */
export function useRestartFix(projectId: string) {
  return useMutation({
    mutationFn: () =>
      api.post<FixRestartResponse>(`/projects/${projectId}/fix-runs/restart`),
    onSuccess: (result) => {
      const generation = useGenerationStore.getState();
      if (result?.runId) generation.noteCoderRun(projectId, result.runId);
      generation.noteCoderRunStart(projectId);
    },
    onError: (error) => {
      toast.error(errorText(error, "重新修改发起失败，请稍后重试"));
    },
  });
}
