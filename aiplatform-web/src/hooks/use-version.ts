import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/schema";
import { errorText } from "@/lib/api/api-error";
import { queryKeys } from "@/lib/api/keys";
import { useVersionRecordStore } from "@/lib/store/version-record";

/**
 * 版本数据层（#626 单轴时间线 + #627 异步回滚）：时间线读面（git log 即版本序列）
 * 与「回到此版」动作面。回滚进度经工作消息起止呈现（SSE），成功由 run-finish
 * 失效项目域刷新时间线/预览，失败经 run-failed 明确展示。
 */

/** 版本行（#626 版本层 git log 投影）。 */
type GitVersion = components["schemas"]["VersionResponse"];
/** 回到某版受理回执（#627）：runId＝SSE 锚，label＝版本标签（回到 vN）。 */
type RollbackStart = components["schemas"]["VersionRollbackStartResponse"];

/**
 * 收口版本表（#626：git log 即版本序列，新→旧）。单轴时间线（
 * {@code VersionTimelineSheet}）的唯一数据源。
 */
export function useGitVersions(
  projectId: string,
  options?: { enabled?: boolean },
) {
  return useQuery({
    queryKey: queryKeys.projects.versions(projectId),
    queryFn: ({ signal }) =>
      api.get<GitVersion[]>(`/projects/${projectId}/versions`, { signal }),
    enabled: options?.enabled ?? true,
  });
}

/**
 * 确认当前版本用于签约（#629，ADR-0056 商业面改接）：把项目确认指针指向该用户面
 * 版本（换选＝指针随移、重复确认同版幂等）。成功即失效整项目域——时间线 confirmed
 * 标位、阶段门「Demo 已确认」、下单前置「存在已确认用户面版本」同源刷新。
 */
export function useConfirmVersion(projectId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (versionNo: number) =>
      api.post<GitVersion>(`/projects/${projectId}/versions/${versionNo}/confirm`),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.projects.all });
    },
    onError: (error) => {
      toast.error(errorText(error, "确认失败，请稍后重试"));
    },
  });
}

/**
 * 回到某版（#627 异步回滚）：POST 立即受理（返回 runId＋版本标签），进度经工作
 * 消息起止呈现（SSE「回到版本 vN」）；成功由 run-finish 失效项目域刷新时间线与
 * 预览，失败经 run-failed 明确展示（含两条恢复路）。受理成功即收起时间线抽屉让
 * 用户回到主视图看进度；同步拒绝（版本不存在/已归档/在途）走 toast。
 */
export function useRollbackVersion(projectId: string) {
  const closeVersionRecord = useVersionRecordStore((s) => s.closeVersionRecord);
  return useMutation({
    mutationFn: (version: string) =>
      api.post<RollbackStart>(`/projects/${projectId}/versions/${version}/rollback`),
    onSuccess: (result) => {
      closeVersionRecord();
      if (result?.label) toast.info(`正在${result.label}，完成后会自动刷新`);
    },
    onError: (error) => {
      toast.error(errorText(error, "回滚失败，请稍后重试"));
    },
  });
}
