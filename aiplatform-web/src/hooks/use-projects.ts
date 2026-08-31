import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import { seedAndInvalidate } from "@/hooks/use-project";
import type { ProjectDetailResponse } from "@/lib/projects/detail";
import { normalizeProjectSummary, type ProjectResponse } from "@/lib/projects/list";

/**
 * 项目列表与归档（issue #21 四态重组）。列表全量拉取一份（v1 不分页），四态
 * 过滤与归档折叠在消费端本地分区（lib/projects/list）；归档为单向终点——200
 * 返回最新详情，播种 + 失效项目域（use-project.ts 的 seedAndInvalidate）。
 */

/** 列表（全量；过滤视图由消费端 projectListSections 分区）。 */
export function useProjectList() {
  return useQuery({
    queryKey: queryKeys.projects.list(),
    queryFn: ({ signal }) =>
      api
        .get<ProjectResponse[]>("/projects", { signal })
        .then((items) => (items ?? []).map(normalizeProjectSummary)),
  });
}

export type { ProjectSummary } from "@/lib/projects/list";

/** 首页「最近的项目」= 未归档项目（三活态并集，已归档只留在列表页折叠分组）。 */
export function useRecentProjects() {
  const { data } = useProjectList();
  return (data ?? []).filter((p) => !p.archived);
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
