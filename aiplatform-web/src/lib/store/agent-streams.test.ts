import { beforeEach, describe, expect, it } from "vitest";

import { latestProjectRun, useAgentStreamsStore } from "./agent-streams";

/** zustand 单例 store：每个用例前重置回空表。 */
function reset() {
  useAgentStreamsStore.setState({ runs: {}, order: [] });
}

function state() {
  return useAgentStreamsStore.getState();
}

describe("agent-streams store", () => {
  beforeEach(reset);

  it("startRun 建立新 run（running，携带 task-start 元数据）", () => {
    state().startRun({ runId: "r1", projectId: "p1", prompt: "实现表单", model: "deepseek-v3" });

    expect(state().runs["r1"]).toMatchObject({
      runId: "r1",
      projectId: "p1",
      prompt: "实现表单",
      model: "deepseek-v3",
      status: "running",
      segments: [],
    });
    expect(typeof state().runs["r1"].startedAt).toBe("number");
    expect(state().order).toEqual(["r1"]);
  });

  it("驱逐：同项目只留最近 1 个 run，旧 run 整体清掉", () => {
    state().startRun({ runId: "r1", projectId: "p1", prompt: "第一次" });
    state().startRun({ runId: "r2", projectId: "p2", prompt: "别的项目" });
    state().startRun({ runId: "r3", projectId: "p1", prompt: "第二次" });

    expect(state().order).toEqual(["r2", "r3"]);
    expect(state().runs["r1"]).toBeUndefined();
    expect(state().runs["r3"].status).toBe("running");
  });

  it("task-start 晚于首帧（role-assigned 补建 stub）→ 补 prompt/model 元数据、不清已收分段", () => {
    // 正本帧序 role-assigned → task-start（创建即开场）：首帧补建 stub 后
    // task-start 必须把 prompt 补进去，否则用户的一句话描述永不出现。
    state().appendSegment(
      { runId: "r1", projectId: "p1" },
      { kind: "role", id: "r1:1", role: "BA", roleLabel: "需求分析师", engine: "agentscope" },
    );
    state().startRun({ runId: "r1", projectId: "p1", prompt: "做个官网", model: "deepseek-v3" });

    expect(state().runs["r1"]).toMatchObject({ prompt: "做个官网", model: "deepseek-v3" });
    expect(state().runs["r1"].segments).toEqual([
      { kind: "role", id: "r1:1", role: "BA", roleLabel: "需求分析师", engine: "agentscope" },
    ]);
  });

  it("task-start 命中已存在 stub 时仍驱逐同项目旧 run", () => {
    state().startRun({ runId: "r1", projectId: "p1", prompt: "旧任务" });
    // 新 run 首帧先到（补建 stub r2），task-start 随后——旧 run r1 仍应被驱逐
    state().appendSegment(
      { runId: "r2", projectId: "p1" },
      { kind: "role", id: "r2:1", role: "BA", roleLabel: "需求分析师", engine: "agentscope" },
    );
    state().startRun({ runId: "r2", projectId: "p1", prompt: "新任务" });

    expect(state().runs["r1"]).toBeUndefined();
    expect(state().runs["r2"]).toMatchObject({ prompt: "新任务" });
    expect(state().runs["r2"].segments).toHaveLength(1);
  });

  it("总量软上限 10：第 11 个 run 挤掉最旧的一个", () => {
    for (let i = 1; i <= 11; i++) {
      state().startRun({ runId: `r${i}`, projectId: `p${i}` });
    }
    expect(state().order).toHaveLength(10);
    expect(state().order[0]).toBe("r2");
    expect(state().runs["r1"]).toBeUndefined();
    expect(state().order[9]).toBe("r11");
  });

  it("appendSegment 追加分段；run 不存在时按事件携带的 projectId 补建 stub", () => {
    state().startRun({ runId: "r1", projectId: "p1" });
    state().appendSegment({ runId: "r1", projectId: "p1" }, { kind: "text", id: "r1:3", data: { text: "hi" } });
    // 断线缺口：没收到 task-start，分段先到
    state().appendSegment({ runId: "r9", projectId: "p9" }, { kind: "text", id: "r9:1", data: { text: "late" } });

    expect(state().runs["r1"].segments).toEqual([{ kind: "text", id: "r1:3", data: { text: "hi" } }]);
    expect(state().runs["r9"]).toMatchObject({ runId: "r9", projectId: "p9", status: "running" });
  });

  it("分段推导 run 状态：wait→waiting、error→error、finish→finished", () => {
    state().startRun({ runId: "r1", projectId: "p1" });
    state().appendSegment({ runId: "r1", projectId: "p1" }, { kind: "wait", id: "a", waitKind: "QUESTION", summary: "字段清单？" });
    expect(state().runs["r1"].status).toBe("waiting");
    state().appendSegment({ runId: "r1", projectId: "p1" }, { kind: "finish", id: "c", finish: "end" });
    expect(state().runs["r1"].status).toBe("finished");
  });

  it("markSession 记录 sessionId，run 不存在时同样补建", () => {
    state().startRun({ runId: "r1", projectId: "p1" });
    state().markSession({ runId: "r1", projectId: "p1" }, "s-1");
    state().markSession({ runId: "r2", projectId: "p2" }, "s-2");

    expect(state().runs["r1"].sessionId).toBe("s-1");
    expect(state().runs["r2"]).toMatchObject({ runId: "r2", sessionId: "s-2" });
  });

  it("驱逐只由 startRun 触发：迟到事件补建 stub 不得清掉该项目当前 run", () => {
    state().startRun({ runId: "r1", projectId: "p1", prompt: "当前运行" });
    state().appendSegment({ runId: "r1", projectId: "p1" }, { kind: "text", id: "r1:1", data: { text: "进度" } });

    // 已驱逐的旧 run 迟到分段（乱序/重放）：r0 补建，但 r1 及其分段必须原样保留
    state().appendSegment({ runId: "r0", projectId: "p1" }, { kind: "text", id: "r0:1", data: { text: "迟到" } });

    expect(state().runs["r1"]).toMatchObject({ prompt: "当前运行" });
    expect(state().runs["r1"].segments).toHaveLength(1);
    expect(state().runs["r0"]).toBeDefined();
  });

  describe("latestProjectRun（直播视图读口，#23）", () => {
    it("空表 / 该项目无 run → undefined", () => {
      expect(latestProjectRun(state(), "p1")).toBeUndefined();
      state().startRun({ runId: "r1", projectId: "p2" });
      expect(latestProjectRun(state(), "p1")).toBeUndefined();
    });

    it("多项目多 run：取本项目插入序最近的一个（直播只看当前 run）", () => {
      state().startRun({ runId: "r1", projectId: "p1" });
      state().startRun({ runId: "r2", projectId: "p2" });
      state().startRun({ runId: "r3", projectId: "p1" }); // 驱逐 r1
      state().startRun({ runId: "r4", projectId: "p3" });

      expect(latestProjectRun(state(), "p1")?.runId).toBe("r3");
      expect(latestProjectRun(state(), "p2")?.runId).toBe("r2");
    });

    it("迟到事件补建的 stub run 也是可读的最新 run（断线缺口补建不遮蔽）", () => {
      state().startRun({ runId: "r1", projectId: "p1" });
      state().appendSegment({ runId: "r9", projectId: "p1" }, { kind: "text", id: "r9:1", data: {} });

      // order 尾部 = 最近补建的 r9（补建不驱逐，但确是该项目的最新可见 run）
      expect(latestProjectRun(state(), "p1")?.runId).toBe("r9");
    });
  });
});
