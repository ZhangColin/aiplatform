import { useMutation } from "@tanstack/react-query";
import { toast } from "sonner";

import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/schema";
import { errorText } from "@/lib/api/api-error";
import { useWorkMessageStore } from "@/lib/store/work-message";

type PermissionAnswerCommand = components["schemas"]["PermissionAnswerCommand"];

/**
 * 权限确认卡作答（#83 作答通道分家）：POST /api/projects/{id}/permissions/{ref}/
 * answer，ref = 挂起事件 engineRef；body 只带 runId（串卡校验）+ 批准位——恢复
 * 私货不回传（挂起事实在平台侧）。与问答作答（useAnswerQuestion，答复文本）通道
 * 分离、互不串扰。乐观更新在 work-message store（确认卡转已批/已拒；permission-
 * resolved 事件双到达幂等；失败回滚重开按钮）。
 */
export function useAnswerPermission(projectId: string) {
  return useMutation({
    mutationFn: (input: { ref: string; command: PermissionAnswerCommand }) =>
      api.post<void>(`/projects/${projectId}/permissions/${input.ref}/answer`, input.command),
    onMutate: ({ ref, command }) => {
      useWorkMessageStore
        .getState()
        .resolvePermission(projectId, ref, command.approved ? "approved" : "denied");
    },
    onError: (error, input) => {
      // 回滚确认卡（PRJ_027 过期卡等）：重开按钮如实重试
      useWorkMessageStore.getState().resolvePermission(projectId, input.ref, "pending");
      toast.error(errorText(error, "作答失败，请刷新后重试"));
    },
  });
}
