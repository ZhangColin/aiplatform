import { useQuery, useQueryClient } from "@tanstack/react-query";

import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import {
  normalizeProjectDetail,
  type ProjectDetailResponse,
} from "@/lib/projects/detail";
import { useSseFallbackPolling } from "@/lib/sse/provider";

export type { ProjectDetail } from "@/lib/projects/detail";

/**
 * 项目数据层（issue #17 清场后骨架）：详情查询 + 写操作 200 返回最新详情的统一
 * 收口（播种详情缓存再失效项目域，粗粒度失效顺带重拉列表等）；正确性始终以
 * REST 为准。
 */
export function useProject(projectId: string | undefined) {
  const fallbackPolling = useSseFallbackPolling("notification");
  return useQuery({
    queryKey: queryKeys.projects.detail(projectId ?? ""),
    queryFn: ({ signal }) =>
      api
        .get<ProjectDetailResponse>(`/projects/${projectId}`, { signal })
        .then(normalizeProjectDetail),
    enabled: projectId !== undefined,
    refetchInterval: fallbackPolling,
  });
}

/** 写操作 200 返回最新详情的统一收口：先播种详情缓存再失效项目域。 */
export function seedAndInvalidate(
  queryClient: ReturnType<typeof useQueryClient>,
  projectId: string,
  raw: ProjectDetailResponse,
): void {
  queryClient.setQueryData(queryKeys.projects.detail(projectId), normalizeProjectDetail(raw));
  void queryClient.invalidateQueries({ queryKey: queryKeys.projects.all });
}
