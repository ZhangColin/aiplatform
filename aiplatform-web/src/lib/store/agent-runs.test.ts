import { beforeEach, describe, expect, it } from "vitest";

import { latestProjectRun, useAgentRunsStore } from "./agent-runs";

/**
 * 运行注册表 store（顶栏 LIVE 脉冲 + 计时的数据源，#82 随收缩票立档）：run-start
 * 建 run（同项目驱逐旧 run）、生命周期事件落状态、迟到事件补建 stub 不驱逐、
 * 起跑锚取事件时戳（重放重建时计时仍准）。
 */

function seedRun(projectId: string, runId: string, at = 1_000) {
  useAgentRunsStore.getState().startRun({ projectId, runId, at });
}

beforeEach(() => {
  useAgentRunsStore.setState({ runs: {}, order: [] });
});

describe("agent-runs store · run-start 建档", () => {
  it("run-start 建 run：status=running、startedAt = 事件时戳（信封 ts——重放重建计时仍准）", () => {
    seedRun("p1", "run1", 1_728_000_000_000);

    const run = useAgentRunsStore.getState().runs["run1"];
    expect(run).toEqual({
      runId: "run1",
      projectId: "p1",
      startedAt: 1_728_000_000_000,
      status: "running",
    });
  });

  it("同 runId 的 run-start 重放幂等：不重开、起跑锚不漂移", () => {
    seedRun("p1", "run1", 1_000);
    seedRun("p1", "run1", 9_999); // 重放（迟到/重复）

    expect(useAgentRunsStore.getState().runs["run1"].startedAt).toBe(1_000);
    expect(useAgentRunsStore.getState().order).toEqual(["run1"]);
  });

  it("同项目新 run-start 驱逐旧 run（互逐：对话/编码轮流坐庄，注册表只留最近）", () => {
    seedRun("p1", "run1", 1_000);
    seedRun("p1", "run2", 2_000);

    const state = useAgentRunsStore.getState();
    expect(state.runs["run1"]).toBeUndefined();
    expect(latestProjectRun(state, "p1")?.runId).toBe("run2");
  });

  it("别的项目的 run 不互逐", () => {
    seedRun("p1", "run1", 1_000);
    seedRun("p2", "run2", 2_000);

    const state = useAgentRunsStore.getState();
    expect(state.runs["run1"]).toBeDefined();
    expect(state.runs["run2"]).toBeDefined();
  });

  it("总量软上限 10：最旧先出（内存有界）", () => {
    for (let i = 1; i <= 12; i++) seedRun(`p${i}`, `run${i}`, i);

    const state = useAgentRunsStore.getState();
    expect(state.order).toHaveLength(10);
    expect(state.runs["run1"]).toBeUndefined();
    expect(state.runs["run12"]).toBeDefined();
  });
});

describe("agent-runs store · 生命周期状态落定", () => {
  it("question-raised → questioning（等用户 ≠ 终态）；run-finish → finished", () => {
    seedRun("p1", "run1");
    const s = useAgentRunsStore.getState();
    s.setRunStatus({ projectId: "p1", runId: "run1", at: 2_000 }, "questioning");
    expect(useAgentRunsStore.getState().runs["run1"].status).toBe("questioning");

    s.setRunStatus({ projectId: "p1", runId: "run1", at: 3_000 }, "finished");
    expect(useAgentRunsStore.getState().runs["run1"].status).toBe("finished");
  });

  it("run-failed / error → error（run 失败为唯一失败终态——LIVE 脉冲不悬死）", () => {
    seedRun("p1", "run1");
    useAgentRunsStore
      .getState()
      .setRunStatus({ projectId: "p1", runId: "run1", at: 4_000 }, "error");
    expect(useAgentRunsStore.getState().runs["run1"].status).toBe("error");
  });

  it("run 不存在时补建 stub（断线缺口/起跑即死也可见），不触发驱逐", () => {
    seedRun("p1", "run1", 1_000);
    useAgentRunsStore
      .getState()
      .setRunStatus({ projectId: "p1", runId: "run-late", at: 5_000 }, "error");

    const state = useAgentRunsStore.getState();
    expect(state.runs["run-late"]).toEqual({
      runId: "run-late",
      projectId: "p1",
      startedAt: 5_000,
      status: "error",
    });
    expect(state.runs["run1"]).toBeDefined(); // 补建不驱逐当前 run
  });

  it("同状态重复落定幂等（引用不变）", () => {
    seedRun("p1", "run1");
    const before = useAgentRunsStore.getState().runs["run1"];
    useAgentRunsStore.getState().setRunStatus({ projectId: "p1", runId: "run1", at: 2_000 }, "running");
    expect(useAgentRunsStore.getState().runs["run1"]).toBe(before);
  });
});

describe("latestProjectRun · 最近 run 读口", () => {
  it("取该项目插入序最近的一个 run；无则 undefined", () => {
    seedRun("p1", "run1", 1_000);
    seedRun("p2", "run2", 2_000);
    seedRun("p1", "run3", 3_000);

    const state = useAgentRunsStore.getState();
    expect(latestProjectRun(state, "p1")?.runId).toBe("run3");
    expect(latestProjectRun(state, "p9")).toBeUndefined();
  });
});
