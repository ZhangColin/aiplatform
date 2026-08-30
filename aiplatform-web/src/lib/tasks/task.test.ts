import { describe, expect, it } from "vitest";

import {
  buildCloseBugInput,
  buildCreateTaskCommand,
  buildFirstRoundPayload,
  buildRetestPayload,
  bugStatusTone,
  byCreatedAtDesc,
  countOpenBugs,
  isRetestTask,
  narrowSubmittedPayload,
  normalizeAccount,
  normalizeBug,
  normalizeTask,
  normalizeTaskCard,
  normalizeTaskDetail,
  severityLabel,
  severityTone,
  taskStatusLabel,
  taskStatusTone,
  type TaskResponse,
} from "./task";

describe("normalizeTaskCard", () => {
  it("透传完整字段，project 最小上下文展开", () => {
    expect(
      normalizeTaskCard({
        taskId: "101",
        projectId: "55",
        project: { name: "商城", previewUrl: "http://localhost:9001" },
        title: "首页回归",
        content: "按清单过一遍",
        status: 1,
        statusName: "已发布",
        rejectReason: "漏了移动端",
        rejectedAt: "2026-08-22T01:00:00Z",
        createdAt: "2026-08-21T09:00:00Z",
      }),
    ).toEqual({
      taskId: "101",
      projectId: "55",
      projectName: "商城",
      previewUrl: "http://localhost:9001",
      title: "首页回归",
      content: "按清单过一遍",
      status: 1,
      statusName: "已发布",
      rejectReason: "漏了移动端",
      createdAt: "2026-08-21T09:00:00Z",
    });
  });

  it("字段全缺：归一不炸，previewUrl 为 null", () => {
    expect(normalizeTaskCard({})).toEqual({
      taskId: "",
      projectId: "",
      projectName: "",
      previewUrl: null,
      title: "",
      content: "",
      status: 0,
      statusName: "",
      rejectReason: "",
      createdAt: "",
    });
  });

  it("工作区未建：project.previewUrl 缺失 → null", () => {
    expect(normalizeTaskCard({ project: { name: "商城" } }).previewUrl).toBeNull();
  });
});

describe("normalizeTask / normalizeTaskDetail", () => {
  it("TaskResponse 归一 + submittedPayload 收窄", () => {
    const task = normalizeTask({
      taskId: "101",
      status: 3,
      statusName: "已提交",
      assigneeName: "测试小李",
      // swagger 类型是 Map 形态泛化对象（Record<string, never> 值），运行期真实载荷经收窄断言
      submittedPayload: {
        report: "测完了",
        bugs: [{ title: "白屏", severity: 1 }],
      } as unknown as TaskResponse["submittedPayload"],
    });
    expect(task.taskId).toBe("101");
    expect(task.assigneeName).toBe("测试小李");
    expect(task.submittedPayload.report).toBe("测完了");
    expect(task.submittedPayload.bugs).toHaveLength(1);
  });

  it("详情 = task + ProjectBrief + bugs[] 组装", () => {
    const detail = normalizeTaskDetail({
      task: { taskId: "101", title: "回归", status: 2, statusName: "执行中" },
      project: { name: "商城", previewUrl: "http://localhost:9001" },
      bugs: [{ bugId: "8800", title: "白屏", severity: 1, status: 2, statusName: "已修复" }],
    });
    expect(detail.taskId).toBe("101");
    expect(detail.projectName).toBe("商城");
    expect(detail.previewUrl).toBe("http://localhost:9001");
    expect(detail.bugs).toHaveLength(1);
    expect(detail.bugs[0]?.bugId).toBe("8800");
  });

  it("task / project / bugs 全缺：归一不炸", () => {
    const detail = normalizeTaskDetail({});
    expect(detail.taskId).toBe("");
    expect(detail.projectName).toBe("");
    expect(detail.previewUrl).toBeNull();
    expect(detail.bugs).toEqual([]);
  });
});

