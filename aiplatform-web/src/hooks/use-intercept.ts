import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import type { components } from "@/lib/api/schema";
import { errorText } from "@/lib/api/api-error";
import { fetchPendingIntercept } from "@/lib/projects/intercept";
import { useChatStore } from "@/lib/store/chat";

/**
 * 试用拦截卡两接口（#533 计费②门②）：读面（卡可回——重载/回访重建，挂对话区）
 * 与确认扣减（翻转门②——确认即翻转付费并立即受理原被拦输入，响应 runId＝受理
 * 运行标识挂 SSE）。
 */

type InterviewTurnResponse = components["schemas"]["InterviewTurnResponse"];

/**
 * 待决拦截卡读面：staleTime 0——每次挂载即重查（被拦/确认/翻转都在服务端发生，
 * 缓存一分钟会让重载后卡重建缺场；单行读，成本可忽略）。
 */
export function usePendingIntercept(projectId: string) {
  return useQuery({
    queryKey: queryKeys.projects.intercept(projectId),
    queryFn: () => fetchPendingIntercept(projectId),
    staleTime: 0,
  });
}

/** 确认命令：source＝随卡首次选源（1=账户余额 2=项目余额；空＝不改动既有选定——
 *  后台已充项目池的免选直确认）；interceptId 供落定卡面（卡转已确认回执）。 */
export type ConfirmInterceptVars = { interceptId: string; source?: 1 | 2 };

export function useConfirmIntercept(projectId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (vars: ConfirmInterceptVars) =>
      api.post<InterviewTurnResponse>(
        `/projects/${projectId}/intercept/confirm`,
        vars.source == null ? {} : { source: vars.source },
      ),
    onSuccess: (result, vars) => {
      // 卡落定（只读回执）＋受理 run 登记（run-start 回声去重锚）；读面失效——
      // 服务端卡已消费，重载不再重建
      useChatStore.getState().settleIntercept(projectId, vars.interceptId);
      if (result?.runId) useChatStore.getState().markRunIngested(projectId, result.runId);
      void queryClient.invalidateQueries({ queryKey: queryKeys.projects.intercept(projectId) });
    },
    // 错误面如实上卡旁提示（BILL_009 指路登录 / BILL_011 充值引导 / BILL_017
    // 被取代——服务端信封 message 已含处置语义），卡留待决可改口
    onError: (error) => {
      toast.error(errorText(error, "确认失败，请稍后重试"));
    },
  });
}
