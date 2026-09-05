import { beforeEach, describe, expect, it } from "vitest";

import type { RaisedQuestion } from "@/lib/chat/qa";

import { pendingQuestionOf, useChatStore, type ChatMessage } from "./chat";

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

/** 一轮主智能体对话事件序的模拟（bridge 之外的 store 直驱，事件序语义同 bridge 侧）。 */
function playMainTurn(projectId: string, runId: string, prompt: string) {
  const s = useChatStore.getState();
  s.noteChatRun(projectId, runId);
  s.ingestRunStart(projectId, runId, prompt);
}

describe("chat store · 对话区对话累积（#19，#86 单会话收敛）", () => {
  beforeEach(() => {
    useChatStore.setState({ chats: {} });
  });

  it("一轮主智能体事件：run-start 落用户气泡起轮 → text 增量累积成无标签气泡 → run-finish 收轮", () => {
    playMainTurn("p1", "run-1", "给宠物医院做预约系统");
    expect(useChatStore.getState().chats["p1"]?.turnActive).toBe(true); // run-start 起轮

    useChatStore.getState().appendAgentDelta("p1", "run-1", "初步理解是", "run-1:3");
    useChatStore.getState().appendAgentDelta("p1", "run-1", "在线预约。", "run-1:4");
    useChatStore.getState().finishTurn("p1", "run-1");

    const chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages).toEqual([
      { kind: "user", id: expect.any(String), text: "给宠物医院做预约系统", runId: "run-1" },
      {
        kind: "agent",
        id: expect.any(String),
        text: "初步理解是在线预约。",
        runId: "run-1",
      },
    ]);
    expect(chat?.turnActive).toBe(false); // 收口落轮
  });

  it("智能体话语无署名（#86 界面上只有一个「它」）；平台轻引导自带「平台」", () => {
    playMainTurn("p1", "run-1", "需求");
    useChatStore.getState().appendAgentDelta("p1", "run-1", "好的", "run-1:3");
    useChatStore.getState().noteGuideReply("p1", "run-7", "你好呀", "平台", "我在这里帮您…", "run-7:1");

    const agents = (useChatStore.getState().chats["p1"]?.messages ?? []).filter(
      (m): m is Extract<ChatMessage, { kind: "agent" }> => m.kind === "agent",
    );
    expect(agents.find((m) => m.text === "好的")?.label).toBeUndefined();
    expect(agents.find((m) => m.text === "我在这里帮您…")?.label).toBe("平台");
  });

  it("受理动作卡（#87）：受理事件落卡（受理中）→ 该轮收口（run-finish/error）落定；重放同事件不重复；问答挂起不落定", () => {
    playMainTurn("p1", "run-1", "把系统的主色调改成绿色");
    const s = useChatStore.getState();
    s.noteAcceptance("p1", "run-1", "run-1:0");
    s.noteAcceptance("p1", "run-1", "run-1:0"); // 重放同事件 id 只收一次
    s.appendAgentDelta("p1", "run-1", "我来处理这个需求", "run-1:3");
    s.raiseQuestion("p1", "run-1", question("run-1:5")); // 追问挂起：受理仍在途，卡不落定
    s.submitAnswer("p1", "要薄荷绿", "run-1");
    // 续跑收口：桥在收口事件处合成收轮 + 受理卡落定（受理完成——衔接更新 run
    // 工作消息）；异 run 落定为无操作（每卡锚自己的受理轮）
    s.finishTurn("p1", "run-1");
    s.settleAcceptance("p1", "run-1");
    s.settleAcceptance("p1", "run-9");

    const chat = useChatStore.getState().chats["p1"];
    const cards = chat?.messages.filter((m): m is Extract<ChatMessage, { kind: "acceptance" }> => m.kind === "acceptance");
    expect(cards).toHaveLength(1);
    expect(cards?.[0]).toMatchObject({ kind: "acceptance", runId: "run-1", settled: true });
    // 呈现序：用户气泡 → 受理卡 → 解说（动作卡先于解说——意见已接住的反馈先出）
    expect(chat?.messages.map((m) => m.kind)).toEqual(["user", "acceptance", "agent", "question", "user"]);
  });

  it("受理卡落定幂等（收口与失败事件双达不闪换）；error 落定同路（炸轮不死转）", () => {
    useChatStore.getState().noteAcceptance("p1", "run-1", "run-1:0");
    useChatStore.getState().settleAcceptance("p1", "run-1");
    useChatStore.getState().settleAcceptance("p1", "run-1");

    const cards = useChatStore.getState().chats["p1"]?.messages.filter(
      (m): m is Extract<ChatMessage, { kind: "acceptance" }> => m.kind === "acceptance",
    );
    expect(cards).toHaveLength(1);
    expect(cards?.[0].settled).toBe(true);
  });

  it("发送失败撤尾卡（#87 code-review）：受理事件已到而提交失败——尾随未落定卡随乐观气泡同撤；已落定/非尾卡不动", () => {
    const s = useChatStore.getState();
    s.appendUserMessage("p1", "把系统的主色调改成绿色");
    s.noteAcceptance("p1", "run-1", "run-1:0"); // 服务端守卫后发出、提交随即失败
    s.removeTrailingAcceptance("p1");

    expect(useChatStore.getState().chats["p1"]?.messages.map((m) => m.kind)).toEqual(["user"]);

    // 已落定的尾卡（历史轮）不撤；解说之后的卡非尾卡（受理在途的正常序）不撤
    s.noteAcceptance("p1", "run-2", "run-2:0");
    s.settleAcceptance("p1", "run-2");
    s.removeTrailingAcceptance("p1");
    s.noteChatRun("p1", "run-3");
    s.noteAcceptance("p1", "run-3", "run-3:0");
    s.appendAgentDelta("p1", "run-3", "受理中", "run-3:1"); // 解说落在卡后（正常序）
    s.removeTrailingAcceptance("p1"); // 尾条是解说非卡 → 无操作

    expect(useChatStore.getState().chats["p1"]?.messages.map((m) => m.kind)).toEqual([
      "user", "acceptance", "acceptance", "agent",
    ]);
  });

  it("重放序：受理事件先于 run-start 到达时，用户气泡插到受理卡上方（#87——意见在卡上）", () => {
    const s = useChatStore.getState();
    s.noteChatRun("p1", "run-1");
    s.noteAcceptance("p1", "run-1", "run-1:1"); // 服务端守卫后即发：事件序在前
    s.ingestRunStart("p1", "run-1", "把系统的主色调改成绿色"); // 重放重建用户气泡
    s.appendAgentDelta("p1", "run-1", "我来处理", "run-1:3");

    expect(useChatStore.getState().chats["p1"]?.messages.map((m) => m.kind)).toEqual([
      "user", "acceptance", "agent",
    ]);
  });

  it("未登记 run 的 text / 问答 / finish 不进对话（runId 锚定；编码 run 的解说不进对话）", () => {
    playMainTurn("p1", "run-1", "需求");

    useChatStore.getState().appendAgentDelta("p1", "run-9", "写代码中", "run-9:3");
    useChatStore.getState().raiseQuestion("p1", "run-9", question("run-9:5"));
    useChatStore.getState().finishTurn("p1", "run-9");
    useChatStore.getState().appendAgentDelta("p2", "run-2", "串台", "run-2:1");

    const chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages).toHaveLength(1); // 只有用户气泡
    expect(chat?.turnActive).toBe(true); // 未登记 run 的 finish 不收轮
  });

  it("平台轻引导（#47 兜底）：prompt 落用户气泡（重放重建）+ 平台标签气泡 + 收轮；重放不重复", () => {
    const s = useChatStore.getState();
    s.startTurn("p1"); // 乐观起轮
    s.noteGuideReply("p1", "run-7", "你好呀", "平台", "我在这里帮您把系统做出来…", "run-7:1");
    s.noteGuideReply("p1", "run-7", "你好呀", "平台", "我在这里帮您把系统做出来…", "run-7:1"); // 重放

    const chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages).toEqual([
      { kind: "user", id: expect.any(String), text: "你好呀", runId: "run-7" },
      { kind: "agent", id: expect.any(String), text: "我在这里帮您把系统做出来…", label: "平台", runId: "run-7" },
    ]);
    expect(chat?.turnActive).toBe(false); // 引导即收口
  });

  it("轻引导的乐观用户气泡去重：尾条同文不再补（即时到达场景）", () => {
    const s = useChatStore.getState();
    s.appendUserMessage("p1", "我想下单"); // 乐观发送
    s.noteGuideReply("p1", "run-8", "我想下单", "平台", "请点「确认下单」按钮…", "run-8:1");

    expect(useChatStore.getState().chats["p1"]?.messages).toHaveLength(2);
  });

  it("run-start 只认登记过的对话面 run；重放同事件不重复落气泡", () => {
    // 未登记的 run（run-start 配置键缺失等）不落用户气泡
    useChatStore.getState().ingestRunStart("p1", "run-x", "不进对话");
    expect(useChatStore.getState().chats["p1"]).toBeUndefined();

    playMainTurn("p1", "run-1", "第一句");
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
    playMainTurn("p1", "run-1", "需求");
    useChatStore.getState().appendAgentDelta("p1", "run-1", "先问一句", "run-1:3");

    const s = useChatStore.getState();
    s.raiseQuestion("p1", "run-1", question("run-1:5"));
    expect(pendingQuestionOf(useChatStore.getState(), "p1")?.question).toBe("面向谁?");
    expect(useChatStore.getState().chats["p1"]?.turnActive).toBe(false);

    s.submitAnswer("p1", "企业客户", "run-1");
    expect(pendingQuestionOf(useChatStore.getState(), "p1")).toBeUndefined();
    expect(useChatStore.getState().chats["p1"]?.turnActive).toBe(true);
    expect(useChatStore.getState().chats["p1"]?.messages.at(-1)).toMatchObject({
      kind: "user",
      text: "企业客户",
    });

    // 作答后主智能体续流：新一轮智能体气泡另起（问题卡之后不拼接）
    useChatStore.getState().appendAgentDelta("p1", "run-1", "收到，下一个问题…", "run-1:9");
    expect(useChatStore.getState().chats["p1"]?.messages.at(-1)).toMatchObject({
      kind: "agent",
      text: "收到，下一个问题…",
    });

    // 发送失败：撤回气泡 + 问题卡重开
    s.removeMessage("p1", useChatStore.getState().chats["p1"]!.messages.at(-2)!.id);
    s.reopenQuestion("p1");
    expect(pendingQuestionOf(useChatStore.getState(), "p1")?.question).toBe("面向谁?");
  });

  it("新问题取代旧未答问题（一轮一问）；重放同事件不重复成卡", () => {
    const s = useChatStore.getState();
    playMainTurn("p1", "run-1", "需求");
    s.raiseQuestion("p1", "run-1", question("run-1:5"));
    s.raiseQuestion("p1", "run-1", question("run-1:9", { question: "范围?", engineRef: "reply-2" })); // 取代
    s.raiseQuestion("p1", "run-1", question("run-1:9", { question: "范围?", engineRef: "reply-2" })); // 重放

    const chat = useChatStore.getState().chats["p1"];
    const cards = chat?.messages.filter((m) => m.kind === "question") ?? [];
    expect(cards).toHaveLength(2);
    expect(pendingQuestionOf(useChatStore.getState(), "p1")?.question).toBe("范围?");
  });

  it("error 事件落中断提示并收轮（对话轮不死寂）；非对话 run 不落；重放不重复", () => {
    playMainTurn("p1", "run-1", "需求");
    const s = useChatStore.getState();
    s.noteTurnError("p1", "run-1", "模型调用失败", "run-1:7");
    s.noteTurnError("p1", "run-1", "模型调用失败", "run-1:7"); // 重放
    s.noteTurnError("p1", "run-x", "别的 run", "run-x:1"); // 未登记 run

    const chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages.filter((m) => m.kind === "error")).toHaveLength(1);
    expect(chat?.turnActive).toBe(false);
  });

  it("text 增量重放去重（事件 id 只收一次）——路由回访不双份", () => {
    playMainTurn("p1", "run-1", "需求");
    const s = useChatStore.getState();
    s.appendAgentDelta("p1", "run-1", "你好", "run-1:3");
    s.appendAgentDelta("p1", "run-1", "你好", "run-1:3"); // 重放同事件

    const chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages.filter((m) => m.kind === "agent")).toEqual([
      { kind: "agent", id: expect.any(String), text: "你好", runId: "run-1" },
    ]);
  });

  it("相邻轮的气泡不互并（runId 锚定——追问与答询连续同会话、不同轮各成气泡）", () => {
    playMainTurn("p1", "run-1", "把主色调改成绿色");
    playMainTurn("p1", "run-2", "我后台的地址是什么？");
    const s = useChatStore.getState();
    s.appendAgentDelta("p1", "run-1", "意见轮的话", "run-1:3");
    s.appendAgentDelta("p1", "run-2", "答询轮的回答", "run-2:3");

    const chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages.filter((m) => m.kind === "agent").map((m) => [m.text, m.runId])).toEqual([
      ["意见轮的话", "run-1"],
      ["答询轮的回答", "run-2"],
    ]);
  });

  it("平台引导气泡之后的智能体增量不与其互并（带标签气泡不是增量合并目标）", () => {
    playMainTurn("p1", "run-1", "需求");
    const s = useChatStore.getState();
    s.noteGuideReply("p1", "run-7", "你好呀", "平台", "我在这里帮您…", "run-7:1");
    s.appendAgentDelta("p1", "run-1", "智能体的话", "run-1:3");

    const chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages.filter((m) => m.kind === "agent").map((m) => [m.text, m.label])).toEqual([
      ["我在这里帮您…", "平台"],
      ["智能体的话", undefined],
    ]);
  });
});

