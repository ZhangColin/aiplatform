import { useMutation, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import type { components } from "@/lib/api/schema";
import { errorText } from "@/lib/api/api-error";
import { useGenerationStore } from "@/lib/store/generation";

type GenerationStartResponse = components["schemas"]["GenerationStartResponse"];

/**
 * 点火生成：POST /api/projects/{id}/generate——岔路口「立即生成」的派发动作
 * （#542），也是失败后的重跑路径（#604 失败新语义＝明确展示＋重跑即恢复：服务端
 * 续跑机器已退役，不带断点、不携错误现场；#608 起「继续生成」专用按钮退役，重跑
 * 统一走本 mutation 的点火入口）。成功即乐观登记编码 run 在途（SSE run-start
 * 随后到，重放/回声幂等）＋失效项目域（生成态投影回「生成中」档——服务端在途
 * 标记已先落）。付费轨首建走 PRD 确认门的 mutation（use-build-gate）。
 */
export function useStartGeneration(projectId: string) {
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
      toast.error(errorText(error, "生成发起失败，请稍后重试"));
    },
  });
}
