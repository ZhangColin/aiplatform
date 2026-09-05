import { beforeEach, describe, expect, it } from "vitest";

import {
  useWorkMessageStore,
  workPartsOf,
  type PartEventRef,
  type WorkPart,
} from "@/lib/store/work-message";

/** 事件引用速写（信封 ts → at）。 */
function ref(overrides: Partial<PartEventRef> & Pick<PartEventRef, "eventId">): PartEventRef {
  return { runId: "r1", sessionId: "coder-p1", at: 0, ...overrides };
}

function work(projectId = "p1") {
  return useWorkMessageStore.getState().works[projectId];
}

beforeEach(() => {
  useWorkMessageStore.setState({ works: {} });
});

describe("work-message store · 生长与锚定（#81 parts 契约）", () => {
  it("startWork（run-start role=CODER）即出现：空部件的生长中消息，startedAt 落锚", () => {
    useWorkMessageStore.getState().startWork("p1", "r1", 1000);

    expect(work()).toEqual({
      runId: "r1",
      startedAt: 1000,
      frozen: false,
      parts: [],
      seenEventIds: [],
    });
  });

  it("同 runId 幂等（重放）：不清已长部件；新 runId（重试下一尝试/新一轮）重开", () => {
    useWorkMessageStore.getState().startWork("p1", "r1", 1000);
    useWorkMessageStore.getState().notePart("p1", ref({ eventId: "r1:2" }), { kind: "text", text: "开始" });

    useWorkMessageStore.getState().startWork("p1", "r1", 9999);
    expect(work()?.parts).toHaveLength(1);

    useWorkMessageStore.getState().startWork("p1", "r2", 20000);
    expect(work()?.runId).toBe("r2");
    expect(work()?.parts).toEqual([]);
  });

  it("部件序列投影（步骤 → 解说 → 动作 → 解说）：到达序即呈现序", () => {
    const { startWork, notePart } = useWorkMessageStore.getState();
    startWork("p1", "r1", 0);
    notePart("p1", ref({ eventId: "r1:1" }), { kind: "step", step: 1 });
    notePart("p1", ref({ eventId: "r1:2" }), { kind: "text", text: "正在编写订单管理页面。" });
    notePart("p1", ref({ eventId: "r1:3", at: 1000 }), {
      kind: "action",
      toolCallId: "tc-1",
      toolName: "write_file",
      state: "started",
      label: "编写【代码文件】",
    });
    notePart("p1", ref({ eventId: "r1:5" }), { kind: "text", text: "订单管理完成" });

    const kinds = work()?.parts.map((part) => part.kind);
    expect(kinds).toEqual(["step", "text", "action", "text"]);
  });
});

