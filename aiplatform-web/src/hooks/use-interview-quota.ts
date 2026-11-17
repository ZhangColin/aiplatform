import { useQuery } from "@tanstack/react-query";

import { queryKeys } from "@/lib/api/keys";
import { fetchInterviewQuota } from "@/lib/projects/interview-quota";

/**
 * 五问额度读面（#538 访谈①）：「第 N/5」进度 chip 数据源——服务端单源，
 * 前端不自算。挂 conversation 命名空间：轮收口（run-finish）失效 conversation
 * 域时本键同失效（收口观测＝额度面变化点——第 5 问答完收口、PRD 产出退场）。
 * staleTime 0——访谈期逐轮都变，单行读成本可忽略。
 */
export function useInterviewQuota(projectId: string) {
  return useQuery({
    queryKey: queryKeys.conversation.quota(projectId),
    queryFn: () => fetchInterviewQuota(projectId),
    staleTime: 0,
  });
}
