import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import type { WorkPart, WorkSnapshot } from "@/lib/store/work-message";

import {
  activityOf,
  formatClock,
  formatDuration,
  presentWorkParts,
  WorkMessage,
} from "./work-message";

function action(
  overrides: Partial<Extract<WorkPart, { kind: "action" }>> = {},
): Extract<WorkPart, { kind: "action" }> {
  return {
    kind: "action",
    id: "a1",
    toolCallId: "t1",
    toolName: "write_file",
    state: "completed",
    label: "编写【A】",
    ...overrides,
  };
}

function signal(overrides: Partial<Extract<WorkPart, { kind: "signal" }>> = {}) {
  return {
    kind: "signal",
    id: "s1",
    signal: "derailed",
    ...overrides,
  } satisfies Extract<WorkPart, { kind: "signal" }>;
}

function work(overrides: Partial<WorkSnapshot> = {}): WorkSnapshot {
  return { runId: "r1", frozen: false, parts: [], ...overrides };
}

describe("presentWorkParts · 成功无痕投影（#230）", () => {
  it("只留失败动作；成功/在跑动作与脱轨信号不进正文", () => {
    const parts: WorkPart[] = [
      action({ id: "a1", state: "completed" }),
      action({ id: "a2", state: "running", toolCallId: "t2" }),
      action({ id: "a3", state: "failed", toolCallId: "t3" }),
      signal(),
    ];

    expect(presentWorkParts(parts)).toEqual([action({ id: "a3", state: "failed", toolCallId: "t3" })]);
  });
});

describe("activityOf · 活性行变体（#235/#240）", () => {
  it("末位动作失败 = 失败变体（破例优先）", () => {
    const parts = [action({ id: "a1", toolCallId: "t1", state: "running" }), action({ id: "a2", toolCallId: "t2", state: "failed" })];
    expect(activityOf(parts)).toEqual({ kind: "failed" });
  });

  it("最近发起的在跑动作 = 动作变体（并发取最近一条）", () => {
    const parts = [
      action({ id: "a1", toolCallId: "t1", state: "running", label: "先跑" }),
      action({ id: "a2", toolCallId: "t2", state: "started", label: "后跑" }),
    ];
    const activity = activityOf(parts);
    expect(activity.kind).toBe("action");
    if (activity.kind === "action") expect(activity.part.label).toBe("后跑");
  });

  it("末位信号晚于末位动作 = 脱轨变体（吞段后无动作接管）", () => {
    expect(activityOf([action({ state: "completed" }), signal()])).toEqual({ kind: "derailed" });
  });

  it("信号后被新动作接管 = 动作变体（真实动作优先）", () => {
    const parts = [
      signal(),
      action({ id: "a2", toolCallId: "t2", state: "running" }),
    ];
    expect(activityOf(parts).kind).toBe("action");
  });

  it("无动作无信号 = 间隙打字点", () => {
    expect(activityOf([])).toEqual({ kind: "idle" });
    expect(activityOf([action({ state: "completed" })])).toEqual({ kind: "idle" });
  });
});

describe("WorkMessage · 呈现", () => {
  it("生长中：标题＋时钟＋活性行；成功动作无痕", () => {
    const html = renderToStaticMarkup(
      <WorkMessage
        work={work({
          slice: { title: "系统初始化" },
          startedAt: 1_000,
          parts: [action({ state: "completed" })],
        })}
      />,
    );

    expect(html).toContain("系统初始化");
    expect(html).toContain("已运行");
    expect(html).not.toContain("编写【A】"); // 成功无痕
  });

  it("无切片标题回落「正在做」；切片序号/进度不再呈现（#608 轮次计数退役）", () => {
    const html = renderToStaticMarkup(<WorkMessage work={work({ startedAt: 1_000 })} />);
    expect(html).toContain("正在做");
  });

  it("失败红行：label＋没做成＋错误副行（#229 失败留痕）", () => {
    const html = renderToStaticMarkup(
      <WorkMessage
        work={work({
          parts: [
            action({ state: "failed", label: "执行【构建】", error: "exit code 1" }),
          ],
        })}
      />,
    );

    expect(html).toContain("执行【构建】");
    expect(html).toContain("没做成");
    expect(html).toContain("exit code 1");
  });

  it("脱轨信号只在活性行呈现（琥珀色定型文案），静态面无痕", () => {
    const html = renderToStaticMarkup(<WorkMessage work={work({ parts: [signal()] })} />);
    expect(html).toContain("刚才模型发射异常，正在调整");
  });

  it("间隙活性行 = 打字点（无字）", () => {
    const html = renderToStaticMarkup(<WorkMessage work={work()} />);
    expect(html).not.toContain("正在干活"); // 字样行已退役
  });

  it("定格空壳整卡退场（run 从无部件）", () => {
    expect(renderToStaticMarkup(<WorkMessage work={work({ frozen: true })} />)).toBe("");
  });

  it("定格未入流：保留末行活性行且静态（无转圈、不跳动）", () => {
    const html = renderToStaticMarkup(
      <WorkMessage work={work({ frozen: true, parts: [action({ state: "running" })] })} />,
    );
    expect(html).toContain("编写【A】");
    expect(html).not.toContain("进行中");
  });

  it("定格且收尾卡入流：活性行沉没（终形只余失败痕）", () => {
    const html = renderToStaticMarkup(
      <WorkMessage
        work={work({ frozen: true, parts: [action({ state: "running" })] })}
        closingArrived
      />,
    );
    expect(html).not.toContain("编写【A】");
  });
});

describe("formatClock / formatDuration", () => {
  it("mm:ss（负值归零）", () => {
    expect(formatClock(65_000)).toBe("01:05");
    expect(formatClock(-1)).toBe("00:00");
  });

  it("用时用户语言：秒/分秒", () => {
    expect(formatDuration(5_200)).toBe("5 秒");
    expect(formatDuration(63_000)).toBe("1 分 03 秒");
  });
});
