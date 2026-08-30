import type { components } from "@/lib/api/schema";

/**
 * 想法池（issue #20，spec 0002 §4）：输入 + 列表（新→旧）。提交纪律——
 * `kind` / `source` 一律不传（后端缺省 = 用户 / 需求），不暴露选择器；展示
 * 走 `*Name` 字段（枚举 code 不进消费逻辑）。
 */

export type DemandPoolEntryResponse = components["schemas"]["DemandPoolEntryResponse"];

export const DEMAND_CONTENT_MAX = 2000;

/** 提交前校验：空内容拦截（前端校验先行）+ 长度上限；null = 可提交。 */
export function validateDemandContent(input: string): string | null {
  const text = input.trim();
  if (!text) return "想记录的内容不能为空";
  if (text.length > DEMAND_CONTENT_MAX)
    return `最多 ${DEMAND_CONTENT_MAX} 字，当前 ${text.length} 字`;
  return null;
}

/** 新→旧（契约即此序，客户端防御排序；createdAt 缺失者沉底不抛）。纯函数。 */
export function sortDemandEntriesNewestFirst(
  entries: DemandPoolEntryResponse[],
): DemandPoolEntryResponse[] {
  const timeOf = (e: DemandPoolEntryResponse) =>
    e.createdAt ? Date.parse(e.createdAt) : Number.NEGATIVE_INFINITY;
  return [...entries].sort((a, b) => timeOf(b) - timeOf(a));
}
