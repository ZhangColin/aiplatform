import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/schema";

/**
 * 待决试用拦截卡读口（#533 计费②门②，卡可回）：GET /api/projects/{id}/intercept
 * ——重载/回访重建拦截卡（live 事件经 SSE 桥直写 chat store，本读面只承载水合缺
 * 口的重建）；无待决/已付费 data=null。
 */

/**
 * 拦截卡响应（swagger InterceptCardResponse 信封解包后的形状——生成物单一事实
 * 源，同 ConversationEntryResponse 先例）。interceptId 为 string——后端 Long→
 * String 序列化防 JS 精度丢失（同对话史口径，勿转 Number）；deductionSource /
 * sourcePoolBalance / runId 生成物不带 null 与运行时漂移（Jackson 可空字段输出
 * null），本地按同口径拓宽。
 */
export type InterceptCardResponse = Omit<
  components["schemas"]["InterceptCardResponse"],
  "deductionSource" | "sourcePoolBalance" | "runId"
> & {
  deductionSource?: number | null;
  sourcePoolBalance?: number | null;
  runId?: string | null;
};

/** 读面：无待决/已付费归 null（同「未产出 404 归 null」既有口径）。 */
export async function fetchPendingIntercept(
  projectId: string,
): Promise<InterceptCardResponse | null> {
  const card = await api.get<InterceptCardResponse | null>(
    `/projects/${projectId}/intercept`,
  );
  return card && card.interceptId ? card : null;
}
