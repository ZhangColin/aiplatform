import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/schema";

/**
 * 首建派发门（#542 访谈②，ADR-0048/0049）：岔路口三选卡与 PRD 确认按钮的
 * 呈现条件单读点——两入口退役后首个构建 run 的点火归用户（试用态岔路口
 * 「立即生成」/付费态 PRD 确认按钮；快道收口观测直派、两读面缺席）。
 */

export type BuildGateResponse = components["schemas"]["BuildGateResponse"];

/** 派发门读面（null＝项目缺档——读面缺席语义，同拦截卡读面口径）。 */
export async function fetchBuildGate(projectId: string) {
  return api.get<BuildGateResponse>(`/projects/${projectId}/build-gate`);
}
