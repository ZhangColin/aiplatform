import { useMutation, useQueryClient } from "@tanstack/react-query";

import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import type { components } from "@/lib/api/schema";

/**
 * 定稿（#294 稿卡显式动作，#291 REST 面的画布接线）：POST
 * /design-items/{ord}/finalize——候选中锁定一稿（path＝锚定形）。成功失效项目域
 * （件状态转已定稿进计划区、finalizedPath 透出定稿徽记）＋对话域（定稿收尾卡
 * 水合入流）＋文件树（成版提交后的树事实）；后续分岔的派发是服务端事实，前端
 * 零推测（重拉详情见分岔进展）。
 */
export function useFinalizeDesignItem(projectId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (input: { ord: number; path: string }) =>
      api.post<components["schemas"]["DesignFinalizedResponse"]>(
        `/projects/${projectId}/design-items/${input.ord}/finalize`,
        { path: input.path },
      ),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.projects.all });
      void queryClient.invalidateQueries({ queryKey: queryKeys.conversation.all });
    },
  });
}
