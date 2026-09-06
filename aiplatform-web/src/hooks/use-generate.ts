import { useMutation } from "@tanstack/react-query";
import { toast } from "sonner";

import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/schema";
import { errorText } from "@/lib/api/api-error";
import { useGenerationStore } from "@/lib/store/generation";

type GenerationStartResponse = components["schemas"]["GenerationStartResponse"];

/**
 * 重新发起（生成失败兜底，#101 生成无门后「开始做系统」按钮退役）：POST
 * /api/projects/{id}/generate——纯动作无门（待定项未清也可点）。成功即乐观登记
 * 生成在途（SSE run-start 随后到，重放/回声幂等），过程与收口以 SSE + REST 重查
 * 为准。正常流生成由平台在意见轮收口自动发起，本 mutation 只服务失败态兜底。
 */
export function useGenerate(projectId: string) {
  return useMutation({
    mutationFn: () =>
      api.post<GenerationStartResponse>(`/projects/${projectId}/generate`),
    onSuccess: (result) => {
      if (result?.runId) {
        // 乐观登记编码 run 在途（run-start 随后到，重放/回声幂等）
        useGenerationStore.getState().noteCoderRun(projectId, result.runId);
      }
    },
    onError: (error) => {
      toast.error(errorText(error, "发起生成失败，请稍后重试"));
    },
  });
}
