import { describe, expect, it } from "vitest";

import { generationStateOf } from "./detail";

describe("generationStateOf · 生成态投影映射（#222；#604 四态归一——无中断档）", () => {
  it("三档 code 归一：1=从未生成、2=生成中、4=已生成；缺省 undefined", () => {
    expect(generationStateOf(1)).toBe("never");
    expect(generationStateOf(2)).toBe("generating");
    expect(generationStateOf(4)).toBe("generated");
    expect(generationStateOf(undefined)).toBeUndefined();
    expect(generationStateOf(null)).toBeUndefined();
  });

  it("已退役的中断档（code 3）不再映射——失败/停止的项目由服务端落「从未生成」，失败只走会话态呈现", () => {
    expect(generationStateOf(3)).toBeUndefined();
  });
});


