import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/schema";

/**
 * 模拟收银台与账单读面（#534 计费④）：薄 API 函数——类型一律取 gen:api 生成物
 * （CheckoutController / MeBillingController / ProjectBillingController 契约），
 * 请求形状即 swagger paths 契约（ADR 0002）。金额全线 Long 分（禁转 Number 运算
 * 精度事故——分→元呈现归消费端）。
 */

export type AccountBillingView = components["schemas"]["AccountBillingView"];
export type ProjectBillingView = components["schemas"]["ProjectBillingView"];
export type BillingEntryView = components["schemas"]["BillingEntryView"];
export type CheckoutReceipt = components["schemas"]["CheckoutReceipt"];

/**
 * 充值准入码校验（收银台第一步）：正确码 200 进金额选择；错误/缺失 400
 * BILL_019（信封数字码 8019——message 指路联系平台，如实引导归消费端呈现）。
 */
export function validateAccessCode(code: string): Promise<unknown> {
  return api.post("/checkout/access-code", { code });
}

/** 账户上下文充值（充「我的账户」池——我的账户页入口）。 */
export function topupAccount(amountFen: number, accessCode: string): Promise<CheckoutReceipt> {
  return api.post("/checkout/topup", { accessCode, amountFen });
}

/** 项目上下文充值（充项目池——项目账单/拦截卡入口；owner 守卫面）。 */
export function topupProject(
  projectId: string,
  amountFen: number,
  accessCode: string,
): Promise<CheckoutReceipt> {
  return api.post(`/projects/${projectId}/billing/topup`, { accessCode, amountFen });
}

/** 我的账户账单（账户池余额＋流水；无账户会话 401——消费端按登录态 enable）。 */
export function fetchMyBilling(): Promise<AccountBillingView> {
  return api.get("/me/billing", { silentUnauthorized: true });
}

/** 项目账单（项目池余额＋计费态＋流水＋收银台充值目标）。 */
export function fetchProjectBilling(projectId: string): Promise<ProjectBillingView> {
  return api.get(`/projects/${projectId}/billing`);
}
