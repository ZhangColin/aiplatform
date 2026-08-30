import type { components } from "@/lib/api/schema";

/**
 * 项目用量（issue #20 简版 → #24 升级，spec 0002 §4）：五档 token 数——
 * input / output / cacheRead / cacheWrite / reasoning；平台成本币种分桶 +
 * 未配价标注 + 按期聚合（后端 #29 已收口）。
 */

export type TokenUsage = components["schemas"]["TokenUsage"];

export type TokenUsageKey = keyof TokenUsage;

/**
 * swagger 生成类型失真修正：issue #24 明确 seq 可为 null（期行已不可得时，
 * 沉底显「往期」），生成类型缺 nullable——domain 层按运行时真相当即收窄；
 * 持久正解在后端 swagger 标 nullable，修正后此处应回退为直接引用生成类型。
 */
export type IterationUsage = Omit<components["schemas"]["IterationUsage"], "seq"> & {
  seq?: number | null;
};

/** 平台成本币种分桶（键 = ISO 4217 币种码；分桶平铺、不相加不折算）。 */
export type CostBuckets = Record<string, number>;

/** 五档定义（呈现序）。 */
export const TOKEN_USAGE_ROWS = [
  { key: "input", label: "输入" },
  { key: "output", label: "输出" },
  { key: "cacheRead", label: "缓存读取" },
  { key: "cacheWrite", label: "缓存写入" },
  { key: "reasoning", label: "推理" },
] as const;

/** 单档取数：缺省归 0（后端字段全可缺）。 */
export function tokenCount(tokens: TokenUsage | undefined, key: TokenUsageKey): number {
  return tokens?.[key] ?? 0;
}

/** 五档合计。 */
export function usageTotalTokens(tokens: TokenUsage | undefined): number {
  return TOKEN_USAGE_ROWS.reduce((sum, row) => sum + tokenCount(tokens, row.key), 0);
}

const numberFormat = new Intl.NumberFormat("zh-CN");

/** token 数千分位展示。 */
export function formatTokenCount(value: number): string {
  return numberFormat.format(value);
}

const costFormat = new Intl.NumberFormat("zh-CN", {
  minimumFractionDigits: 4,
  maximumFractionDigits: 4,
});

/**
 * 平台成本数值：固定 4 位小数（BigDecimal 序列化可能带多位小数，成本量级小）。
 * 口径纪律（A6 §7）：只标「平台成本」，不出现价格/费用/金额措辞。
 */
export function formatCost(value: number): string {
  return costFormat.format(value);
}

/**
 * cost 币种分桶平铺（键 = ISO 4217 币种码）：按币种码排序的 [币种, 数值]
 * 序列，不相加不折算（无汇率概念）；空对象/缺省 = 全部未配价或无事件，归空序列。
 */
export function costEntries(cost: CostBuckets | undefined): [string, number][] {
  return Object.entries(cost ?? {}).sort(([a], [b]) => a.localeCompare(b));
}

/** byRole 用途标记桶（roleLabel 为 null 的非 preset 角色）code → 展示名映射。 */
const ROLE_MARKER_LABELS: Record<string, string> = {
  FIX: "期后修复",
  RESUME: "恢复执行",
};

/**
 * 角色桶展示名：展示纪律（spec 0002 §6）只走展示名字段，roleLabel 缺失时
 * 按用途标记 code 映射（FIX→期后修复 / RESUME→恢复执行），未知 code 落「—」。
 */
export function roleUsageLabel(
  role: string | undefined,
  roleLabel: string | null | undefined,
): string {
  if (roleLabel) return roleLabel;
  return (role && ROLE_MARKER_LABELS[role]) || "—";
}

/** 期行展示标签：seq = 「第 N 期」；seq 为 null（期行已不可得）沉底显「往期」。 */
export function iterationLabel(seq: number | null | undefined): string {
  return seq == null ? "往期" : `第 ${seq} 期`;
}

/** byIteration 呈现序：seq 升序，null 沉底（不改写原数组）。 */
export function sortedIterations(list: IterationUsage[] | undefined): IterationUsage[] {
  return [...(list ?? [])].sort((a, b) => (a.seq ?? Infinity) - (b.seq ?? Infinity));
}

/**
 * 期后修复差额 = total − Σ期桶逐档机械减法（期后修复 run 不带期归属，入 total
 * 不入任何期桶——各期桶合计可能小于 total，差额即期后修复部分，刻意语义不补桶）。
 */
export function iterationRemainder(
  total: TokenUsage | undefined,
  list: IterationUsage[] | undefined,
): TokenUsage {
  const remainder: Record<TokenUsageKey, number> = {
    input: 0,
    output: 0,
    cacheRead: 0,
    cacheWrite: 0,
    reasoning: 0,
  };
  for (const row of TOKEN_USAGE_ROWS) {
    const iterated = (list ?? []).reduce((sum, it) => sum + tokenCount(it.tokens, row.key), 0);
    remainder[row.key] = tokenCount(total, row.key) - iterated;
  }
  return remainder;
}
