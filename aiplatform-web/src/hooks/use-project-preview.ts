import { useQuery } from "@tanstack/react-query";

import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/schema";
import { queryKeys } from "@/lib/api/keys";
import { useSseFallbackPolling } from "@/lib/sse/provider";

type ProjectPreviewResponse = components["schemas"]["ProjectPreviewResponse"];

/**
 * 系统预览地址（#22 片2-1 → #45 门禁解除；#105 URL 事件驱动）：GET
 * /api/projects/{id}/preview——后端探活工作区应用端口，通过（run 执行体已在 8081
 * 起服）才返回 URL，未就绪 503 WSP_012。URL 由切片收口的 preview-ready 事件
 * 推送（bridge setQueryData 写预览查询缓存），本 query 不再无条件 3s 轮询——
 * 轮询降级为 SSE 断线兜底：连接健康不空转（等事件推 URL），断线才按门控间隔
 * 重拉补齐（错过的事件靠重连广谱 invalidate 与兜底轮询兜住）。此后刷新由
 * generation store 预览纪元驱动 iframe 重挂（run 收口信号）。不自动重试：
 * WSP_012 是待期不是故障。
 */
export function useProjectPreview(projectId: string, active: boolean) {
  const fallbackPolling = useSseFallbackPolling("notification");
  return useQuery({
    queryKey: queryKeys.projects.preview(projectId),
    queryFn: ({ signal }) =>
      api.get<ProjectPreviewResponse>(`/projects/${projectId}/preview`, { signal }),
    enabled: active,
    retry: false,
    refetchInterval: fallbackPolling,
  });
}
