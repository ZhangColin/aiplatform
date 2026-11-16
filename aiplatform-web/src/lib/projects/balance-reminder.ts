import { api } from "@/lib/api/client";

/**
 * 待决余额提醒卡读口（#537 计费③段间硬门，卡可回）：GET
 * /api/projects/{id}/balance-reminder——重载/回访重建余额提醒卡（live 事件经
 * SSE 桥直写 chat store，本读面只承载水合缺口的重建）；无待决（充值回正已放行/
 * 试用项目门恒放行）data=null。
 */

/**
 * 余额提醒卡响应（GET 信封解包后的形状——同 InterceptCardResponse 先例。
 * #537 新增端点：生成物 schema.d.ts 在下一次 gen:api 时并入
 * components["schemas"]["BalanceReminderCardResponse"]，本地先行同形 hand-write）。
 * holdId 为 string——后端 Long→String 序列化防 JS 精度丢失；可空字段 Jackson
 * 输出 null，本地按同口径拓宽为可选。
 */
export type BalanceReminderCardResponse = {
  holdId?: string | null;
  lane?: string | null;
  prompt?: string | null;
  designItem?: number | null;
  sourcePoolBalance?: number | null;
  runId?: string | null;
};

/** 读面：无待决归 null（同拦截卡读面口径）。 */
export async function fetchPendingBalanceReminder(
  projectId: string,
): Promise<BalanceReminderCardResponse | null> {
  const card = await api.get<BalanceReminderCardResponse | null>(
    `/projects/${projectId}/balance-reminder`,
  );
  return card && (card.holdId || card.runId) ? card : null;
}
