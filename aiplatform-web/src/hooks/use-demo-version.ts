import { useQuery } from "@tanstack/react-query";

import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import type { components } from "@/lib/api/schema";

type DemoVersionSummary = components["schemas"]["DemoVersionSummaryResponse"];

/**
 * Demo 构建版本列表（#333）：versionNo 倒序（最新在前）、空列表＝从未成版。
 * 版本 chip（最新版「Demo vN · 基于 PRD vM 快照」）与阶段门「Demo 已确认」
 * （确认标记行 confirmedAt）的事实源。刷新时机＝编码 run 收口（run-finish 失效
 * projects 域——收口自动成版即新版本行落库）。
 */
export function useDemoVersions(projectId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.projects.demoVersions(projectId ?? ""),
    queryFn: ({ signal }) =>
      api.get<DemoVersionSummary[]>(`/projects/${projectId ?? ""}/demo-versions`, { signal }),
    enabled: projectId !== undefined,
  });
}
