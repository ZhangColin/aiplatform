import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import {
  sortDemandEntriesNewestFirst,
  type DemandPoolEntryResponse,
} from "@/lib/projects/demand-pool";

/**
 * 想法池（issue #20，spec 0002 §4）：列表新→旧 + 提交。提交纪律：只传
 * `content`，`kind` / `source` 一律缺省不传（后端缺省 = 用户需求，枚举 code
 * 不进消费逻辑）；成功后失效条目键重拉。
 */

export function useDemandPool(projectId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.projects.demandPool(projectId ?? ""),
    queryFn: ({ signal }) =>
      api
        .get<DemandPoolEntryResponse[]>(`/projects/${projectId}/demand-pool`, { signal })
        .then((items) => sortDemandEntriesNewestFirst(items ?? [])),
    enabled: projectId !== undefined,
  });
}

export function useAddDemandEntry(projectId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (content: string) =>
      api.post<DemandPoolEntryResponse>(`/projects/${projectId}/demand-pool`, {
        content,
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.projects.demandPool(projectId),
      });
    },
  });
}
