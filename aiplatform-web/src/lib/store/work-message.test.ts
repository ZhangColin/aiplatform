import { beforeEach, describe, expect, it } from "vitest";

import {
  useWorkMessageStore,
  workPartsOf,
  type PartEventRef,
} from "@/lib/store/work-message";

/** 事件引用速写。 */
function ref(overrides: Partial<PartEventRef> & Pick<PartEventRef, "eventId">): PartEventRef {
  return { runId: "r1", sessionId: "coder-p1", ...overrides };
}

function work(projectId = "p1") {
  return useWorkMessageStore.getState().works[projectId];
}

function actionInput(overrides: Partial<{
  toolCallId: string;
  state: "started" | "running" | "completed" | "failed";
  label: string;
  error?: string;
}> = {}) {
  return {
    kind: "action" as const,
    toolCallId: "t1",
    toolName: "write_file",
    state: "started" as const,
    label: "编写【代码文件】",
    ...overrides,
  };
}

beforeEach(() => {
  useWorkMessageStore.setState({ works: {} });
});

describe("work-message store · 生长与锚定（#81 parts 契约）", () => {
  it("startWork（run-start agent=executor）即出现：空部件的生长中消息", () => {
    useWorkMessageStore.getState().startWork("p1", "r1");

    expect(work()).toEqual({
      runId: "r1",
      frozen: false,
      parts: [],
      seenEventIds: [],
    });
  });

  it("同 runId 幂等（重放）：不清已长部件；新 runId（下一轮生成）重开", () => {
    useWorkMessageStore.getState().startWork("p1", "r1");
    useWorkMessageStore.getState().notePart("p1", ref({ eventId: "r1:2" }), actionInput());

    useWorkMessageStore.getState().startWork("p1", "r1");
    expect(work()?.parts).toHaveLength(1);

    useWorkMessageStore.getState().startWork("p1", "r2");
    expect(work()?.runId).toBe("r2");
    expect(work()?.parts).toEqual([]);
  });

  it("run 级时钟锚（#225）：startWork 落 startedAt；freezeWork 落 endedAt——定格后值不随重放漂移", () => {
    const { startWork, freezeWork } = useWorkMessageStore.getState();
    startWork("p1", "r1", { title: "系统初始化" }, 1_000);
    expect(work()?.startedAt).toBe(1_000);

    freezeWork("p1", "r1", 65_000);
    expect(work()?.endedAt).toBe(65_000);
    expect(work()?.frozen).toBe(true);

    freezeWork("p1", "r1", 99_000); // 重放再定格：首次为准
    expect(work()?.endedAt).toBe(65_000);
  });

  it("时钟锚缺省（run-start 被淘汰的补建路径）：startedAt 不落", () => {
    useWorkMessageStore.getState().notePart("p1", ref({ eventId: "r9:2", runId: "r9" }), actionInput());
    expect(work()?.startedAt).toBeUndefined();
  });
});

describe("work-message store · 部件投影（动作 + 脱轨信号）", () => {
  it("部件序列投影：到达序即呈现序", () => {
    const { startWork, notePart } = useWorkMessageStore.getState();
    startWork("p1", "r1");
    notePart("p1", ref({ eventId: "r1:2" }), actionInput({ state: "started" }));
    notePart("p1", ref({ eventId: "r1:3" }), { kind: "signal", signal: "derailed" });

    expect(workPartsOf(useWorkMessageStore.getState(), "p1")).toEqual([
      {
        kind: "action",
        id: "r1:2",
        source: undefined,
        toolCallId: "t1",
        toolName: "write_file",
        state: "started",
        label: "编写【代码文件】",
        error: undefined,
      },
      { kind: "signal", id: "r1:3", source: undefined, signal: "derailed" },
    ]);
  });

  it("脱轨信号逐件追加、不原位合并（每次吞段一件）", () => {
    const { startWork, notePart } = useWorkMessageStore.getState();
    startWork("p1", "r1");
    notePart("p1", ref({ eventId: "r1:2" }), { kind: "signal", signal: "derailed" });
    notePart("p1", ref({ eventId: "r1:3" }), { kind: "signal", signal: "derailed" });

    const parts = workPartsOf(useWorkMessageStore.getState(), "p1");
    expect(parts).toHaveLength(2);
    expect(parts.filter((part) => part.kind === "signal")).toHaveLength(2);
  });
});

