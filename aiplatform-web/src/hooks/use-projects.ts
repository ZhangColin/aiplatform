import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import { seedAndInvalidate } from "@/hooks/use-project";
import type { ProjectDetailResponse } from "@/lib/main-chain/project";
import {
  FILTER_STATUS,
  normalizeProjectSummary,
  visibleProjects,
  type ProjectListFilterKey,
  type ProjectResponse,
} from "@/lib/projects/list";

/**
 * 项目列表与归档（issue #20）。列表 `status` 为过滤视图（四态 Integer code，
 * 映射收在 lib/projects/list）；归档为单向终点——200 返回最新详情，播种 + 失效
 * 项目域（同门操作口径，use-project.ts 的 seedAndInvalidate）。
 */

/** 列表（过滤视图随 key 进缓存；「全部」不传 status + 本地过滤已归档）。 */
export function useProjectList(filter: ProjectListFilterKey = "all") {
  const status = FILTER_STATUS[filter];
  return useQuery({
    queryKey: queryKeys.projects.list(status),
    queryFn: ({ signal }) =>
      api
        .get<ProjectResponse[]>("/projects", { query: { status }, signal })
        .then((items) => (items ?? []).map(normalizeProjectSummary)),
  });
}

export type { ProjectSummary } from "@/lib/projects/list";

/** sidebar 项目列表 = 未归档口径（同列表页「全部」，spec 0002 §3）——user / dev 场景壳共用。 */
export function useSidebarProjects() {
  const { data } = useProjectList("all");
  return visibleProjects(data ?? [], "all");
}

/** 归档（重复归档 409 PRJ_013 由调用侧 toast 后端 message）。 */
export function useArchiveProject(projectId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () =>
      api.post<ProjectDetailResponse>(`/projects/${projectId}/archive`),
    onSuccess: (detail) => seedAndInvalidate(queryClient, projectId, detail),
  });
}
