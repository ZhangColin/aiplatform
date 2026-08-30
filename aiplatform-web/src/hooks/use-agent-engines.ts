import { useQuery } from "@tanstack/react-query";

import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import type { components } from "@/lib/api/schema";

/**
 * 引擎能力矩阵（spec 0002 §3.1）：GET /api/agent-engines → EngineInfo[]（name /
 * label / questionSupported / permissionSupported / note；显式注册表全量，换引擎 =
 * 后端登记、前端自动出现）。#51 起创建表单不再选引擎（后台统一定），消费方 =
 * 简易后台引擎配置页（#56，端点未就绪时的只读展示源）。无 SSE 失效源——随页面
 * 进入 / 重连广谱失效刷新。
 */

type EngineInfo = components["schemas"]["EngineInfo"];

export function useAgentEngines() {
  return useQuery({
    queryKey: queryKeys.agentEngines,
    queryFn: ({ signal }) => api.get<EngineInfo[]>("/agent-engines", { signal }),
  });
}