describe("work-message store · 动作卡全生命周期（toolCallId 锚定原位更新）", () => {
  it("started → running → completed：同一行原位换装（id 不变），label running 起具体", () => {
    const { startWork, notePart } = useWorkMessageStore.getState();
    startWork("p1", "r1");

    notePart("p1", ref({ eventId: "r1:2" }), actionInput({ state: "started" }));
    notePart("p1", ref({ eventId: "r1:3" }), actionInput({ state: "running", label: "编写【订单管理】" }));
    notePart("p1", ref({ eventId: "r1:4" }), actionInput({ state: "completed", label: "编写【订单管理】" }));

    const parts = workPartsOf(useWorkMessageStore.getState(), "p1");
    expect(parts).toHaveLength(1);
    expect(parts[0]).toMatchObject({
      kind: "action",
      id: "r1:2", // React key 不变（行不重挂）
      toolCallId: "t1",
      state: "completed",
      label: "编写【订单管理】",
    });
  });

  it("失败留痕（#229）：failed 携 error 原位落卡；completed 不落 error", () => {
    const { startWork, notePart } = useWorkMessageStore.getState();
    startWork("p1", "r1");

    notePart("p1", ref({ eventId: "r1:2" }), actionInput({ state: "running" }));
    notePart(
      "p1",
      ref({ eventId: "r1:3" }),
      actionInput({ state: "failed", label: "执行【构建】", error: "exit code 1" }),
    );

    expect(workPartsOf(useWorkMessageStore.getState(), "p1")[0]).toMatchObject({
      state: "failed",
      error: "exit code 1",
    });
  });

  it("多个动作并行（不同 toolCallId）各自成行、各自更新", () => {
    const { startWork, notePart } = useWorkMessageStore.getState();
    startWork("p1", "r1");

    notePart("p1", ref({ eventId: "r1:2" }), actionInput({ toolCallId: "t1" }));
    notePart("p1", ref({ eventId: "r1:3" }), actionInput({ toolCallId: "t2" }));
    notePart("p1", ref({ eventId: "r1:4" }), actionInput({ toolCallId: "t1", state: "running" }));

    const parts = workPartsOf(useWorkMessageStore.getState(), "p1");
    expect(parts).toHaveLength(2);
    expect(parts[0]).toMatchObject({ toolCallId: "t1", state: "running" });
    expect(parts[1]).toMatchObject({ toolCallId: "t2", state: "started" });
  });
});

describe("work-message store · 锚定守卫（部件全事件流恒挂，工作消息只锚编码 run）", () => {
  it("无锚 + coder- 会话的部件：补建锚（重放缓冲淘汰 run-start 的刷新回访恢复）", () => {
    useWorkMessageStore.getState().notePart("p1", ref({ eventId: "r9:2", runId: "r9" }), actionInput());

    expect(work()?.runId).toBe("r9");
    expect(work()?.parts).toHaveLength(1);
  });

  it("无锚 + 主智能体会话的部件：不建工作消息（对话面走 text 增量气泡）", () => {
    const { notePart } = useWorkMessageStore.getState();
    notePart("p1", ref({ eventId: "b1:2", sessionId: "main-p1", runId: "rb" }), actionInput());
    notePart("p1", ref({ eventId: "b3:2", sessionId: undefined, runId: "rn" }), actionInput());

    expect(work()).toBeUndefined();
  });

  it("有锚 + 异 runId 的对话部件：锚定消息不受扰", () => {
    const { startWork, notePart } = useWorkMessageStore.getState();
    startWork("p1", "r1");
    notePart("p1", ref({ eventId: "r1:2" }), actionInput());

    notePart("p1", ref({ eventId: "b1:2", sessionId: "main-p1", runId: "rb" }), actionInput());

    expect(work()?.runId).toBe("r1");
    expect(work()?.parts).toHaveLength(1);
  });

  it("生长中锚 + 异 runId 的编码部件（事件序异常）：忽略不闪空", () => {
    const { startWork, notePart } = useWorkMessageStore.getState();
    startWork("p1", "r1");
    notePart("p1", ref({ eventId: "r1:2" }), actionInput());

    notePart("p1", ref({ eventId: "r2:2", runId: "r2" }), actionInput({ toolCallId: "t2" }));

    expect(work()?.runId).toBe("r1");
    expect(work()?.parts).toHaveLength(1);
  });

  it("已定格锚 + 异 runId 的编码部件：重锚新 run", () => {
    const { startWork, notePart, freezeWork } = useWorkMessageStore.getState();
    startWork("p1", "r1");
    notePart("p1", ref({ eventId: "r1:2" }), actionInput());
    freezeWork("p1", "r1");

    notePart("p1", ref({ eventId: "r2:5", runId: "r2" }), actionInput({ toolCallId: "t2" }));

    expect(work()?.runId).toBe("r2");
    expect(work()?.frozen).toBe(false);
  });
});

describe("work-message store · 重放幂等与定格", () => {
  it("部件事件按 SSE id 只收一次", () => {
    const { startWork, notePart } = useWorkMessageStore.getState();
    startWork("p1", "r1");
    const input = actionInput();

    notePart("p1", ref({ eventId: "r1:2" }), input);
    notePart("p1", ref({ eventId: "r1:2" }), input);

    expect(work()?.parts).toHaveLength(1);
  });

  it("freezeWork 定格：部件不再进；重放再定格幂等", () => {
    const { startWork, notePart, freezeWork } = useWorkMessageStore.getState();
    startWork("p1", "r1");
    freezeWork("p1", "r1", 1_000);

    notePart("p1", ref({ eventId: "r1:3" }), actionInput({ toolCallId: "t9" }));

    expect(work()?.frozen).toBe(true);
    expect(work()?.parts).toEqual([]);
  });

  it("freezeWork 非锚定 run（对话轮收口）忽略", () => {
    const { startWork, freezeWork } = useWorkMessageStore.getState();
    startWork("p1", "r1");
    freezeWork("p1", "r2");

    expect(work()?.frozen).toBe(false);
  });
});
