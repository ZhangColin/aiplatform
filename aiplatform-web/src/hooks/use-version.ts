import { useMutation } from "@tanstack/react-query";
import { toast } from "sonner";

import { api } from "@/lib/api/client";
import { errorText } from "@/lib/api/api-error";

/**
 * 版本动作数据层（#92 查看当时 + #93 回滚到此）：收尾卡上「查看当时 / 回滚到此」
 * 两个动作的请求面。查看当时 = 起快照容器（POST view，返回 viewId + 预览 URL，
 * 逛完经 DELETE 关闭销毁）；回滚到此 = 追加新版本（POST rollback，只回代码不回
 * 数据）。回滚成功只 toast 提示——版本动作不产对话条目，预览刷新归下一轮 run
 * （回滚不代起应用）。
 */

/** 查看会话起服结果（#92）：viewId 是关闭动作的寻址锚，previewUrl 是快照预览。 */
export type VersionViewStart = {
  viewId: string;
  previewUrl: string;
};

/** 回滚追加出的新版本（#93）：runId 空、rollbackFrom 锚定源版本。 */
export type RolledVersion = {
  commitHash: string;
  subject: string;
  rollbackFrom?: string | null;
};

/** 起「查看当时」快照容器。 */
export function useStartVersionView(projectId: string) {
  return useMutation({
    mutationFn: (version: string) =>
      api.post<VersionViewStart>(`/projects/${projectId}/versions/${version}/view`),
  });
}

/** 关闭「查看当时」会话（销毁快照容器，用完即销毁）。 */
export function useStopVersionView(projectId: string) {
  return useMutation({
    mutationFn: ({ version, viewId }: { version: string; viewId: string }) =>
      api.delete<void>(`/projects/${projectId}/versions/${version}/view/${viewId}`),
  });
}

/** 回滚到此：追加新版本（历史只追加不改写、只回代码不回数据）。 */
export function useRollbackVersion(projectId: string) {
  return useMutation({
    mutationFn: (version: string) =>
      api.post<RolledVersion>(`/projects/${projectId}/versions/${version}/rollback`),
    onSuccess: () => {
      toast.success("已回滚到该版本，系统代码已复原");
    },
    onError: (error) => {
      toast.error(errorText(error, "回滚失败，请稍后重试"));
    },
  });
}
