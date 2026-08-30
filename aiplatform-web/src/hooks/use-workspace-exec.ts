import { useMutation } from "@tanstack/react-query";

import { api } from "@/lib/api/client";
import {
  normalizeExecResult,
  type ExecResultResponse,
  type WorkspaceExecCommand,
} from "@/lib/workspace/exec";

/**
 * 工作区执行命令（issue #42，spec 0001 §5「终端」）：POST /workspaces/{workspaceId}/exec，
 * 请求体 = buildExecCommand 构造的 `{command}`，响应归化为 `ExecResult`
 * （stdout/stderr/exitCode 三字段必有）。非 0 退出码随结果正常返回（HTTP 200），
 * 是命令失败非环境故障——呈现层按 exitCode 判色；HTTP 4xx/5xx 才是环境故障走 toast。
 */

export function useWorkspaceExec(workspaceId: string | undefined) {
  return useMutation({
    mutationFn: (command: WorkspaceExecCommand) =>
      api
        .post<ExecResultResponse>(`/workspaces/${workspaceId}/exec`, command)
        .then(normalizeExecResult),
  });
}
