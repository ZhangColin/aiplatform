import { describe, expect, it } from "vitest";

import { userGateCopy } from "./gate-copy";

// 需求端三扇门文案映射（spec 0002 §5）：门名 → 用户侧卡片文案。禁词红线由
// 映射集中守住，这里断言门名命中与验收门专门动作口径（issue #43）；第一扇门
// 2026-08-25 更名「确认 PRD」（PRD 用词放开，issue #53）。
describe("userGateCopy", () => {
  it("三扇门名命中对应文案", () => {
    expect(userGateCopy("确认 PRD")?.approve).toBe("确认无误，开始做原型");
    expect(userGateCopy("确认原型")?.approve).toBe("满意，按这个做");
    expect(userGateCopy("验收")?.label).toBe("验收");
  });

  it("验收门有专门验收动作口径：验收通过 / 驳回反馈", () => {
    expect(userGateCopy("验收")?.approve).toBe("验收通过");
    expect(userGateCopy("验收")?.reject).toBe("驳回反馈");
  });

  it("无门名 / 未知名返回 null 兜底", () => {
    expect(userGateCopy(undefined)).toBeNull();
    expect(userGateCopy("")).toBeNull();
    expect(userGateCopy("开发完成")).toBeNull();
  });

  it("三扇门文案均不出现禁词（spec 0002 §5 全表）", () => {
    const banned = ["阶段", "状态机", "智能体", "期", "OPC", "HITL", "Demo", "门"];
    for (const gate of ["确认 PRD", "确认原型", "验收"]) {
      const copy = userGateCopy(gate)!;
      const text = Object.values(copy).join(" ");
      for (const word of banned) {
        expect(text, `${gate} 不应出现禁词「${word}」`).not.toContain(word);
      }
    }
  });
});
