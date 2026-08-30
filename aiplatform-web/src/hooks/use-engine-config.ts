import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import { errorText } from "@/lib/api/api-error";
import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import type { components } from "@/lib/api/schema";

/**
 * 引擎全局配置（issue #56，CONTEXT「简易后台」）：
 * - 当前生效引擎 `GET /admin/engine-config`（从未配置返回注册表缺省 opencode）
 * - 切换 `PUT /admin/engine-config { engine }`（须 ∈ agent-engines 注册表，
 *   非法值 400 AGT_009；即时生效、持久化）。生效口径 = 新项目生效、存量项目
 *   保持创建时固化的引擎——确认文案在组件层明示。
 * 无 SSE 失效源——切换成功后就地失效重拉。
 */

type EngineConfigResponse = components["schemas"]["EngineConfigResponse"];

export function useEngineConfig() {
  return useQuery({
    queryKey: queryKeys.admin.engineConfig,
    queryFn: ({ signal }) => api.get<EngineConfigResponse>("/admin/engine-config", { signal }),
  });
}

export function useSwitchEngine() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (engine: string) =>
      api.put<EngineConfigResponse>("/admin/engine-config", { engine }),
    onSuccess: (_data, engine) => {
      void qc.invalidateQueries({ queryKey: queryKeys.admin.engineConfig });
      toast.success(`已切换生效引擎（${engine}）`);
    },
    onError: (error) => toast.error(errorText(error, "切换失败，请重试")),
  });
}
