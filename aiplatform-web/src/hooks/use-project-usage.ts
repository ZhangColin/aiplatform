import { useQuery } from "@tanstack/react-query";

import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import type { components } from "@/lib/api/schema";
import type { CostBuckets, IterationUsage } from "@/lib/projects/usage";

/**
 * 项目用量（issue #20 简版 → #24 升级，spec 0002 §4）：`total` + `cost`
 * 平台成本分桶 + `unpriced` 未配价标注 + `byRole` + `byIteration` + `byModel`
 * （后端 #29 已收口）。右栏挂载即拉，无独立失效触发。
 */

type ProjectUsageResponse = components["schemas"]["ProjectUsageResponse"];

/** 消费口径：分组缺省归空序列/空分桶（后端字段全可缺）。 */
export type ProjectUsage = {
  total?: components["schemas"]["TokenUsage"];
  cost: CostBuckets;
  unpriced: components["schemas"]["UnpricedUsage"][];
  byModel: components["schemas"]["ModelUsage"][];
  byRole: components["schemas"]["RoleUsage"][];
  byIteration: IterationUsage[];
};

export function useProjectUsage(projectId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.projects.usage(projectId ?? ""),
    queryFn: ({ signal }) =>
      api
        .get<ProjectUsageResponse>(`/projects/${projectId}/usage`, { signal })
        .then(
          (raw): ProjectUsage => ({
            total: raw.total,
            cost: raw.cost ?? {},
            unpriced: raw.unpriced ?? [],
            byModel: raw.byModel ?? [],
            byRole: raw.byRole ?? [],
            byIteration: raw.byIteration ?? [],
          }),
        ),
    enabled: projectId !== undefined,
  });
}