describe("work-message store · 动作卡全生命周期（toolCallId 锚定原位更新）", () => {
  it("started → running → completed：同一行原位换装（React key 不变），label running 起具体，终态落时长", () => {
    const { startWork, notePart } = useWorkMessageStore.getState();
    startWork("p1", "r1", 0);
    const action = {
      kind: "action",
      toolCallId: "tc-1",
      toolName: "write_file",
    } as const;

    notePart("p1", ref({ eventId: "r1:3", at: 1_000 }), { ...action, state: "started", label: "编写【代码文件】" });
    notePart("p1", ref({ eventId: "r1:4", at: 1_500 }), { ...action, state: "running", label: "编写【订单管理】" });
    notePart("p1", ref({ eventId: "r1:6", at: 5_300 }), { ...action, state: "completed", label: "编写【订单管理】" });

    const parts = work()?.parts ?? [];
    expect(parts).toHaveLength(1); // 原位更新不另起行
    const card = parts[0] as Extract<WorkPart, { kind: "action" }>;
    expect(card.id).toBe("r1:3"); // 首见事件 id 为键，状态更新不改键
    expect(card.state).toBe("completed");
    expect(card.label).toBe("编写【订单管理】");
    expect(card.startedAt).toBe(1_000);
    expect(card.endedAt).toBe(5_300); // 时长 = 4.3s
  });

  it("failed 终态：endedAt 落定（动作层状态，与 run 终态无关）", () => {
    const { startWork, notePart } = useWorkMessageStore.getState();
    startWork("p1", "r1", 0);
    notePart("p1", ref({ eventId: "r1:2", at: 2_000 }), {
      kind: "action",
      toolCallId: "tc-9",
      toolName: "command",
      state: "started",
      label: "执行【命令】",
    });
    notePart("p1", ref({ eventId: "r1:3", at: 9_000 }), {
      kind: "action",
      toolCallId: "tc-9",
      toolName: "command",
      state: "failed",
      label: "执行【命令】",
    });

    const card = work()?.parts[0] as Extract<WorkPart, { kind: "action" }>;
    expect(card.state).toBe("failed");
    expect(card.endedAt).toBe(9_000);
  });

  it("多个动作并行（不同 toolCallId）各自成行、各自更新", () => {
    const { startWork, notePart } = useWorkMessageStore.getState();
    startWork("p1", "r1", 0);
    notePart("p1", ref({ eventId: "r1:2", at: 100 }), {
      kind: "action",
      toolCallId: "tc-1",
      toolName: "write_file",
      state: "started",
      label: "编写【A】",
    });
    notePart("p1", ref({ eventId: "r1:3", at: 200 }), {
      kind: "action",
      toolCallId: "tc-2",
      toolName: "command",
      state: "started",
      label: "执行【B】",
    });
    notePart("p1", ref({ eventId: "r1:4", at: 300 }), {
      kind: "action",
      toolCallId: "tc-1",
      toolName: "write_file",
      state: "completed",
      label: "编写【A】",
    });

    const actions = (work()?.parts ?? []).filter(
      (part): part is Extract<WorkPart, { kind: "action" }> => part.kind === "action",
    );
    expect(actions.map((a) => a.toolCallId)).toEqual(["tc-1", "tc-2"]);
    expect(actions.map((a) => a.state)).toEqual(["completed", "started"]);
  });
});

describe("work-message store · 锚定守卫（部件全事件流恒挂，工作消息只锚编码 run）", () => {
  it("无锚 + coder- 会话的部件：补建锚（重放缓冲淘汰 run-start 的刷新回访恢复）", () => {
    useWorkMessageStore.getState().notePart("p1", ref({ eventId: "r1:9", at: 5_000, runId: "r9" }), {
      kind: "text",
      text: "正在收尾",
    });

    expect(work()?.runId).toBe("r9");
    expect(work()?.startedAt).toBe(5_000); // 补建锚取首部件 ts
    expect(workPartsOf(useWorkMessageStore.getState(), "p1")).toHaveLength(1);
  });

  it("无锚 + BA/助理会话的部件：不建工作消息（对话面走 text 增量气泡，部件并行不双渲染）", () => {
    const { notePart } = useWorkMessageStore.getState();
    notePart("p1", ref({ eventId: "b1:2", sessionId: "ba-p1", runId: "rb" }), { kind: "text", text: "BA 解说" });
    notePart("p1", ref({ eventId: "b2:2", sessionId: "assist-p1", runId: "ra" }), { kind: "text", text: "助理作答" });
    notePart("p1", ref({ eventId: "b3:2", sessionId: undefined, runId: "rn" }), { kind: "text", text: "无会话" });

    expect(work()).toBeUndefined();
  });

  it("有锚 + 异 runId 的 BA 部件：锚定消息不受扰（不重开不进件）", () => {
    const { startWork, notePart } = useWorkMessageStore.getState();
    startWork("p1", "r1", 0);
    notePart("p1", ref({ eventId: "r1:2" }), { kind: "text", text: "编码解说" });

    notePart("p1", ref({ eventId: "b1:2", sessionId: "ba-p1", runId: "rb" }), { kind: "text", text: "BA 插话" });

    expect(work()?.runId).toBe("r1");
    expect(work()?.parts).toHaveLength(1);
  });

  it("生长中锚 + 异 runId 的编码部件（迟到/重放残段）：忽略不闪空（重锚只在无锚或已定格时）", () => {
    const { startWork, notePart } = useWorkMessageStore.getState();
    startWork("p1", "r2", 0);
    notePart("p1", ref({ eventId: "r2:2", runId: "r2" }), { kind: "text", text: "当前尝试解说" });

    // 上一尝试 r1 的残段（同 coder 会话、异 runId）迟到：不清当前锚
    notePart("p1", ref({ eventId: "r1:9", runId: "r1", at: 99_999 }), { kind: "text", text: "残段" });

    expect(work()?.runId).toBe("r2");
    expect(work()?.parts).toHaveLength(1);
  });

  it("已定格锚 + 异 runId 的编码部件（新 run 的 run-start 被缓冲淘汰）：重锚新 run", () => {
    const { startWork, notePart, freezeWork } = useWorkMessageStore.getState();
    startWork("p1", "r1", 0);
    notePart("p1", ref({ eventId: "r1:2" }), { kind: "text", text: "上一轮解说" });
    freezeWork("p1", "r1", 10_000);

    notePart("p1", ref({ eventId: "r2:5", runId: "r2", at: 50_000 }), { kind: "text", text: "新一轮解说" });

    expect(work()?.runId).toBe("r2");
    expect(work()?.frozen).toBe(false);
    expect(work()?.startedAt).toBe(50_000);
    expect(work()?.parts).toHaveLength(1);
  });
});

