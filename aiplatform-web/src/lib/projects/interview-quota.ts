import { api } from "@/lib/api/client";

/**
 * 五问额度读口（#538 访谈①，ADR-0048「第 N/5」进度 chip 的服务端单源）：
 * GET /api/projects/{id}/interview-quota——试用态访谈期返回 {已答缺口数, 5}；
 * 付费态 / PRD 已产出 / 已生成（非访谈期）data=null（chip 不呈现）。
 * 前端不自算：进度数与额度收口判定同读服务端本口。
 */

/**
 * 五问额度响应（GET 信封解包后的形状——同 BalanceReminderCardResponse 先例；
 * 下一次 gen:api 时并入 components["schemas"]["InterviewQuotaResponse"]，本地
 * 先行同形 hand-write）。
 */
export type InterviewQuotaResponse = {
  answered: number;
  total: number;
};

/** 读面：非访谈期归 null（chip 不呈现）。 */
export async function fetchInterviewQuota(
  projectId: string,
): Promise<InterviewQuotaResponse | null> {
  return api.get<InterviewQuotaResponse | null>(`/projects/${projectId}/interview-quota`);
}
