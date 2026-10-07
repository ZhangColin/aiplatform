import { useMutation, useQueryClient } from "@tanstack/react-query";

import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";

/**
 * 悬卡删除（#293 画布整理）：DELETE /design-drafts——真删工作区文件（候选可删、
 * 定稿不可删，守卫归后端）。成功失效文件树域即可——画布稿卡以树为存在性正本
 * （删稿即消卡），对话史不回写（收尾卡稿清单是历史事实）。
 */
export function useDeleteDesignDraft(projectId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (path: string) =>
      api.delete<void>(`/projects/${projectId}/design-drafts`, { query: { path } }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.projects.files(projectId) });
    },
  });
}