describe("work-message store · 重放幂等与定格", () => {
  it("部件事件按 SSE id 只收一次（挂载重连重收缓冲不双长）", () => {
    const { startWork, notePart } = useWorkMessageStore.getState();
    startWork("p1", "r1", 0);
    const input = { kind: "text", text: "同一段" } as const;

    notePart("p1", ref({ eventId: "r1:2" }), input);
    notePart("p1", ref({ eventId: "r1:2" }), input);

    expect(work()?.parts).toHaveLength(1);
  });

  it("freezeWork（run-finish / run-failed）定格：部件不再进、frozenAt 落；重放再定格幂等", () => {
    const { startWork, notePart, freezeWork } = useWorkMessageStore.getState();
    startWork("p1", "r1", 0);
    notePart("p1", ref({ eventId: "r1:2", at: 100 }), {
      kind: "action",
      toolCallId: "tc-1",
      toolName: "write_file",
      state: "running",
      label: "编写【订单管理】",
    });

    freezeWork("p1", "r1", 8_000);
    notePart("p1", ref({ eventId: "r1:3", at: 9_000 }), { kind: "text", text: "迟到部件" });
    freezeWork("p1", "r1", 9_500);

    expect(work()?.frozen).toBe(true);
    expect(work()?.frozenAt).toBe(8_000); // 首次定格为准
    expect(work()?.parts).toHaveLength(1);
  });

  it("freezeWork 非锚定 run（BA 收口）忽略", () => {
    const { startWork, freezeWork } = useWorkMessageStore.getState();
    startWork("p1", "r1", 0);

    freezeWork("p1", "rb", 100);

    expect(work()?.frozen).toBe(false);
  });
});

describe("work-message store · 确认卡（#83 权限确认：长在工作消息流）", () => {
  function permissionPart() {
    return work()?.parts.find(
      (part): part is Extract<WorkPart, { kind: "permission" }> => part.kind === "permission",
    );
  }

  it("permission-required 部件入消息（pending 态、engineRef/summary 随卡）；重放按事件 id 去重", () => {
    const { startWork, notePart } = useWorkMessageStore.getState();
    startWork("p1", "r1", 0);
    const input = { kind: "permission", engineRef: "reply-1", summary: "rm -rf /workspace/data" } as const;

    notePart("p1", ref({ eventId: "r1:2", at: 1_000 }), input);
    notePart("p1", ref({ eventId: "r1:2", at: 1_000 }), input);

    const card = permissionPart();
    expect(work()?.parts).toHaveLength(1);
    expect(card).toMatchObject({
      id: "r1:2",
      engineRef: "reply-1",
      summary: "rm -rf /workspace/data",
      state: "pending",
      at: 1_000,
    });
  });

  it("resolvePermission 落定（permission-resolved 事件与作答乐观更新双写口）：同值幂等、异值以事件为准", () => {
    const { startWork, notePart, resolvePermission } = useWorkMessageStore.getState();
    startWork("p1", "r1", 0);
    notePart("p1", ref({ eventId: "r1:2" }), {
      kind: "permission",
      engineRef: "reply-1",
      summary: "清理数据",
    });

    resolvePermission("p1", "reply-1", "denied"); // 乐观：拒绝
    expect(permissionPart()?.state).toBe("denied");
    resolvePermission("p1", "reply-1", "denied"); // 事件双达（同值）幂等
    expect(permissionPart()?.state).toBe("denied");
    resolvePermission("p1", "reply-1", "pending"); // 作答失败回滚重开
    expect(permissionPart()?.state).toBe("pending");
  });

  it("resolvePermission 未知 engineRef（重放缺口/异项目）忽略；无锚项目忽略", () => {
    const { startWork, notePart, resolvePermission } = useWorkMessageStore.getState();
    startWork("p1", "r1", 0);
    notePart("p1", ref({ eventId: "r1:2" }), {
      kind: "permission",
      engineRef: "reply-1",
      summary: "清理数据",
    });

    resolvePermission("p1", "reply-x", "approved");
    resolvePermission("p9", "reply-1", "approved");

    expect(permissionPart()?.state).toBe("pending");
  });
});

