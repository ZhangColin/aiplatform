import { beforeEach, describe, expect, it } from "vitest";

import type { RaisedQuestion } from "@/lib/chat/qa";

import { pendingQuestionOf, useChatStore } from "./chat";

function question(id: string, overrides: Partial<RaisedQuestion> = {}): RaisedQuestion {
  return {
    id,
    runId: "run-1",
    engineRef: "reply-1",
    header: "目标用户",
    question: "面向谁?",
    multiple: false,
    options: ["企业客户", "个人用户"],
    toolCalls: [{ id: "tc-1", name: "ask_user", input: {} }],
    ...overrides,
  };
}

/** 一轮完整访谈的帧序模拟（bridge 之外的 store 直驱，帧序语义同 bridge 侧）。 */
function playBaTurn(projectId: string, runId: string, prompt: string) {
  const s = useChatStore.getState();
  s.noteBaRun(projectId, runId);
  s.ingestRunStart(projectId, runId, prompt);
}

describe("chat store · 指令区对话累积（#19）", () => {
  beforeEach(() => {
    useChatStore.setState({ chats: {} });
  });

  it("一轮 BA 帧：run-start 落用户气泡起轮 → text 增量累积成 BA 气泡 → run-finish 收轮", () => {
    playBaTurn("p1", "run-1", "给宠物医院做预约系统");
    expect(useChatStore.getState().chats["p1"]?.turnActive).toBe(true); // run-start 起轮

    useChatStore.getState().appendBaDelta("p1", "ba-p1", "初步理解是", "run-1:3");
    useChatStore.getState().appendBaDelta("p1", "ba-p1", "在线预约。", "run-1:4");
    useChatStore.getState().finishTurn("p1", "ba-p1");

    const chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages.map((m) => [m.kind, m.kind === "question" ? m.question : m.text])).toEqual([
      ["user", "给宠物医院做预约系统"],
      ["ba", "初步理解是在线预约。"],
    ]);
    expect(chat?.turnActive).toBe(false); // 收口落轮
  });

  it("非 BA 会话的 text / finish 不进对话（coder- 前缀，片 2 起的判别面）", () => {
    playBaTurn("p1", "run-1", "需求");

    useChatStore.getState().appendBaDelta("p1", "coder-p1", "写代码中", "run-1:3");
    useChatStore.getState().finishTurn("p1", "coder-p1");
    useChatStore.getState().appendBaDelta("p2", "ba-p2", "串台", "run-2:1");

    const chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages).toHaveLength(1); // 只有用户气泡
  });

  it("run-start 只认 role-assigned(BA) 登记过的 run；重放同帧不重复落气泡", () => {
    // 未登记的 run（role-assigned 帧被缓冲淘汰等）不落用户气泡
    useChatStore.getState().ingestRunStart("p1", "run-x", "不进对话");
    expect(useChatStore.getState().chats["p1"]).toBeUndefined();

    playBaTurn("p1", "run-1", "第一句");
    useChatStore.getState().ingestRunStart("p1", "run-1", "第一句"); // 重放/回声
    expect(useChatStore.getState().chats["p1"]?.messages).toHaveLength(1);
  });

  it("乐观发送与 run-start 回声去重：尾条同文不重复（runId 落定后彻底闭口）", () => {
    const s = useChatStore.getState();
    const id = s.appendUserMessage("p1", "加个会员功能"); // 乐观
    s.startTurn("p1");
    s.noteBaRun("p1", "run-2");
    s.ingestRunStart("p1", "run-2", "加个会员功能"); // 回声：尾条同文
    s.markRunIngested("p1", "run-2"); // POST 返回 runId
    s.ingestRunStart("p1", "run-2", "加个会员功能"); // 再回声（重放）：已闭口

    const chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages).toHaveLength(1);
    expect(chat?.turnActive).toBe(true);

    // 发送失败撤回：气泡移除、收轮
    s.removeMessage("p1", id);
    s.endTurn("p1");
    expect(useChatStore.getState().chats["p1"]?.messages).toHaveLength(0);
    expect(useChatStore.getState().chats["p1"]?.turnActive).toBe(false);
  });

  it("问答：question-raised 落问答卡并收轮；作答落用户气泡 + 卡转已答 + 起轮；失败重开", () => {
    playBaTurn("p1", "run-1", "需求");
    useChatStore.getState().appendBaDelta("p1", "ba-p1", "先问一句", "run-1:3");

    const s = useChatStore.getState();
    s.raiseQuestion("p1", "ba-p1", question("run-1:5"));
    expect(pendingQuestionOf(useChatStore.getState(), "p1")?.question).toBe("面向谁?");
    expect(useChatStore.getState().chats["p1"]?.turnActive).toBe(false);

    s.submitAnswer("p1", "企业客户");
    expect(pendingQuestionOf(useChatStore.getState(), "p1")).toBeUndefined();
    expect(useChatStore.getState().chats["p1"]?.turnActive).toBe(true);
    expect(useChatStore.getState().chats["p1"]?.messages.at(-1)).toMatchObject({
      kind: "user",
      text: "企业客户",
    });

    // 作答后 BA 续流：新一轮 BA 气泡另起（问题卡之后不拼接）
    useChatStore.getState().appendBaDelta("p1", "ba-p1", "收到，下一个问题…", "run-1:9");
    expect(useChatStore.getState().chats["p1"]?.messages.at(-1)).toMatchObject({
      kind: "ba",
      text: "收到，下一个问题…",
    });

    // 发送失败：撤回气泡 + 问题卡重开
    s.removeMessage("p1", useChatStore.getState().chats["p1"]!.messages.at(-2)!.id);
    s.reopenQuestion("p1");
    expect(pendingQuestionOf(useChatStore.getState(), "p1")?.question).toBe("面向谁?");
  });

  it("新问题取代旧未答问题（一轮一问）；重放同帧不重复成卡", () => {
    const s = useChatStore.getState();
    playBaTurn("p1", "run-1", "需求");
    s.raiseQuestion("p1", "ba-p1", question("run-1:5"));
    s.raiseQuestion("p1", "ba-p1", question("run-1:9", { question: "范围?" })); // 取代
    s.raiseQuestion("p1", "ba-p1", question("run-1:9", { question: "范围?" })); // 重放

    const chat = useChatStore.getState().chats["p1"];
    const cards = chat?.messages.filter((m) => m.kind === "question") ?? [];
    expect(cards).toHaveLength(2);
    expect(pendingQuestionOf(useChatStore.getState(), "p1")?.question).toBe("范围?");
  });

  it("error 帧落中断提示并收轮（BA 轮不死寂）；非 BA run 不落；重放不重复", () => {
    playBaTurn("p1", "run-1", "需求");
    const s = useChatStore.getState();
    s.noteTurnError("p1", "run-1", "模型调用失败", "run-1:7");
    s.noteTurnError("p1", "run-1", "模型调用失败", "run-1:7"); // 重放
    s.noteTurnError("p1", "run-x", "别的 run", "run-x:1"); // 未登记 run

    const chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages.filter((m) => m.kind === "error")).toHaveLength(1);
    expect(chat?.turnActive).toBe(false);
  });

  it("text 增量重放去重（事件 id 只收一次）——路由回访不双份", () => {
    playBaTurn("p1", "run-1", "需求");
    const s = useChatStore.getState();
    s.appendBaDelta("p1", "ba-p1", "你好", "run-1:3");
    s.appendBaDelta("p1", "ba-p1", "你好", "run-1:3"); // 重放同帧

    const chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages.filter((m) => m.kind === "ba")).toEqual([
      { kind: "ba", id: expect.any(String), text: "你好" },
    ]);
  });
});