describe("normalizeBug", () => {
  it("字段全缺：归一为空不炸", () => {
    expect(normalizeBug({})).toEqual({
      bugId: "",
      title: "",
      description: "",
      reproSteps: "",
      severity: 0,
      severityName: "",
      status: 0,
      statusName: "",
      createdAt: "",
    });
  });
});

describe("taskStatusTone / taskStatusLabel", () => {
  it("执行中 + rejectReason = 被驳回 destructive（驳回不是 status，是派生态）", () => {
    expect(taskStatusTone(2, "漏了移动端")).toBe("destructive");
    expect(taskStatusLabel(2, "执行中", "漏了移动端")).toBe("被驳回");
  });

  it("五态 tone：新任务 amber / 执行中 default / 已提交 primary / 已确认 success / 已取消 muted", () => {
    expect(taskStatusTone(1, "")).toBe("amber");
    expect(taskStatusTone(2, "")).toBe("default");
    expect(taskStatusTone(3, "")).toBe("primary");
    expect(taskStatusTone(4, "")).toBe("success");
    expect(taskStatusTone(5, "")).toBe("muted");
  });

  it("label 优先服务端 statusName，缺失回退本地枚举表", () => {
    expect(taskStatusLabel(1, "已发布", "")).toBe("已发布");
    expect(taskStatusLabel(3, "", "")).toBe("已提交");
    expect(taskStatusLabel(99, "", "")).toBe("未知");
  });
});

describe("severityLabel / severityTone / bugStatusTone", () => {
  it("severityName 优先，缺失回退本地四档", () => {
    expect(severityLabel(1, "致命")).toBe("致命");
    expect(severityLabel(2)).toBe("严重");
    expect(severityLabel(4)).toBe("轻微");
    expect(severityLabel(99)).toBe("未知");
  });

  it("severity tone：致命 destructive / 严重 amber / 一般 default / 轻微 muted", () => {
    expect(severityTone(1)).toBe("destructive");
    expect(severityTone(2)).toBe("amber");
    expect(severityTone(3)).toBe("default");
    expect(severityTone(4)).toBe("muted");
    expect(severityTone(99)).toBe("default");
  });

  it("Bug 三态 tone：待修复 amber / 已修复 primary / 复测通过 success", () => {
    expect(bugStatusTone(1)).toBe("amber");
    expect(bugStatusTone(2)).toBe("primary");
    expect(bugStatusTone(3)).toBe("success");
    expect(bugStatusTone(99)).toBe("default");
  });
});

describe("byCreatedAtDesc", () => {
  it("新→旧排序，不改原数组", () => {
    const items = [
      { createdAt: "2026-08-20T01:00:00Z" },
      { createdAt: "2026-08-22T01:00:00Z" },
      { createdAt: "2026-08-21T01:00:00Z" },
    ];
    const sorted = byCreatedAtDesc(items);
    expect(sorted.map((i) => i.createdAt)).toEqual([
      "2026-08-22T01:00:00Z",
      "2026-08-21T01:00:00Z",
      "2026-08-20T01:00:00Z",
    ]);
    expect(items[0]?.createdAt).toBe("2026-08-20T01:00:00Z");
  });
});

describe("isRetestTask", () => {
  it("提交形状判别 = 详情 bugs 非空", () => {
    expect(isRetestTask([])).toBe(false);
    expect(isRetestTask([normalizeBug({ bugId: "1" })])).toBe(true);
  });
});