describe("chat store · 对话史水合（#89 落库④：闭史以 REST 为准）", () => {
  beforeEach(() => {
    useChatStore.setState({ chats: {} });
  });

  const entry = (
    id: number,
    kind: "user" | "agent" | "question" | "answer" | "closing" | "guide",
    overrides: Record<string, unknown> = {},
    runId = `run-${Math.ceil(id / 2)}`,
  ) => ({ id, kind, runId, answered: false, ...overrides });

  it("空库条目水合：消息序 = 写入序（发言 → 回复 → 问答卡 → 作答 → 收尾卡 → 轻引导），作答渲染同用户气泡", () => {
    useChatStore.getState().hydrate("p1", [
      entry(1, "user", { text: "给宠物医院做预约系统" }),
      entry(2, "agent", { text: "收到，先确认关键点" }),
      entry(3, "question", {
        text: undefined,
        answered: false,
        question: {
          runId: "run-2",
          engineRef: "reply-1",
          summary: "面向谁?",
          data: {
            toolCalls: [{ id: "tc-1", name: "ask_user", input: {} }],
            questions: [{ header: "目标用户", question: "面向谁?", multiple: false, custom: true, options: [{ label: "企业客户" }] }],
          },
        },
      }),
      entry(4, "answer", { text: "企业客户" }),
      entry(5, "agent", { text: "好的，按企业客户梳理" }),
      entry(6, "closing", {
        text: undefined,
        closing: {
          summary: "首次生成了系统",
          prdChanged: false,
          systemChanged: true,
          files: [{ path: "/src/App.jsx", added: 40, removed: 0 }],
          durationMs: 183_420,
        },
      }),
      entry(7, "guide", { text: "我在这里帮您…" }, "run-4"),
    ]);

    const chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages.map((m) => m.kind)).toEqual([
      "user", "agent", "question", "user", "agent", "closing", "agent",
    ]);
    // 问答卡可重建可作答（载荷原样），已答位随库
    const pending = pendingQuestionOf(useChatStore.getState(), "p1");
    expect(pending?.question).toBe("面向谁?");
    expect(pending?.engineRef).toBe("reply-1");
    // 收尾卡（#89 归对话流常驻）
    expect(chat?.messages.find((m) => m.kind === "closing")).toMatchObject({
      closing: { summary: "首次生成了系统" },
    });
    // 轻引导带「平台」标签
    expect(chat?.messages.find((m) => m.kind === "agent" && m.label === "平台")).toMatchObject({
      text: "我在这里帮您…",
    });
  });

  it("未答问答卡水合：待答可作答（挂起重放的刷新重建面）；已答卡不进待答", () => {
    useChatStore.getState().hydrate("p1", [
      entry(1, "user", { text: "需求" }),
      entry(2, "question", { question: { runId: "run-1", engineRef: "reply-9", data: { questions: [{ question: "要几分账?" }] } } }),
    ]);

    expect(pendingQuestionOf(useChatStore.getState(), "p1")?.question).toBe("要几分账?");

    // 作答收口后的回访（新挂载面）：已答卡水合不进待答
    useChatStore.setState({ chats: {} });
    useChatStore.getState().hydrate("p1", [
      entry(1, "user", { text: "需求" }),
      entry(2, "question", { answered: true, question: { runId: "run-1", engineRef: "reply-9", data: { questions: [{ question: "要几分账?" }] } } }),
    ]);

    expect(pendingQuestionOf(useChatStore.getState(), "p1")).toBeUndefined();
  });

  it("增量水合接管 live 片段（run 整体替换幂等）：闭史原位替换乐观气泡与流式段，不双条", () => {
    // live：乐观气泡 + 流式回复段（POST 返回后标记 runId）
    const s = useChatStore.getState();
    s.appendUserMessage("p1", "加个导出按钮");
    s.noteChatRun("p1", "run-1");
    s.ingestRunStart("p1", "run-1");
    s.appendAgentDelta("p1", "run-1", "我来加导出", "run-1:3");

    // 第一次水合（该轮尚开放——流式中末条目）：开放轮条目跳过，live 尾巴权威
    s.hydrate("p1", [
      entry(1, "user", { text: "加个导出按钮" }, "run-1"),
    ]);
    let chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages.map((m) => m.kind)).toEqual(["user", "agent"]);
    expect(chat?.openRunId).toBe("run-1");

    // 轮收口（run-finish 清开放锚）→ 水合增量接管：库块整体替换 live 片段
    s.finishTurn("p1", "run-1");
    s.hydrate("p1", [
      entry(1, "user", { text: "加个导出按钮" }, "run-1"),
      entry(2, "agent", { text: "我来加导出，已完成" }, "run-1"),
    ]);
    chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages.map((m) => `${m.kind}:${"text" in m ? m.text : ""}`)).toEqual([
      "user:加个导出按钮",
      "agent:我来加导出，已完成",
    ]);
    expect(chat?.openRunId).toBeUndefined();
  });

  it("多轮增量水合：新 run 的库块插在开放尾巴之前（顺序正确），旧轮不动", () => {
    const s = useChatStore.getState();
    s.hydrate("p1", [
      entry(1, "user", { text: "第一句" }, "run-1"),
      entry(2, "agent", { text: "第一答" }, "run-1"),
    ]);
    // 新一轮在途（乐观 + 受理卡 + 流式）
    s.appendUserMessage("p1", "第二句");
    s.noteChatRun("p1", "run-2");
    s.noteAcceptance("p1", "run-2", "run-2:0");
    s.ingestRunStart("p1", "run-2");
    s.appendAgentDelta("p1", "run-2", "受理中…", "run-2:3");

    // 断线期间 run-3 的编码轮收口（closing 落库）→ 重连水合：新 run 块插入不越过开放尾巴
    s.hydrate("p1", [
      entry(1, "user", { text: "第一句" }, "run-1"),
      entry(2, "agent", { text: "第一答" }, "run-1"),
      entry(3, "user", { text: "更早的排队意见" }, "run-9"),
      entry(4, "closing", { closing: { summary: "更新了系统", prdChanged: false, systemChanged: true, files: [], durationMs: 1 } }, "run-9"),
    ]);

    const chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages.map((m) => m.kind)).toEqual([
      "user", "agent", "user", "closing", "user", "acceptance", "agent",
    ]);
  });

  it("live 收尾卡被水合同 run 块接管（不双卡）：断线补发的 run-finish 回声不双卡", () => {
    const s = useChatStore.getState();
    const closing = { summary: "更新了系统", prdChanged: false, systemChanged: true, files: [], durationMs: 5 };
    // live 到达（bridge：run-finish 携 closing → appendClosing）
    s.appendClosing("p1", "run-1", closing, "run-1:9");
    s.appendClosing("p1", "run-1", closing, "run-1:9"); // 补发回声：事件 id 只收一次
    s.appendClosing("p1", "run-1", closing, "run-1:10"); // 异事件 id 但同 run：不双卡

    // 水合接管：库块整体替换（live 卡原位退位、库卡唯一）
    s.hydrate("p1", [entry(1, "closing", { closing }, "run-1")]);

    const cards = useChatStore.getState().chats["p1"]?.messages.filter((m) => m.kind === "closing") ?? [];
    expect(cards).toHaveLength(1);
    expect(cards[0]).toMatchObject({ id: "h1", runId: "run-1", closing });
  });

  it("同轮重放与水合去重：重放序（断线窗口）重建的卡片与问答回声不与水合卡双生", () => {
    // 水合建挂起卡（未答）→ 断线窗口补发的 question-raised 事件回声（异事件 id）
    const s = useChatStore.getState();
    s.hydrate("p1", [
      entry(1, "user", { text: "需求" }, "run-1"),
      entry(2, "question", { question: { runId: "run-1", engineRef: "reply-1", data: { questions: [{ question: "面向谁?" }] } } }, "run-1"),
    ]);
    s.noteChatRun("p1", "run-1"); // 水合登记的对话面 run
    s.raiseQuestion("p1", "run-1", question("run-1:5"));

    const cards = useChatStore.getState().chats["p1"]?.messages.filter((m) => m.kind === "question") ?? [];
    expect(cards).toHaveLength(1); // 同锚（engineRef）不双卡
  });
});
