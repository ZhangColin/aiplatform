import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import {
  fetchMyBilling,
  fetchProjectBilling,
  topupAccount,
  topupProject,
  validateAccessCode,
} from "@/lib/billing/api";
import { queryKeys } from "@/lib/api/keys";

/**
 * 模拟收银台与账单读面 hooks（#534 计费④）：读面挂 billing 域（充值到账即失效
 * ——append-only 账本无 SSE 桥，充值 mutation 是唯一写面）；充值 mutation 成功
 * 连带失效账单域与项目拦截卡（试用项目充值入账即选定项目源——拦截卡回读即显
 * 源池余额，卡可直确认）。错误面直出后端中文 message（BILL_019 指路 / BILL_020
 * 死钱防线如实呈现）。
 */

/** 我的账户账单（账户池余额＋流水）：未登录不发起（页面级登录门先行）。 */
export function useMyBilling(enabled: boolean) {
  return useQuery({
    queryKey: queryKeys.billing.account(),
    queryFn: fetchMyBilling,
    enabled,
  });
}

/** 项目账单（项目池余额＋计费态＋流水＋充值目标）：项目账单节随用随查。
 *  {@code enabled=false}＝账户上下文（收银台 projectId=null 时占位调用）——
 *  空 projectId 不发请求（bogus 404 面）。 */
export function useProjectBilling(projectId: string, enabled = true) {
  return useQuery({
    queryKey: queryKeys.billing.project(projectId),
    queryFn: () => fetchProjectBilling(projectId),
    enabled: enabled && projectId !== "",
  });
}

/** 准入码校验（收银台第一步分步闸）：错误就地呈现 message，不进 toast。 */
export function useValidateAccessCode() {
  return useMutation({
    mutationFn: (code: string) => validateAccessCode(code),
  });
}

/** 充值命令：上下文归调用侧（账户上下文走 topupAccount；项目上下文走
 *  topupProject——目标池已由账单读面 checkoutTarget 推导，非源池选项不呈现）。 */
export type CheckoutTopupVars = {
  projectId: string | null;
  amountFen: number;
  accessCode: string;
};

export function useCheckoutTopup() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (vars: CheckoutTopupVars) =>
      vars.projectId == null
        ? topupAccount(vars.amountFen, vars.accessCode)
        : topupProject(vars.projectId, vars.amountFen, vars.accessCode),
    onSuccess: (_receipt, vars) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.billing.all });
      if (vars.projectId != null) {
        // 充值入账即选定项目源：拦截卡回读显源池余额（卡可直确认——销售动线同口径）
        void queryClient.invalidateQueries({
          queryKey: queryKeys.projects.intercept(vars.projectId),
        });
      }
    },
    // 错误面不进 toast（弹窗内联呈现——message 含处置语义：BILL_019 指路 /
    // BILL_020 死钱防线 / BILL_002 金额守卫）
  });
}
