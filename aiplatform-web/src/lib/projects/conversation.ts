import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/schema";
import type { HydratedEntry } from "@/lib/store/chat";

/**
 * 对话史读口（#89 对话史落库④，前端水合源）：GET /api/projects/{id}/conversation
 * ——对话面全量按写入序（id 升序 = 对话序）返回；过程明细（解说段/动作卡流水）
 * 不在其中（收尾卡已是凝聚物）。载荷原样透传给 chat store 水合（question/closing
 * 为事件载荷 JSON 原样，收窄归消费端）。
 */

/**
 * 对话史条目响应（swagger ConversationEntryResponse 信封解包后的形状）。id 为
 * string ——后端全局 Long→String 序列化防 JS 精度丢失（2026-09-10 回归锚：schema
 * 生成的 `number` 与运行时漂移，勿据其写 typeof 守卫）。
 */
export type ConversationEntryResponse = Omit<
  components["schemas"]["ConversationEntryResponse"],
  "id"
> & { id?: string | number };

/** kind Integer code → 消费口径（正本 = ConversationEntryKind；1=user 2=agent 3=question 4=answer 5=closing 6=guide）。 */
const ENTRY_KINDS: Record<number, HydratedEntry["kind"]> = {
  1: "user",
  2: "agent",
  3: "question",
  4: "answer",
  5: "closing",
  6: "guide",
};

/** 响应 → 水合载荷（kind 由 Integer code 收窄；未知 code 条目弃守——契约演进的容错面）。 */
export function toHydratedEntries(raw: ConversationEntryResponse[]): HydratedEntry[] {
  return raw.flatMap((entry) => {
    const kind = entry?.id != null ? ENTRY_KINDS[entry.kind ?? -1] : undefined;
    if (kind === undefined) return [];
    return [{
      id: String(entry.id),
      kind,
      runId: entry.runId ?? undefined,
      text: entry.text ?? undefined,
      question: entry.question ?? undefined,
      closing: entry.closing ?? undefined,
      attachments: entry.attachments ?? undefined,
      answered: entry.answered === true,
    }];
  });
}

/** 拉取项目对话史（全量有序——写入序即对话序）。 */
export async function fetchConversation(projectId: string): Promise<HydratedEntry[]> {
  const raw = await api.get<ConversationEntryResponse[]>(`/projects/${projectId}/conversation`);
  return toHydratedEntries(raw ?? []);
}
