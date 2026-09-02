import { useQuery } from "@tanstack/react-query";

import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/schema";
import { queryKeys } from "@/lib/api/keys";
import { useSseFallbackPolling } from "@/lib/sse/provider";

type ProjectPreviewResponse = components["schemas"]["ProjectPreviewResponse"];

/** 无应用期间的探活续轮间隔（#45：后端探活通过才返回 URL，未就绪 503 WSP_012）。 */
const PROBE_INTERVAL_MS = 3000;

/**
 * 系统预览地址（#22 片2-1 → #45 门禁解除）：GET /api/projects/{id}/preview——
 * 后端探活工作区应用端口，通过（编码智能体已在 8081 起服）才返回 URL，未就绪
 * 503 WSP_012。run 开始即可调用（enabled 归 SystemPanel 的门禁推导）；未取到
 * URL 期间秒级续探、取到即停（此后刷新由 generation store 预览纪元驱动 iframe
 * 重挂，逐修改刷新归 #49）。不自动重试：WSP_012 是待期不是故障，重试退避只会
 * 拖慢轮询节拍。
 */
export function useProjectPreview(projectId: string, active: boolean) {
  const fallbackPolling = useSseFallbackPolling("notification");
  return useQuery({
    queryKey: queryKeys.projects.preview(projectId),
    queryFn: ({ signal }) =>
      api.get<ProjectPreviewResponse>(`/projects/${projectId}/preview`, { signal }),
    enabled: active,
    retry: false,
    refetchInterval: (query) => (query.state.data?.url ? fallbackPolling : PROBE_INTERVAL_MS),
  });
}
