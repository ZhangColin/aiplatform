import { useQuery } from "@tanstack/react-query";

import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import type { components } from "@/lib/api/schema";
import { normalizeProjectFiles } from "@/lib/projects/files";
import { useSseFallbackPolling } from "@/lib/sse/provider";

type ProjectFilesResponse = components["schemas"]["ProjectFilesResponse"];

/**
 * 项目文件树（#27 文件模式）：`GET …/files` = 交付文件只读清单（工作区剔除
 * data/、.platform/、node_modules/、.env——与源码包同口径），只列文件、目录
 * 由前端合成（files.ts）；响应经 normalizeProjectFiles 归一为消费口径。实时性
 * 搭 projects 域粗粒度失效的现成车：编码 run 收口（run-finish）与 PRD 写出
 * （document-updated）都会失效整域——生成/修正后文件树即反映最新工作区。
 * 断线走门控轮询兜底。
 */
export function useProjectFiles(projectId: string | undefined) {
  const fallbackPolling = useSseFallbackPolling("notification");
  return useQuery({
    queryKey: queryKeys.projects.files(projectId ?? ""),
    queryFn: async ({ signal }) => {
      const raw = await api.get<ProjectFilesResponse>(`/projects/${projectId}/files`, { signal });
      return normalizeProjectFiles(raw);
    },
    enabled: projectId !== undefined,
    refetchInterval: fallbackPolling,
  });
}