describe("narrowSubmittedPayload", () => {
  it("首轮形状：report + bugs 断言取值（Map 形态对象）", () => {
    const narrowed = narrowSubmittedPayload({
      report: "首轮报告",
      bugs: [
        { title: "白屏", description: "打开即白", reproSteps: "1. 打开", severity: 1 },
        { title: "样式歪" },
      ],
    });
    expect(narrowed.report).toBe("首轮报告");
    expect(narrowed.bugs).toEqual([
      { title: "白屏", description: "打开即白", reproSteps: "1. 打开", severity: 1 },
      { title: "样式歪", description: "", reproSteps: "", severity: 0 },
    ]);
    expect(narrowed.results).toEqual([]);
  });

  it("复测形状：results 的 bugId 归一为字符串（响应侧口径）", () => {
    const narrowed = narrowSubmittedPayload({
      report: "复测报告",
      results: [
        { bugId: 8800, pass: true, note: "好了" },
        { bugId: "8801", pass: false },
      ],
    });
    expect(narrowed.results).toEqual([
      { bugId: "8800", pass: true, note: "好了" },
      { bugId: "8801", pass: false, note: "" },
    ]);
  });

  it("null / 非对象 / 垃圾字段：归一空不炸", () => {
    const empty = { report: "", bugs: [], results: [] };
    expect(narrowSubmittedPayload(undefined)).toEqual(empty);
    expect(narrowSubmittedPayload(null)).toEqual(empty);
    expect(narrowSubmittedPayload("junk")).toEqual(empty);
    expect(narrowSubmittedPayload({ report: 42, bugs: "x", results: [null, 7] })).toEqual(empty);
  });
});

describe("载荷构造（ID 转换收口）", () => {
  it("首轮：report + bugs（空数组 = 测试全过也原样带）", () => {
    expect(buildFirstRoundPayload("  全过  ", [])).toEqual({ report: "全过", bugs: [] });
    expect(
      buildFirstRoundPayload("有问题", [
        { title: " 白屏 ", description: "打开即白", reproSteps: "", severity: 1 },
      ]),
    ).toEqual({
      report: "有问题",
      bugs: [{ title: "白屏", description: "打开即白", reproSteps: "", severity: 1 }],
    });
  });

  it("复测：bugId string → int64 Number 转换在此收口", () => {
    expect(
      buildRetestPayload("复测完", [
        { bugId: "8800", pass: true, note: "" },
        { bugId: "8801", pass: false, note: "还是白" },
      ]),
    ).toEqual({
      report: "复测完",
      results: [
        { bugId: 8800, pass: true, note: undefined },
        { bugId: 8801, pass: false, note: "还是白" },
      ],
    });
  });

  it("建任务：assigneeAccountId string → int64 Number 转换", () => {
    expect(buildCreateTaskCommand("回归", "按清单", "42")).toEqual({
      title: "回归",
      content: "按清单",
      assigneeAccountId: 42,
    });
  });
});

describe("关闭 / 派发修复载荷构造（issue #38）", () => {
  it("关闭：bugId string → int64 路径参 + reason 去空格 body", () => {
    expect(buildCloseBugInput("8800", "  误报，产品本就如此  ")).toEqual({
      bugId: 8800,
      command: { reason: "误报，产品本就如此" },
    });
  });

  it("派发修复：可派发态 = 待修复（OPEN）Bug 计数", () => {
    const bugs = [
      normalizeBug({ bugId: "8800", status: 2, statusName: "已修复" }),
      normalizeBug({ bugId: "8801", status: 3, statusName: "复测通过" }),
    ];
    expect(countOpenBugs(bugs)).toBe(0);

    const withOpen = [
      ...bugs,
      normalizeBug({ bugId: "8802", status: 1, statusName: "待修复" }),
      normalizeBug({ bugId: "8803", status: 1, statusName: "待修复" }),
    ];
    expect(countOpenBugs(withOpen)).toBe(2);
  });

  it("空列表：无待修复可派发", () => {
    expect(countOpenBugs([])).toBe(0);
  });
});

describe("normalizeAccount", () => {
  it("字段全缺：归一为空串", () => {
    expect(normalizeAccount({})).toEqual({ accountId: "", displayName: "" });
    expect(normalizeAccount({ accountId: "7", displayName: "小李" })).toEqual({
      accountId: "7",
      displayName: "小李",
    });
  });
});
