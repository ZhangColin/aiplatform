import { useQuery } from "@tanstack/react-query";

import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import type { components } from "@/lib/api/schema";

/**
 * 会话身份（A2 §3）：形状取 gen:api 生成物，accountId 为字符串 TSID（超 JS 安全整数），禁转 Number。
 * Required 收紧：spec 0004 §5 最小契约两字段必有；swagger（springdoc）未标 required，投影恢复必填。
 */
export type Me = Required<components["schemas"]["MeResponse"]>;

/**
 * 会话身份（spec 0004 §5）：staleTime Infinity——会话内不重拉，
 * 失效感知交给 401 全局出口（client.ts），此处不设 error 分支。
 */
export function useMe() {
  return useQuery({
    queryKey: queryKeys.me,
    queryFn: () => api.get<Me>("/me"),
    staleTime: Infinity,
  });
}
