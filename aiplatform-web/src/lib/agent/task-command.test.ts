import { describe, expect, it } from "vitest";

import { AGENT_ROLES, buildTaskCommand, roleByCode } from "./task-command";

describe("buildTaskCommand（下任务载荷构造，spec 0001 §4.1）", () => {
  it("prompt 必带；role 缺省（undefined / 不传）即省略 → 后端取阶段默认角色", () => {
    expect(buildTaskCommand("实现预约表单")).toEqual({ prompt: "实现预约表单" });
    expect(buildTaskCommand("实现预约表单", undefined)).toEqual({ prompt: "实现预约表单" });
    // 缺省语义不残留 role 键（JSON 序列化后后端拿不到 role 字段）
    expect("role" in buildTaskCommand("实现预约表单")).toBe(false);
  });

  it("显式选角色卡 → 携带 role（1–6）", () => {
    expect(buildTaskCommand("跑架构评审", 4)).toEqual({ prompt: "跑架构评审", role: 4 });
    expect(buildTaskCommand("写测试用例", 5)).toEqual({ prompt: "写测试用例", role: 5 });
  });
});

describe("AGENT_ROLES / roleByCode", () => {
  it("六角色 code 1–6 词表齐备（label 镜像 swagger 注释）", () => {
    expect(AGENT_ROLES.map((r) => r.code)).toEqual([1, 2, 3, 4, 5, 6]);
    expect(AGENT_ROLES.map((r) => r.label)).toEqual([
      "需求分析师",
      "开发工程师",
      "交付工程师",
      "架构师",
      "测试工程师",
      "原型开发工程师",
    ]);
  });

  it("roleByCode：命中返回角色卡，未知 code 返回 undefined", () => {
    expect(roleByCode(1)?.label).toBe("需求分析师");
    expect(roleByCode(6)?.name).toBe("DEMO");
    expect(roleByCode(0)).toBeUndefined();
    expect(roleByCode(7)).toBeUndefined();
  });
});
