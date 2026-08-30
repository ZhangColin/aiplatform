import { describe, expect, it } from "vitest";

import { attentionByProject, normalizeTodo, todoHref, todoTypeMeta } from "./todo";

describe("normalizeTodo", () => {
  it("透传完整字段", () => {
    expect(
      normalizeTodo({
        type: "AGENT_WAIT",
        projectId: "123456",
        refId: "w789",
        title: "智能体等待答复",
        createdAt: "2026-08-22T02:15:33.123Z",
      }),
    ).toEqual({
      type: "AGENT_WAIT",
      projectId: "123456",
      refId: "w789",
      title: "智能体等待答复",
      createdAt: "2026-08-22T02:15:33.123Z",
    });
  });

  it("字段全缺：归一为空串不炸", () => {
    expect(normalizeTodo({})).toEqual({
      type: "",
      projectId: "",
      refId: "",
      title: "",
      createdAt: "",
    });
  });
});

describe("todoTypeMeta", () => {
  it("dev 四型有中文徽章文案（A4 落定：任务待确认 / 可发复测）", () => {
    expect(todoTypeMeta("AGENT_WAIT").label).toBe("等答复");
    expect(todoTypeMeta("GATE_PENDING").label).toBe("待拍板");
    expect(todoTypeMeta("TASK_SUBMITTED").label).toBe("待确认");
    expect(todoTypeMeta("RETEST_READY").label).toBe("可复测");
  });

  it("opc 两型有中文徽章文案（新任务 / 被驳回 destructive）", () => {
    expect(todoTypeMeta("NEW_TASK")).toEqual({ label: "新任务", tone: "amber" });
    expect(todoTypeMeta("TASK_REJECTED")).toEqual({ label: "被驳回", tone: "destructive" });
  });

  it("未知型：回退 type 原文 + 中性 tone，不炸不丢", () => {
    expect(todoTypeMeta("SOMETHING_ELSE")).toEqual({ label: "SOMETHING_ELSE", tone: "default" });
  });
});

describe("todoHref", () => {
  it("dev 四型跳工作台深链（issue #44：AGENT_WAIT 消费 waitId / 门卡 / 任务面板）", () => {
    expect(todoHref({ ...todo, type: "AGENT_WAIT", refId: "w789" })).toBe(
      "/dev/projects/123456?wait=w789",
    );
    expect(todoHref({ ...todo, type: "GATE_PENDING" })).toBe("/dev/projects/123456?focus=gate");
    expect(todoHref({ ...todo, type: "TASK_SUBMITTED", refId: "101" })).toBe(
      "/dev/projects/123456?focus=tasks",
    );
    expect(todoHref({ ...todo, type: "RETEST_READY", refId: "123456" })).toBe(
      "/dev/projects/123456?focus=tasks",
    );
  });

  it("AGENT_WAIT 缺 refId（waitId）：回退工作台不定位", () => {
    expect(todoHref({ ...todo, type: "AGENT_WAIT", refId: "" })).toBe("/dev/projects/123456");
  });

  it("opc 两型跳任务详情（refId=taskId）", () => {
    expect(todoHref({ ...todo, type: "NEW_TASK", refId: "101" })).toBe("/opc/tasks/101");
    expect(todoHref({ ...todo, type: "TASK_REJECTED", refId: "101" })).toBe("/opc/tasks/101");
  });

  it("锚点缺失：返回 null 不导航", () => {
    expect(todoHref({ ...todo, projectId: "" })).toBeNull();
    expect(todoHref({ ...todo, type: "NEW_TASK", refId: "" })).toBeNull();
  });
});

const todo = normalizeTodo({
  type: "GATE_PENDING",
  projectId: "123456",
  refId: "123456",
  title: "「开发」门待拍板",
  createdAt: "2026-08-22T02:15:33.123Z",
});

describe("attentionByProject", () => {
  it("口径内两型聚到项目：type 级用户侧文案（不透传后端 title）+ 需求端深链", () => {
    const map = attentionByProject([
      normalizeTodo({ type: "AGENT_WAIT", projectId: "1", refId: "w1", title: "智能体等待答复", createdAt: "2026-08-24T00:00:00Z" }),
      normalizeTodo({ type: "GATE_PENDING", projectId: "2", refId: "2", title: "「开发」门待拍板", createdAt: "2026-08-23T00:00:00Z" }),
    ]);
    expect(map.get("1")?.label).toBe("需要你：顾问在等你答复");
    expect(map.get("1")?.href).toBe("/projects/1?wait=w1");
    expect(map.get("2")?.label).toBe("需要你：有一件事等你拍板");
    expect(map.get("2")?.href).toBe("/projects/2?focus=gate");
    // 后端 title（可能含「智能体」等需求端禁词）不落用户文案
    expect([...map.values()].every((a) => !a.label.includes("智能体"))).toBe(true);
  });

  it("每项目取最新一条（输入按新→旧，后端契约）", () => {
    const map = attentionByProject([
      normalizeTodo({ type: "AGENT_WAIT", projectId: "1", refId: "w-new", title: "新", createdAt: "2026-08-24T00:00:00Z" }),
      normalizeTodo({ type: "GATE_PENDING", projectId: "1", refId: "1", title: "旧", createdAt: "2026-08-23T00:00:00Z" }),
    ]);
    expect(map.get("1")?.href).toBe("/projects/1?wait=w-new");
  });

  it("口径外型不进结果；AGENT_WAIT 缺 refId 时退普通进项目", () => {
    const map = attentionByProject([
      normalizeTodo({ type: "TASK_SUBMITTED", projectId: "3", refId: "101", title: "待确认", createdAt: "2026-08-24T00:00:00Z" }),
      normalizeTodo({ type: "RETEST_READY", projectId: "4", refId: "4", title: "可复测", createdAt: "2026-08-24T00:00:00Z" }),
      normalizeTodo({ type: "AGENT_WAIT", projectId: "5", refId: "", title: "等答复", createdAt: "2026-08-24T00:00:00Z" }),
      normalizeTodo({ type: "GATE_PENDING", projectId: "", refId: "", title: "无项目", createdAt: "2026-08-24T00:00:00Z" }),
    ]);
    expect(map.has("3")).toBe(false);
    expect(map.has("4")).toBe(false);
    expect(map.get("5")?.href).toBe("/projects/5");
    expect(map.size).toBe(1);
  });
});
