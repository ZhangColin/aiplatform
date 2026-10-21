import { useMutation, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import type { components } from "@/lib/api/schema";
import { errorText } from "@/lib/api/api-error";

type GenerationStopResponse = components["schemas"]["GenerationStopResponse"];

/** 停止收口落库的延迟重拉窗（SSE 断线兜底——轨道 wind-down 需要的落库时间）。 */
const STOP_FALLBACK_REFETCH_MS = 2000;

/**
 * 停止在途生成/更新（#359 生成取消）：POST /api/projects/{id}/generation/stop——
 * 服务端置停＋中断执行任务（模型流随之中止）；停止是异步收场，终态经 SSE
 * run-failed(stopped=true) 收口（store 失败档分岔「已停止」文案）。
 *
 * <p>失效兜底双路：请求成功即失效项目域（四态投影在轨道 wind-down 后转「生成
 * 中断」——REST 为正确性正本）；stopped=true 再延迟失效一次——SSE 断线时
 * run-failed 不达，重拉是唯一收敛口（延迟窗给轨道收口留出落库时间）。</p>
 */
export function useStopGeneration(projectId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () =>
      api.post<GenerationStopResponse>(`/projects/${projectId}/generation/stop`),
    onSuccess: (result) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.projects.all });
      if (result?.stopped) {
        // SSE 断线兜底：run-failed(stopped) 若未到（桥侧失效随事件），延迟窗后
        // 重拉项目域——停止收口落库后四态投影即「生成中断」
        setTimeout(() => {
          void queryClient.invalidateQueries({ queryKey: queryKeys.projects.all });
        }, STOP_FALLBACK_REFETCH_MS);
      }
    },
    onError: (error) => {
      toast.error(errorText(error, "停止失败，请稍后重试"));
    },
  });
}
