import { describe, expect, it } from "vitest";

import { ENTRY_MODE } from "@/lib/modes";

import { buildCreateProjectCommand } from "./create";

// 一句话建项目载荷构造（issue #51 纯一句话 → #54 顺延收口：aiplatform-server#39
// 落地后 name 从契约移除，项目名全归后端 LLM 取，前端不再传；#299 mode 进载荷）。
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

  // ---------- 入口两档 mode 进载荷（#299，ADR-0029） ----------

  it("做系统/缺省：不携 endpointType（默认主链路载荷零变化——一等断言）", () => {
    expect(buildCreateProjectCommand({ requirement: "做一个官网" }))
      .toEqual({ requirement: "做一个官网" });
    expect(buildCreateProjectCommand({ requirement: "做一个官网", mode: ENTRY_MODE.system }))
      .toEqual({ requirement: "做一个官网" });
  });

  it("做设计：携终点初值 endpointType=1 入载荷", () => {
    const command = buildCreateProjectCommand({
      requirement: "给我的咖啡店设计一个 logo",
      mode: ENTRY_MODE.design,
    });
    expect(command).toEqual({
      requirement: "给我的咖啡店设计一个 logo",
      endpointType: 1,
    });
  });
});
