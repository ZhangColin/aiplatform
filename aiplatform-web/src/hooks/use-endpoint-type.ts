import { useMutation, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import { api } from "@/lib/api/client";
import { errorText } from "@/lib/api/api-error";
import { seedAndInvalidate } from "@/hooks/use-project";
import type { ProjectDetailResponse } from "@/lib/projects/detail";
import type { components } from "@/lib/api/schema";

/** 切换终点类型载荷（#285）：scopeType/scopePages 只在系统→设计类切换时携带。 */
export type SwitchEndpointTypePayload = components["schemas"]["SwitchEndpointTypeCommand"];

/**
 * 切换项目终点类型（#285 设置 tab 终点控件＝项目内唯一变更位）：下单前可变、
 * 下单即冻结（ORD_006 由后端拦，取消订单即解冻）。200 返回最新详情——播种 +
 * 失效项目域；切换后 PRD 清单章重产走主智能体重产轮，PRD 面经 document-updated
 * 失效自愈，本动作不直接碰文档域。
 */
export function useSwitchEndpointType(projectId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (payload: SwitchEndpointTypePayload) =>
      api.post<ProjectDetailResponse>(`/projects/${projectId}/endpoint-type`, payload),
    onSuccess: (detail) => {
      seedAndInvalidate(queryClient, projectId, detail);
      toast.success("终点类型已切换，PRD 正在随之调整");
    },
    onError: (error) => {
      toast.error(errorText(error, "切换失败，请稍后重试"));
    },
  });
}
