import { useQuery } from "@tanstack/react-query";

import { queryKeys } from "@/lib/api/keys";
import { fetchPendingBalanceReminder } from "@/lib/projects/balance-reminder";

/**
 * 待决余额提醒卡读面（#537 计费③段间硬门，卡可回）：重载/回访重建余额提醒卡，
 * 充值回正后读面归 null（卡熄灭——billing.all 失效由充值成功承载，本键挂
 * billing 命名空间下吃同一失效）。staleTime 0——每次挂载即重查（被拦/放行都在
 * 服务端发生，单行读成本可忽略）。
 */
export function usePendingBalanceReminder(projectId: string) {
  return useQuery({
    queryKey: queryKeys.billing.balanceReminder(projectId),
    queryFn: () => fetchPendingBalanceReminder(projectId),
    staleTime: 0,
  });
}
