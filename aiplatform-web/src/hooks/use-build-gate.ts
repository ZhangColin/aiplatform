import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import type { components } from "@/lib/api/schema";
import { errorText } from "@/lib/api/api-error";
import { fetchBuildGate } from "@/lib/projects/build-gate";
import { useGenerationStore } from "@/lib/store/generation";

type GenerationStartResponse = components["schemas"]["GenerationStartResponse"];

/**
 * 岔路口与 PRD 确认门两接口（#542 访谈②，ADR-0048/0049——自动派发两入口退役、
 * 首建点火归用户）：读面（岔路口卡/确认按钮呈现条件，服务端单源）与付费轨确认
 * （确认即派发首个构建，原动作形态恢复——响应 runId＝编码 run 锚）。
 */

/**
 * 派发门读面：staleTime 0——每次挂载即重查（自然收口置卡在服务端轮收口发生、
 * 点火随「立即生成」/确认落位，缓存会让重载后卡/按钮缺场；单行读成本可忽略）。
 */
export function useBuildGate(projectId: string) {
  return useQuery({
    queryKey: queryKeys.projects.buildGate(projectId),
    queryFn: () => fetchBuildGate(projectId),
    staleTime: 0,
  });
}

/**
 * 确认 PRD，开始生成（付费轨首建点火）：POST /projects/{id}/prd/confirm——确认
 * 即派发、点击幂等（已点火 409 PRJ_017 不重复派发；失败重试走「继续生成」不重复
 * 确认）。成功即乐观登记编码 run 在途（run-start 随后到，回声幂等）＋失效读面与
 * 项目域（卡/门随点火隐藏）。
 */
export function useConfirmPrdGeneration(projectId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () =>
      api.post<GenerationStartResponse>(`/projects/${projectId}/prd/confirm`),
    onSuccess: (result) => {
      if (result?.runId) {
        useGenerationStore.getState().noteCoderRun(projectId, result.runId);
      }
      void queryClient.invalidateQueries({ queryKey: queryKeys.projects.all });
    },
    onError: (error) => {
      toast.error(errorText(error, "确认失败，请稍后重试"));
    },
  });
}
