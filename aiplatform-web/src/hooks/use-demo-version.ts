import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import { api } from "@/lib/api/client";
import { errorText } from "@/lib/api/api-error";
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

/**
 * 确认 Demo 版本（#333 选定动作，#335 起成为下单入口的门开动作——「确认此
 * 版本，准备签约」）：既有版本上打确认标记（不产生新版本号，换选＝指针随移）。
 * 成功即失效整项目域（版本清单 confirmedAt 投影、阶段门 demoConfirmed、下单
 * 前置门 ORD_019 解锁同一事实面）。
 */
export function useConfirmDemoVersion(projectId: string | undefined) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (versionNo: number) =>
      api.post<DemoVersionSummary>(`/projects/${projectId ?? ""}/demo-versions/${versionNo}/confirm`),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.projects.all });
    },
    onError: (error) => {
      toast.error(errorText(error, "确认失败，请稍后重试"));
    },
  });
}
