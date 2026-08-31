/**
 * 订单价格展示（#29 交易环②）：金额单位分（后端口径），用户面展示为元。
 * v1 币种恒 CNY（CONTEXT「报价」）；整数元不带小数、非整数两位小数，
 * 千分位分组（zh-CN locale）。
 */

/** 分 → 用户面价格文案（如 128000 → "¥1,280"、128050 → "¥1,280.50"）。 */
export function formatPrice(amount: number | undefined | null, currency?: string): string | undefined {
  if (amount == null || !Number.isFinite(amount)) {
    return undefined;
  }
  const yuan = amount / 100;
  const text = Number.isInteger(yuan)
    ? yuan.toLocaleString("zh-CN")
    : yuan.toLocaleString("zh-CN", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const symbol = currency ?? "CNY";
  return symbol === "CNY" ? `¥${text}` : `${symbol} ${text}`;
}
