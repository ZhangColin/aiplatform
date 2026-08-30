import { useQuery } from "@tanstack/react-query";

import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import { normalizeAccount, type AccountResponse } from "@/lib/tasks/task";

/**
 * 账号列表（issue #22，spec 0003 §4）：成员页只读表格 + dev 建任务指派下拉
 * 共用一份（`GET /api/accounts`，建档顺序）。无 SSE 失效源——成员变动靠重新
 * 进入页面 / 重连广谱失效自然刷新。
 */
export function useAccounts() {
  return useQuery({
    queryKey: queryKeys.accounts.all,
    queryFn: ({ signal }) =>
      api
        .get<AccountResponse[]>("/accounts", { signal })
        .then((items) => (items ?? []).map(normalizeAccount)),
  });
}
