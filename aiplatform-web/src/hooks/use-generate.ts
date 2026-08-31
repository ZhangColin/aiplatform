import { useMutation } from "@tanstack/react-query";
import { toast } from "sonner";

import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/schema";
import { errorText } from "@/lib/api/api-error";
import { useGenerationStore } from "@/lib/store/generation";

type GenerationStartResponse = components["schemas"]["GenerationStartResponse"];

/**
 * 开始做系统（#22 片2-1）：POST /api/projects/{id}/generate——纯动作无门（待定项
 * 未清也可点）。成功即乐观登记生成在途（SSE role-assigned/task-start 随后到，
 * 重放/回声幂等），过程与收口以 SSE + REST 重查为准。
 */
export function useGenerate(projectId: string) {
  return useMutation({
    mutationFn: () =>
      api.post<GenerationStartResponse>(`/projects/${projectId}/generate`),
    onSuccess: (result) => {
      const generation = useGenerationStore.getState();
      if (result?.runId) generation.noteCoderRun(projectId, result.runId);
      generation.noteCoderTaskStart(projectId);
    },
    onError: (error) => {
      toast.error(errorText(error, "发起生成失败，请稍后重试"));
    },
  });
}