describe("work-message store · 自检播报（#85：一场 run 一个自检部件，原位换装）", () => {
  function checkPart() {
    return work()?.parts.find(
      (part): part is Extract<WorkPart, { kind: "check" }> => part.kind === "check",
    );
  }

  it("checking → passed：同一行原位换装（React key 不变），起跑锚取首条 checking、落定记 endedAt", () => {
    const { startWork, notePart } = useWorkMessageStore.getState();
    startWork("p1", "r1", 0);

    notePart("p1", ref({ eventId: "r1:8", at: 8_000 }), { kind: "check", state: "checking" });
    expect(checkPart()).toEqual({ kind: "check", id: "r1:8", state: "checking", startedAt: 8_000 });

    notePart("p1", ref({ eventId: "r1:9", at: 9_500 }), { kind: "check", state: "passed" });
    expect(work()?.parts).toHaveLength(1);
    expect(checkPart()).toEqual({
      kind: "check",
      id: "r1:8", // 原位更新不改键
      state: "passed",
      startedAt: 8_000,
      endedAt: 9_500, // 落定时间戳（探活结果留痕，收尾卡统计行随 #88 消费）
    });
  });

  it("静默重试口径：重复 checking 幂等（不闪换、起跑锚不重置、部件引用不变）", () => {
    const { startWork, notePart } = useWorkMessageStore.getState();
    startWork("p1", "r1", 0);
    notePart("p1", ref({ eventId: "r1:8", at: 8_000 }), { kind: "check", state: "checking" });
    const partsBefore = work()?.parts;

    // 首试核验未过（不出 ❌）→ 重试核验再发 checking——用户面仍是同一次检查
    //（事件 id 簿记照收 = 重放去重口径；部件面零变更 = 不触发部件重渲染）
    notePart("p1", ref({ eventId: "r1:12", at: 20_000 }), { kind: "check", state: "checking" });

    expect(work()?.parts).toBe(partsBefore); // 部件引用不变
    expect(checkPart()?.startedAt).toBe(8_000);
  });

  it("末次核验未过：checking → failed（与 run-failed 同窗口，❌ 定格留驻）", () => {
    const { startWork, notePart, freezeWork } = useWorkMessageStore.getState();
    startWork("p1", "r1", 0);
    notePart("p1", ref({ eventId: "r1:8", at: 8_000 }), { kind: "check", state: "checking" });

    notePart("p1", ref({ eventId: "r1:9", at: 9_000 }), { kind: "check", state: "failed" });
    freezeWork("p1", "r1", 9_100);

    expect(checkPart()).toMatchObject({ state: "failed", endedAt: 9_000 });
    expect(work()?.frozen).toBe(true);
  });

  it("定格后自检事件不进（重放/迟到防御）；未锚定 BA 会话的 part-check 不建工作消息", () => {
    const { startWork, notePart, freezeWork } = useWorkMessageStore.getState();
    startWork("p1", "r1", 0);
    freezeWork("p1", "r1", 5_000);

    notePart("p1", ref({ eventId: "r1:9" }), { kind: "check", state: "checking" });
    expect(work()?.parts).toEqual([]);

    // 部件全事件流恒挂但只锚编码 run：BA 会话的 part-check 不补建工作消息
    notePart("p1", ref({ eventId: "rb:3", runId: "rb", sessionId: "ba-p1" }), {
      kind: "check",
      state: "checking",
    });
    expect(work()?.parts).toEqual([]);
  });
});
