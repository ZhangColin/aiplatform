import { beforeEach, describe, expect, it } from "vitest";

import type { RaisedQuestion } from "@/lib/chat/qa";

import { FALLBACK_AGENT_LABEL, pendingQuestionOf, useChatStore } from "./chat";

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

/** 一轮完整 BA 事件序的模拟（bridge 之外的 store 直驱，事件序语义同 bridge 侧）。 */
function playBaTurn(projectId: string, runId: string, prompt: string) {
  const s = useChatStore.getState();
  s.noteChatRun(projectId, runId);
  s.ingestRunStart(projectId, runId, prompt);
}

/** 一轮助理应答事件序（#47 咨询分支：assist- 会话）。 */
function playAssistantTurn(projectId: string, runId: string, prompt: string) {
  const s = useChatStore.getState();
  s.noteChatRun(projectId, runId);
  s.ingestRunStart(projectId, runId, prompt);
}

describe("chat store · 指令区对话累积（#19）", () => {
  beforeEach(() => {
    useChatStore.setState({ chats: {} });
  });

  it("一轮 BA 事件：run-start 落用户气泡起轮 → text 增量累积成带标签气泡 → run-finish 收轮", () => {
    playBaTurn("p1", "run-1", "给宠物医院做预约系统");
    expect(useChatStore.getState().chats["p1"]?.turnActive).toBe(true); // run-start 起轮

    useChatStore.getState().appendAgentDelta("p1", "run-1", "ba-p1", "初步理解是", "run-1:3");
    useChatStore.getState().appendAgentDelta("p1", "run-1", "ba-p1", "在线预约。", "run-1:4");
    useChatStore.getState().finishTurn("p1", "ba-p1");

    const chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages).toEqual([
      { kind: "user", id: expect.any(String), text: "给宠物医院做预约系统" },
      {
        kind: "agent",
        id: expect.any(String),
        text: "初步理解是在线预约。",
        label: FALLBACK_AGENT_LABEL,
        runId: "run-1",
      },
    ]);
    expect(chat?.turnActive).toBe(false); // 收口落轮
  });

  it("助理轮（#47）：assist- 会话的 text 进对话；打字指示用通用标签", () => {
    playAssistantTurn("p1", "run-5", "我后台的地址是什么？");
    expect(useChatStore.getState().chats["p1"]?.activeRoleLabel).toBe(FALLBACK_AGENT_LABEL);

    useChatStore.getState().appendAgentDelta("p1", "run-5", "assist-p1", "访问地址是 ", "run-5:3");
    useChatStore.getState().appendAgentDelta("p1", "run-5", "assist-p1", "http://localhost:32168/", "run-5:4");
    useChatStore.getState().finishTurn("p1", "assist-p1");

    const chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages.at(-1)).toMatchObject({
      kind: "agent",
      text: "访问地址是 http://localhost:32168/",
      label: FALLBACK_AGENT_LABEL,
    });
    expect(chat?.turnActive).toBe(false);
    expect(chat?.activeRoleLabel).toBeUndefined(); // 收轮清标签
  });

  it("角色事件缺失的残段（重放边界）：text 仍进对话，标签回退通用", () => {
    useChatStore.getState().appendAgentDelta("p1", "run-9", "ba-p1", "旧轮残段", "run-9:2");

    expect(useChatStore.getState().chats["p1"]?.messages.at(-1)).toMatchObject({
      kind: "agent",
      label: FALLBACK_AGENT_LABEL,
    });
  });

  it("平台轻引导（#47 兜底）：prompt 落用户气泡（重放重建）+ 平台标签气泡 + 收轮；重放不重复", () => {
    const s = useChatStore.getState();
    s.startTurn("p1"); // 乐观起轮
    s.noteGuideReply("p1", "你好呀", "平台", "我在这里帮您把系统做出来…", "run-7:1");
    s.noteGuideReply("p1", "你好呀", "平台", "我在这里帮您把系统做出来…", "run-7:1"); // 重放

    const chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages).toEqual([
      { kind: "user", id: expect.any(String), text: "你好呀" },
      { kind: "agent", id: expect.any(String), text: "我在这里帮您把系统做出来…", label: "平台" },
    ]);
    expect(chat?.turnActive).toBe(false); // 引导即收口
  });

  it("轻引导的乐观用户气泡去重：尾条同文不再补（即时到达场景）", () => {
    const s = useChatStore.getState();
    s.appendUserMessage("p1", "我想下单"); // 乐观发送
    s.noteGuideReply("p1", "我想下单", "平台", "请点「确认下单」按钮…", "run-8:1");

    expect(useChatStore.getState().chats["p1"]?.messages).toHaveLength(2);
  });

  it("非对话会话的 text / finish 不进对话（coder- 前缀，片 2 起的判别面）", () => {
    playBaTurn("p1", "run-1", "需求");

    useChatStore.getState().appendAgentDelta("p1", "run-1", "coder-p1", "写代码中", "run-1:3");
    useChatStore.getState().finishTurn("p1", "coder-p1");
    useChatStore.getState().appendAgentDelta("p2", "run-2", "ba-p2", "串台", "run-2:1");

    const chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages).toHaveLength(1); // 只有用户气泡
  });

  it("run-start 只认登记过的对话面 run；重放同事件不重复落气泡", () => {
    // 未登记的 run（run-start 角色键缺失等）不落用户气泡
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
    s.noteChatRun("p1", "run-2");
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
    useChatStore.getState().appendAgentDelta("p1", "run-1", "ba-p1", "先问一句", "run-1:3");

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

    // 作答后 BA 续流：新一轮智能体气泡另起（问题卡之后不拼接）
    useChatStore.getState().appendAgentDelta("p1", "run-1", "ba-p1", "收到，下一个问题…", "run-1:9");
    expect(useChatStore.getState().chats["p1"]?.messages.at(-1)).toMatchObject({
      kind: "agent",
      text: "收到，下一个问题…",
      label: FALLBACK_AGENT_LABEL,
    });

    // 发送失败：撤回气泡 + 问题卡重开
    s.removeMessage("p1", useChatStore.getState().chats["p1"]!.messages.at(-2)!.id);
    s.reopenQuestion("p1");
    expect(pendingQuestionOf(useChatStore.getState(), "p1")?.question).toBe("面向谁?");
  });

  it("新问题取代旧未答问题（一轮一问）；重放同事件不重复成卡", () => {
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

  it("error 事件落中断提示并收轮（对话轮不死寂）；非对话 run 不落；重放不重复", () => {
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
    s.appendAgentDelta("p1", "run-1", "ba-p1", "你好", "run-1:3");
    s.appendAgentDelta("p1", "run-1", "ba-p1", "你好", "run-1:3"); // 重放同事件

    const chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages.filter((m) => m.kind === "agent")).toEqual([
      { kind: "agent", id: expect.any(String), text: "你好", label: FALLBACK_AGENT_LABEL, runId: "run-1" },
    ]);
  });

  it("相邻轮的气泡不互并（runId 锚定——角色标签退役后的交错保护）", () => {
    playBaTurn("p1", "run-1", "需求");
    playAssistantTurn("p1", "run-2", "地址是什么");
    const s = useChatStore.getState();
    s.appendAgentDelta("p1", "run-1", "ba-p1", "BA 的话", "run-1:3");
    s.appendAgentDelta("p1", "run-2", "assist-p1", "助理的回答", "run-2:3");

    const chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages.filter((m) => m.kind === "agent").map((m) => [m.text, m.runId])).toEqual([
      ["BA 的话", "run-1"],
      ["助理的回答", "run-2"],
    ]);
  });

  it("同标签不同 run 也不互并（runId 锚定合并——事件缺失残段同为回退标签的交错保护）", () => {
    playBaTurn("p1", "run-1", "需求");
    const s2 = useChatStore.getState();
    // 两个残段 run 都没有 role 事件（同为回退标签「智能体」）
    s2.appendAgentDelta("p1", "run-a", "ba-p1", "第一段", "run-a:1");
    s2.appendAgentDelta("p1", "run-b", "ba-p1", "第二段", "run-b:1");

    const chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages.filter((m) => m.kind === "agent").map((m) => [m.text, m.runId])).toEqual([
      ["第一段", "run-a"],
      ["第二段", "run-b"],
    ]);
  });

});
