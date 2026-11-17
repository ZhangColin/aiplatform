import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import { seedAndInvalidate } from "@/hooks/use-project";
import type { ProjectDetailResponse } from "@/lib/projects/detail";
import { normalizeProjectSummary, type ProjectResponse } from "@/lib/projects/list";

/**
 * 项目列表与归档。列表全量拉取一份（v1 不分页），P08 生命周期阶段、最近更新
 * 排序与归档折叠在消费端本地推导（lib/projects/list）；归档为单向终点——200
 * 返回最新详情，播种 + 失效项目域（use-project.ts 的 seedAndInvalidate）。
 */

/**
 * 列表（全量；P08 阶段与排序由消费端 lib/projects/list 推导）。{@code enabled=false}
 * 供匿名可逛面（首页「只逛零足迹」）：无凭证 cookie 时背景查询不发请求，
 * 401 出口也就无从弹登录框。{@code silentUnauthorized=true} 供首页背景查询
 *（{@link useRecentProjects}）：cookie 存在但服务端不认（库重建/访客保留期
 * 过期/凭证损坏）时 401 是「无有效会话」的答案、不是用户动作被拦——不发
 * 全局出口事件、不弹 M01（同 useMe／SSE 探针口径）。列表页主查询保持
 * loud：死凭证访客主动进「我的项目」时仍由 M01 承接登录引导。
 */
export function useProjectList(enabled = true, silentUnauthorized = false) {
  return useQuery({
    queryKey: queryKeys.projects.list(),
    queryFn: ({ signal }) =>
      api
        .get<ProjectResponse[]>("/projects", { signal, silentUnauthorized })
        .then((items) => (items ?? []).map(normalizeProjectSummary)),
    enabled,
  });
}

export type { ProjectSummary } from "@/lib/projects/list";

/**
 * 首页「最近的项目」= 未归档项目（已归档只留在列表页）。
 * {@code enabled=false}＝匿名首访：不发列表请求、无草稿恢复横幅。
 *
 * <p>背景查询恒定静默 401（{@code silentUnauthorized}）：首页是匿名可逛面，
 * cookie 在但已失效（库重建／保留期过期／损坏）时，全量列表 401 只代表
 * 「当前无有效会话」——头像位照常呈现「登录」入口，不劫持首页弹 M01
 *（死 cookie 不会被 401 响应清除，loud 形态下每次进站必弹、无自愈）。</p>
 */
export function useRecentProjects(enabled = true) {
  const { data } = useProjectList(enabled, true);
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
