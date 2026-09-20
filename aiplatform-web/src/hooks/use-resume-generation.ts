import { useMutation, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import type { components } from "@/lib/api/schema";
import { errorText } from "@/lib/api/api-error";
import { useGenerationStore } from "@/lib/store/generation";

type GenerationStartResponse = components["schemas"]["GenerationStartResponse"];

/**
 * 继续生成（#222 单出口；#221 断点续跑）：POST /api/projects/{id}/generate——重发
 * 即从断点接续（跳过已收口片、只重跑失败/中断片）；计划缺失（或存量项目无轨道）
 * 返回补产轮 runId，主智能体按 PRD 补产切片计划后自动再派生成。成功即乐观登记
 * 编码 run 在途（SSE run-start 随后到，重放/回声幂等）＋失效项目域（四态投影回
 * 「生成中」档——服务端在途标记已先落）。正常流生成由平台在意见轮收口自动发起，
 * 本 mutation 只服务中断/未起跑的恢复出口。
 */
export function useResumeGeneration(projectId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () =>
      api.post<GenerationStartResponse>(`/projects/${projectId}/generate`),
    onSuccess: (result) => {
      if (result?.runId) {
        // 乐观登记编码 run 在途（run-start 随后到，重放/回声幂等）
        useGenerationStore.getState().noteCoderRun(projectId, result.runId);
      }
      // 四态投影回「生成中」（服务端在途标记已落，重拉即得；补产轮路径靠
      // executor run-start 的失效收尾——见 bridge）
      void queryClient.invalidateQueries({ queryKey: queryKeys.projects.all });
    },
    onError: (error) => {
      toast.error(errorText(error, "继续生成失败，请稍后重试"));
    },
  });
}
