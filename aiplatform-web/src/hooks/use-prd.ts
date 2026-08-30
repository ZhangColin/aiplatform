import { useQuery } from "@tanstack/react-query";

import { ApiError } from "@/lib/api/api-error";
import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import type { components } from "@/lib/api/schema";
import { useSseFallbackPolling } from "@/lib/sse/provider";

type PrdResponse = components["schemas"]["PrdResponse"];

/**
 * 当前版 PRD（issue #54，spec 0002 §4 / §6）：`GET …/prd` = 项目工作区
 * docs/PRD.md 直读（markdown + updatedAt，v1 无版本链只最新版）。**未产出是
 * 正常态**——后端以 404 PRJ_015 区分「工作区无该文件」与项目不存在（PRJ_001），
 * 这里把前者归一为 null（文档面板据 null 呈引导占位），其余错误照抛。
 * 实时性失效源 = 通知通道 document-updated（桥失效文档域）；断线走门控轮询兜底。
 */
export function usePrd(projectId: string | undefined) {
  const fallbackPolling = useSseFallbackPolling("notification");
  return useQuery({
    queryKey: queryKeys.documents.prd(projectId ?? ""),
    queryFn: ({ signal }) =>
      api
        .get<PrdResponse>(`/projects/${projectId}/prd`, { signal })
        .catch((error: unknown) => {
          if (error instanceof ApiError && error.code === "PRJ_015") return null;
          throw error;
        }),
    enabled: projectId !== undefined,
    refetchInterval: fallbackPolling,
  });
}
