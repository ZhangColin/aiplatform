import { describe, expect, it } from "vitest";

import { formatElapsed } from "./time";

describe("formatElapsed（工作台 LIVE 计时 mm:ss）", () => {
  it("不足 1 小时渲染 mm:ss，秒位补零", () => {
    expect(formatElapsed(0)).toBe("00:00");
    expect(formatElapsed(5)).toBe("00:05");
    expect(formatElapsed(65)).toBe("01:05");
    expect(formatElapsed(599)).toBe("09:59");
  });

  it("满 1 小时退化为 h:mm:ss（小时位不补零）", () => {
    expect(formatElapsed(3600)).toBe("1:00:00");
    expect(formatElapsed(3661)).toBe("1:01:01");
  });

  it("负值归零兜底，小数向下取整", () => {
    expect(formatElapsed(-10)).toBe("00:00");
    expect(formatElapsed(59.9)).toBe("00:59");
  });
});
