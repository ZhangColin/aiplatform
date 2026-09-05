import { useQuery } from "@tanstack/react-query";
import { useEffect } from "react";

import { queryKeys } from "@/lib/api/keys";
import { fetchConversation } from "@/lib/projects/conversation";
import { useChatStore } from "@/lib/store/chat";

/**
 * 对话史水合（#89 对话史落库④）：对话 store 由内存态换 REST 水合——刷新 / 回访
 * 对话完整（作答不再「即逝」、收尾卡常驻）。数据到达即灌入 chat store（hydrate
 * 增量合并：新 run 的库块接管 live 片段、开放轮尾巴保留）；失效源 = 轮收口事件
 * （bridge 的 run-finish invalidate）与重连广谱失效。挂在对话区（CommandArea）。
 */
export function useConversation(projectId: string) {
  const query = useQuery({
    queryKey: queryKeys.conversation.of(projectId),
    queryFn: () => fetchConversation(projectId),
  });

  const entries = query.data;
  useEffect(() => {
    if (entries) useChatStore.getState().hydrate(projectId, entries);
  }, [projectId, entries]);

  return query;
}
