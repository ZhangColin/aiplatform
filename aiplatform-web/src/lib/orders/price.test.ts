import { describe, expect, it } from "vitest";

import { formatPrice } from "./price";

// 价格展示纯函数（#29）：分 → 元、千分位、整数不带小数；v1 恒 CNY 符号 ¥。
describe("formatPrice · 订单价格展示", () => {
  it("整数分 → 整数元，不带小数", () => {
    expect(formatPrice(128000, "CNY")).toBe("¥1,280");
    expect(formatPrice(500)).toBe("¥5");
  });

  it("非整数分 → 两位小数", () => {
    expect(formatPrice(128050, "CNY")).toBe("¥1,280.50");
    expect(formatPrice(1)).toBe("¥0.01");
  });

  it("大额千分位分组", () => {
    expect(formatPrice(123456789)).toBe("¥1,234,567.89");
  });

  it("缺省/非数值 → undefined（待报价无价不渲染占位数字）", () => {
    expect(formatPrice(undefined)).toBeUndefined();
    expect(formatPrice(null)).toBeUndefined();
    expect(formatPrice(Number.NaN)).toBeUndefined();
  });
});
