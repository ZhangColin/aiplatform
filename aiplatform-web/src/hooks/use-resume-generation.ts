import { useMutation, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import type { components } from "@/lib/api/schema";
import { errorText } from "@/lib/api/api-error";
import { useGenerationStore } from "@/lib/store/generation";

type GenerationStartResponse = components["schemas"]["GenerationStartResponse"];

/**
 * 继续生成（#222 单出口）：POST /api/projects/{id}/generate——重发即在既有工作区
 * 重跑（#604 失败新语义＝明确展示＋重跑即恢复：服务端续跑机器已退役，不带断点、
 * 不携错误现场）。成功即乐观登记编码 run 在途（SSE run-start 随后到，重放/回声
 * 幂等）＋失效项目域（生成态投影回「生成中」档——服务端在途标记已先落）。正常流
 * 生成由点火入口发起（岔路口「立即生成」／PRD 确认；快道与设计定稿由收口观测
 * 直派），本 mutation 只服务未起跑/会话内失败的恢复出口（岔路口卡面「立即生成」
 * 与本钩同端点，共用本 mutation）。
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
      // 生成态投影回「生成中」（服务端在途标记已落，重拉即得；失效收尾靠
      // run-start 的桥侧失效——见 bridge）
      void queryClient.invalidateQueries({ queryKey: queryKeys.projects.all });
    },
    onError: (error) => {
      toast.error(errorText(error, "继续生成失败，请稍后重试"));
    },
  });
}
