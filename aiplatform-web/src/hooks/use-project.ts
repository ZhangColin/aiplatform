import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import type { components } from "@/lib/api/schema";
import type { RenameProjectCommand } from "@/lib/projects/rename";
import { mapStagesToJourney } from "@/lib/main-chain/journey";
import {
  normalizeProjectDetail,
  type ProjectDetailResponse,
} from "@/lib/main-chain/project";
import { deriveStageProgress } from "@/lib/main-chain/stages";
import { useSseFallbackPolling } from "@/lib/sse/provider";

/**
 * 项目主链数据层（issue #19，共享层一份、双场景消费）：详情 / 门通过 / 门驳回 /
 * 预览。门操作 200 返回最新详情——先播种详情缓存再失效项目域（粗粒度失效顺带
 * 重拉列表徽章等）；正确性始终以 REST 为准。
 */

export type { ProjectDetail } from "@/lib/main-chain/project";

type ProjectPreviewResponse = components["schemas"]["ProjectPreviewResponse"];
type StageRejectCommand = components["schemas"]["StageRejectCommand"];

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

/** 门通过（无确认体，直接 POST）；成功 = 播种详情 + 失效项目域。 */
export function useApproveStage(projectId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () =>
      api.post<ProjectDetailResponse>(`/projects/${projectId}/stage/approve`),
    onSuccess: (detail) => seedAndInvalidate(queryClient, projectId, detail),
  });
}

/** 门驳回（reason 必填，表单侧空值拦截后才发）。 */
export function useRejectStage(projectId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (command: StageRejectCommand) =>
      api.post<ProjectDetailResponse>(`/projects/${projectId}/stage/reject`, command),
    onSuccess: (detail) => seedAndInvalidate(queryClient, projectId, detail),
  });
}

/**
 * 项目改名（issue #55，spec 0002 §4）：AI 取名不满意随时可改。200 返回最新
 * 详情——同门操作口径走 seedAndInvalidate（播种详情 + 失效项目域，列表 / 顶栏
 * 随刷新显示新名）；单账号场景不新增 SSE。
 */
export function useRenameProject(projectId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (command: RenameProjectCommand) =>
      api.post<ProjectDetailResponse>(`/projects/${projectId}/rename`, command),
    onSuccess: (detail) => seedAndInvalidate(queryClient, projectId, detail),
  });
}

/**
 * 写操作 200 返回最新详情的统一收口（门操作 / 归档 / 改名共用）：先播种详情
 * 缓存再失效项目域（粗粒度失效顺带重拉列表 / 徽章等）；正确性始终以 REST 为准。
 */
export function seedAndInvalidate(
  queryClient: ReturnType<typeof useQueryClient>,
  projectId: string,
  raw: ProjectDetailResponse,
): void {
  queryClient.setQueryData(queryKeys.projects.detail(projectId), normalizeProjectDetail(raw));
  void queryClient.invalidateQueries({ queryKey: queryKeys.projects.all });
}

/**
 * 预览地址（`GET …/preview` → `{url}`）：data = url 或 null。入口点亮的信号链 =
 * SSE preview-ready → 桥失效 → 重拉 200；未就绪期 4xx 由全局重试策略天然不重试
 * （ADR 0002），断线时走通知通道门控轮询兜底。
 */
export function useProjectPreview(projectId: string | undefined) {
  const fallbackPolling = useSseFallbackPolling("notification");
  return useQuery({
    queryKey: queryKeys.projects.preview(projectId ?? ""),
    queryFn: ({ signal }) =>
      api
        .get<ProjectPreviewResponse>(`/projects/${projectId}/preview`, { signal })
        .then((res) => res.url ?? null),
    enabled: projectId !== undefined,
    refetchInterval: fallbackPolling,
  });
}

/**
 * 详情 + 旅程推导一步到位（列表卡 / 右栏 / 工作台顶栏共用的消费收口）：
 * `stages[]` → 段序列推导 → 六步映射，各呈现点不再自行拼 derive + map 组合
 * （spec 0002 §5：映射表归通用层，呈现只消费）。
 */
export function useProjectJourney(projectId: string | undefined) {
  const query = useProject(projectId);
  const progress = query.data
    ? deriveStageProgress(query.data.stages, query.data.stage)
    : null;
  const steps = progress ? mapStagesToJourney(progress) : [];
  const current = steps.find((step) => step.status === "current");
  return { ...query, progress, steps, current };
}
