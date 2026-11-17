import { useMutation, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import type { components } from "@/lib/api/schema";
import { errorText } from "@/lib/api/api-error";

type ContinueDiscussionResponse = components["schemas"]["ContinueDiscussionResponse"];

/**
 * 岔路口「继续讨论」引导流命令（#544 访谈③，ADR-0048 切片③——翻转门①）：随卡
 * 一次选定扣减源（source＝1 由我的账户付费／2 由本项目付费；空＝不改动既有选定——
 * 后台已充项目池的免选直翻转/到账后 settle 重判），服务端按所选池判定——源池余额
 * 为正即翻转回访谈（flipped），非正返回引导充值（rechargeRequired——源已选定，
 * 到账后空 source 重判即翻转、无需重走选择）。分岔编排（M01 先行/收银台开合）
 * 归卡组件，本 hook 只管命令与读面失效（卡随 build-gate 熄灭、额度读面/账单
 * 读面回源）。错误面如实 toast（BILL_009 指路登录等——服务端信封含处置语义）。
 */

/** 随卡选源（1=账户余额 2=项目余额；空＝settle 重判——不改动既有选定）。 */
export type ContinueDiscussionVars = { source?: 1 | 2 };

export function useContinueDiscussion(projectId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (vars: ContinueDiscussionVars) =>
      api.post<ContinueDiscussionResponse>(
        `/projects/${projectId}/fork/continue-discussion`,
        vars.source == null ? {} : { source: vars.source },
      ),
    onSuccess: () => {
      // 卡/确认门/额度/账单全走读面回源：翻转后岔路口卡熄灭（试用态不再成立）、
      // 「第 N/5」进度 chip 退场（额度解除）、账单读面刷新
      void queryClient.invalidateQueries({ queryKey: queryKeys.projects.all });
      void queryClient.invalidateQueries({ queryKey: queryKeys.billing.all });
    },
    onError: (error) => {
      toast.error(errorText(error, "操作失败，请稍后重试"));
    },
  });
}
