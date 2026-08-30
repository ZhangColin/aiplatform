import { describe, expect, it } from "vitest";

import { buildCreateProjectCommand } from "./create";

// 一句话建项目载荷构造（issue #51 纯一句话 → #54 顺延收口：aiplatform-server#39
// 落地后 name 从契约移除，项目名全归后端 LLM 取，前端不再传）。
describe("一句话建项目载荷构造", () => {
  it("载荷只含 requirement，不含 name / type / engine（取名归后端 LLM）", () => {
    const command = buildCreateProjectCommand({
      requirement: "给宠物医院做个在线预约的网站",
    });
    expect(command).toEqual({ requirement: "给宠物医院做个在线预约的网站" });
  });

  it("requirement trim 后入载荷（首尾空白不进载荷）", () => {
    const command = buildCreateProjectCommand({ requirement: "  做个小程序  " });
    expect(command.requirement).toBe("做个小程序");
  });
});
