import { useMutation, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import type { components } from "@/lib/api/schema";
import { errorText } from "@/lib/api/api-error";

type PrdVersionResponse = components["schemas"]["PrdVersionResponse"];

/**
 * 确认此版 PRD（#330「确认即成版」＋#332 生成加门）：POST …/prd/confirm——把当时
 * 工作区 PRD 全文冻结为不可变版本快照，并在确认成版后由服务端自动派首个 Demo run
 * （「确认此版 PRD 并生成 Demo」是首个构建 run 的唯一触发）。重复确认（无新草稿）
 * 幂等返回既有版。两级门拦截（PRJ_053 阻碍核心流程缺项）以数字业务码回，此处只
 * 如实呈现错误文案。成功即失效 PRD 文档域＋项目域（版本徽标 / 四态投影刷新）。
 */
export function useConfirmPrd(projectId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () =>
      api.post<PrdVersionResponse>(`/projects/${projectId}/prd/confirm`),
    onSuccess: (result) => {
      toast.success(
        result?.versionNo
          ? `已确认 v${result.versionNo}，正在生成 Demo`
          : "已确认本版 PRD，正在生成 Demo",
      );
      void queryClient.invalidateQueries({
        queryKey: queryKeys.documents.prd(projectId),
      });
      void queryClient.invalidateQueries({ queryKey: queryKeys.projects.all });
    },
    onError: (error) => {
      toast.error(errorText(error, "确认失败，请稍后重试"));
    },
  });
}
