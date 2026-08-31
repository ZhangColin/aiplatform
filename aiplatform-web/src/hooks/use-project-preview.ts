import { useQuery } from "@tanstack/react-query";

import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/schema";
import { queryKeys } from "@/lib/api/keys";
import { useSseFallbackPolling } from "@/lib/sse/provider";

type ProjectPreviewResponse = components["schemas"]["ProjectPreviewResponse"];

/**
 * 系统预览地址（#22 片2-1）：GET /api/projects/{id}/preview——工作区容器端口真实
 * 暴露（docker publish）后的可访问 URL。生成收口（ready）才启用；run 完成信号
 * （generation store 的预览纪元）在 SystemPanel 侧 invalidate 本域 + 以
 * url+epoch 为 iframe key 重挂。
 */
export function useProjectPreview(projectId: string, enabled: boolean) {
  const fallbackPolling = useSseFallbackPolling("notification");
  return useQuery({
    queryKey: queryKeys.projects.preview(projectId),
    queryFn: ({ signal }) =>
      api.get<ProjectPreviewResponse>(`/projects/${projectId}/preview`, { signal }),
    enabled,
    refetchInterval: fallbackPolling,
  });
}
