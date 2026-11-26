import { beforeEach, describe, expect, it } from "vitest";

import type { RaisedQuestion } from "@/lib/chat/qa";

import { pendingQuestionOf, toWorkClosing, useChatStore, type ChatMessage } from "./chat";

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


  it("未登记 run 的 text / finish 不进对话（runId 锚定；编码 run 的解说不进对话）", () => {
    playMainTurn("p1", "run-1", "需求");

    useChatStore.getState().appendAgentDelta("p1", "run-9", "写代码中", "run-9:3");
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

    s.submitAnswer("p1", "企业客户", "run-1", ["企业客户"]);
    expect(pendingQuestionOf(useChatStore.getState(), "p1")).toBeUndefined();
    expect(useChatStore.getState().chats["p1"]?.turnActive).toBe(true);
    expect(useChatStore.getState().chats["p1"]?.messages.at(-1)).toMatchObject({
      kind: "user",
      text: "企业客户",
    });
    // 勾选回看记账（2026-11-26 用户反馈）：作答提交的勾选记到问答卡上——回看卡
    // 显示自己的勾，不是待答卡的实时勾选
    const answeredCard = useChatStore
      .getState()
      .chats["p1"]?.messages.find((m) => m.kind === "question");
    expect(answeredCard).toMatchObject({ answered: true, finalSelection: ["企业客户"] });

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
    // 失败回滚连带清勾选记账（未落账的勾选不得残留），其后被新问题取代也不泄漏
    expect(pendingQuestionOf(useChatStore.getState(), "p1")?.finalSelection).toBeUndefined();
    s.raiseQuestion("p1", "run-1", question("run-1:11", { question: "预算?", engineRef: "reply-2" }));
    const superseded = useChatStore
      .getState()
      .chats["p1"]?.messages.find((m) => m.kind === "question");
    expect(superseded).toMatchObject({ answered: true });
    expect(superseded?.finalSelection).toBeUndefined();
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
    kind: "user" | "agent" | "question" | "answer" | "closing" | "guide" | "quote",
    overrides: Record<string, unknown> = {},
    runId: string | null = `run-${Math.ceil(id / 2)}`,
  ) => ({ id: String(id), kind, runId, answered: false, ...overrides });

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
      // 旧载荷（#617 前落库）：平台句式摘要 + 判定键——读侧回落可读
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
    // 收尾卡（#89 归对话流常驻）：旧载荷回落——旧 summary 作内核自述文本、
    // 判据按收口不变式回落双 ✓（旧对话史不出坏卡）
    expect(chat?.messages.find((m) => m.kind === "closing")).toMatchObject({
      closing: {
        narrative: "首次生成了系统",
        criteria: { buildPassed: true, previewPassed: true },
        files: [{ path: "/src/App.jsx", added: 40, removed: 0 }],
        durationMs: 183_420,
      },
    });
    // 轻引导带「平台」标签
    expect(chat?.messages.find((m) => m.kind === "agent" && m.label === "平台")).toMatchObject({
      text: "我在这里帮您…",
    });
  });

  it("图片物料附件水合（#286）：image 形态条目收窄为物料 chip 条目、坏形状丢弃", () => {
    useChatStore.getState().hydrate("p1", [
      entry(1, "user", {
        text: "照这张参考图做",
        attachments: [
          { attachmentType: "image", name: "logo.png", path: "materials/3897654321098765432-logo.png" },
          { attachmentType: "image", path: "materials/123-海报.png" },
          { attachmentType: "image", name: "无路径.png" },
          { attachmentType: "annotation", annotation: { kind: "select", anchor: { selector: "button.a" } } },
        ],
      }),
    ]);

    const message = useChatStore.getState().chats["p1"]?.messages[0];
    expect(message).toMatchObject({ kind: "user", text: "照这张参考图做" });
    if (message?.kind !== "user") return;
    // image 形态两件有效（name 缺省回落路径末段）、无路径件丢弃；圈注走 annotations
    expect(message.materials).toEqual([
      { name: "logo.png", path: "materials/3897654321098765432-logo.png" },
      { name: "123-海报.png", path: "materials/123-海报.png" },
    ]);
    expect(message.annotations).toHaveLength(1);
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

  it("问答卡序号水合（#538/#549）：顶层 questionOrdinal 为权威，缺省回落载荷内嵌值", () => {
    const questionPayload = (ordinal?: number) => ({
      runId: "run-1",
      engineRef: "reply-9",
      data: {
        questions: [{ question: "要几分账?" }],
        ...(ordinal !== undefined ? { questionOrdinal: ordinal } : {}),
      },
    });
    useChatStore.getState().hydrate("p1", [
      entry(1, "question", { question: questionPayload(9), questionOrdinal: 2 }), // 顶层覆盖内嵌旧值
      entry(2, "question", { question: questionPayload(3) }), // 顶层缺省 → 回落内嵌
      entry(3, "question", { question: questionPayload() }), // 两处皆无 → 字段缺省
    ]);

    const cards = useChatStore.getState().chats["p1"]?.messages ?? [];
    expect(cards).toHaveLength(3);
    expect(cards.map((m) => (m.kind === "question" ? m.ordinal : undefined))).toEqual([
      2,
      3,
      undefined,
    ]);
  });

  it("run-start 漏收（连接竞态）：question-raised 就地登记 run 成卡，不丢", () => {
    const s = useChatStore.getState();
    // 页面加载时水合只拿到用户发言（问答卡尚未落库）；run-start 因 SSE 连接晚于
    // 发射已漏收（未 noteChatRun）
    s.hydrate("p1", [entry(1, "user", { text: "做个官网" })]);

    // 仅 question-raised 到达（无 noteChatRun 前置）——就地登记 run 并成卡
    s.raiseQuestion("p1", "run-1", question("run-1:5"));

    const chat = useChatStore.getState().chats["p1"];
    expect(chat?.chatRunIds).toContain("run-1");
    expect(pendingQuestionOf(useChatStore.getState(), "p1")?.question).toBe("面向谁?");
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
    // 新一轮在途（乐观 + 流式）
    s.appendUserMessage("p1", "第二句");
    s.noteChatRun("p1", "run-2");
    s.ingestRunStart("p1", "run-2");
    s.appendAgentDelta("p1", "run-2", "受理中…", "run-2:3");

    // 断线期间 run-3 的编码轮收口（closing 落库）→ 重连水合：新 run 块插入不越过开放尾巴
    s.hydrate("p1", [
      entry(1, "user", { text: "第一句" }, "run-1"),
      entry(2, "agent", { text: "第一答" }, "run-1"),
      entry(3, "user", { text: "更早的排队意见" }, "run-9"),
      entry(4, "closing", { closing: { narrative: "更新了系统", criteria: { buildPassed: true, previewPassed: true }, files: [], durationMs: 1 } }, "run-9"),
    ]);

    const chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages.map((m) => m.kind)).toEqual([
      "user", "agent", "user", "closing", "user", "agent",
    ]);
  });

  it("live 收尾卡被水合同 run 块接管（不双卡）：断线补发的 run-finish 回声不双卡", () => {
    const s = useChatStore.getState();
    const closing = {
      narrative: "更新了系统",
      criteria: { buildPassed: true, previewPassed: true },
      files: [],
      durationMs: 5,
    };
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

  it("报价卡水合（#203：无 run 归属的平台发言）：写入序即对话序——卡插在库序位", () => {
    useChatStore.getState().hydrate("p1", [
      entry(1, "user", { text: "做个官网" }, "run-1"),
      entry(2, "quote", { quote: { orderId: "901", event: "quoted" } }, null),
      entry(3, "agent", { text: "好的" }, "run-1"),
    ]);

    const chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages.map((m) => m.kind)).toEqual(["user", "quote", "agent"]);
    expect(chat?.messages[1]).toMatchObject({ kind: "quote", orderId: "901", event: "quoted" });
  });

  it("改价卡追加水合（#204）：与首报卡同流共存、各保各的事件（append-only 不改旧卡）", () => {
    useChatStore.getState().hydrate("p1", [
      entry(1, "quote", { quote: { orderId: "901", event: "quoted" } }, null),
      entry(2, "quote", { quote: { orderId: "901", event: "repriced" } }, null),
    ]);

    const cards = useChatStore.getState().chats["p1"]?.messages.filter((m) => m.kind === "quote") ?? [];
    expect(cards).toHaveLength(2);
    expect(cards[0]).toMatchObject({ orderId: "901", event: "quoted" });
    expect(cards[1]).toMatchObject({ orderId: "901", event: "repriced" });
  });

  it("报价卡增量到达与重水合幂等：同条目原位退位，不双卡、位置不漂", () => {
    const s = useChatStore.getState();
    s.hydrate("p1", [entry(1, "user", { text: "需求" }, "run-1")]);

    // 报价后到达（order-status-changed 失效对话史重查 → 增量水合）
    s.hydrate("p1", [
      entry(1, "user", { text: "需求" }, "run-1"),
      entry(2, "quote", { quote: { orderId: "901", event: "quoted" } }, null),
    ]);
    expect(useChatStore.getState().chats["p1"]?.messages.map((m) => m.kind))
      .toEqual(["user", "quote"]);

    // 重水合（再次失效重拉）：同库条目原位退位——不双卡、位置不漂
    s.hydrate("p1", [
      entry(1, "user", { text: "需求" }, "run-1"),
      entry(2, "quote", { quote: { orderId: "901", event: "quoted" } }, null),
    ]);
    const messages = useChatStore.getState().chats["p1"]?.messages;
    expect(messages?.filter((m) => m.kind === "quote")).toHaveLength(1);
    expect(messages?.map((m) => m.kind)).toEqual(["user", "quote"]);
  });

  it("开放轮在途时报价卡落末尾；轮收口后水合按库序重排", () => {
    const s = useChatStore.getState();
    s.appendUserMessage("p1", "改个颜色");
    s.noteChatRun("p1", "run-1");
    s.ingestRunStart("p1", "run-1");

    // 开放轮期间报价到达：run-1 条目跳过（live 尾巴权威），报价卡不属任何轮、落末尾
    s.hydrate("p1", [
      entry(1, "user", { text: "改个颜色" }, "run-1"),
      entry(2, "quote", { quote: { orderId: "901", event: "quoted" } }, null),
    ]);
    expect(useChatStore.getState().chats["p1"]?.messages.map((m) => m.kind))
      .toEqual(["user", "quote"]);

    // 轮收口后水合：live 片段原位退位，全量按库序重排（报价卡回到库序位）
    s.finishTurn("p1", "run-1");
    s.hydrate("p1", [
      entry(1, "user", { text: "改个颜色" }, "run-1"),
      entry(2, "quote", { quote: { orderId: "901", event: "quoted" } }, null),
      entry(3, "agent", { text: "改好了" }, "run-1"),
    ]);
    const messages = useChatStore.getState().chats["p1"]?.messages;
    expect(messages?.map((m) => m.kind)).toEqual(["user", "quote", "agent"]);
    expect(messages?.filter((m) => m.kind === "quote")).toHaveLength(1);
  });

  it("报价卡载荷缺订单引用：容错弃守，不出坏卡", () => {
    useChatStore.getState().hydrate("p1", [
      entry(1, "quote", { quote: { event: "quoted" } }, null),
    ]);

    expect(useChatStore.getState().chats["p1"]?.messages ?? []).toHaveLength(0);
  });
});

describe("chat store · 试用拦截卡（#533 计费②门②）", () => {
  beforeEach(() => {
    useChatStore.setState({ chats: {} });
  });

  const card = (interceptId: string) => ({
    interceptId,
    lane: "OPINION",
    prompt: "把主色调改成绿色",
    sourceSelected: false,
  });

  it("noteIntercept：一项目一卡——GET 重建幂等（同卡不双卡），新卡取代旧卡", () => {
    const s = useChatStore.getState();
    s.noteIntercept("p1", card("ic-1"), "ic-ic-1");
    s.noteIntercept("p1", card("ic-1"), "ic-ic-1"); // GET 重查回声——同卡幂等
    s.noteIntercept("p1", card("ic-2"), "ic-ic-2"); // 新拦顶替旧待决

    const messages = useChatStore.getState().chats["p1"]?.messages ?? [];
    expect(messages).toHaveLength(1);
    expect(messages[0]).toMatchObject({
      kind: "intercept",
      id: "ic-ic-2",
      settled: false,
      card: { interceptId: "ic-2" },
    });
  });

  it("noteIntercept：拦下即收轮（乐观起轮的对称收口）；不重建用户气泡（恢复受理轮落）", () => {
    useChatStore.getState().startTurn("p1");
    useChatStore.getState().appendUserMessage("p1", "把主色调改成绿色");
    useChatStore.getState().noteIntercept("p1", card("ic-1"), "ic-ic-1");

    const chat = useChatStore.getState().chats["p1"];
    // 乐观气泡原样在位 + 卡尾落；卡内引用原文（不双写气泡）
    expect(chat?.messages.map((m) => m.kind)).toEqual(["user", "intercept"]);
    expect(chat?.turnActive).toBe(false);
  });

  it("noteIntercept：SSE 事件按事件 id 只收一次", () => {
    const s = useChatStore.getState();
    s.noteIntercept("p1", card("ic-1"), "evt-1", "evt-1");
    s.noteIntercept("p1", card("ic-1"), "evt-1", "evt-1"); // 重放

    expect(useChatStore.getState().chats["p1"]?.messages).toHaveLength(1);
  });

  it("settleIntercept：确认落定卡转只读回执（幂等）", () => {
    const s = useChatStore.getState();
    s.noteIntercept("p1", card("ic-1"), "ic-ic-1");
    s.settleIntercept("p1", "ic-1");
    s.settleIntercept("p1", "ic-1"); // 幂等

    const message = useChatStore.getState().chats["p1"]?.messages[0];
    expect(message).toMatchObject({ kind: "intercept", settled: true });
  });

  it("dismissIntercept：中途关闭本地面移除（卡可回——读面仍在、重载重建）", () => {
    const s = useChatStore.getState();
    s.noteIntercept("p1", card("ic-1"), "ic-ic-1");
    s.appendUserMessage("p1", "另一句");
    s.dismissIntercept("p1", "ic-1");

    const messages = useChatStore.getState().chats["p1"]?.messages ?? [];
    expect(messages.map((m) => m.kind)).toEqual(["user"]);
  });

  it("恢复受理去重：SSE run-start 先于确认响应到达（尾条卡引用同文）不再落同文气泡", () => {
    const s = useChatStore.getState();
    s.startTurn("p1");
    s.appendUserMessage("p1", "把主色调改成绿色"); // 乐观气泡（发送侧）
    s.noteIntercept("p1", card("ic-1"), "ic-ic-1");
    s.noteChatRun("p1", "run-resume"); // 确认恢复：runId 回声登记前的竞态径
    s.ingestRunStart("p1", "run-resume", "把主色调改成绿色");

    const chat = useChatStore.getState().chats["p1"];
    // 原文只呈现一次（乐观气泡）——卡是呈现位，恢复轮不重复落气泡
    expect(chat?.messages.map((m) => m.kind)).toEqual(["user", "intercept"]);
    expect(chat?.turnActive).toBe(true);
    expect(chat?.ingestedRunIds).toContain("run-resume");
  });

  it("恢复受理去重：兜底车道 guide-reply 恢复（尾条卡引用同文）不再落同文气泡", () => {
    const s = useChatStore.getState();
    s.noteIntercept("p1", { ...card("ic-1"), lane: "FALLBACK" }, "ic-ic-1");
    s.noteGuideReply("p1", "run-resume", "把主色调改成绿色", "平台", "我来帮你把它做出来", "evt-g1");

    const messages = useChatStore.getState().chats["p1"]?.messages ?? [];
    expect(messages.map((m) => m.kind)).toEqual(["intercept", "agent"]);
  });
});

describe("toWorkClosing · 变更清单真实 diff 收窄（#619）", () => {
  it("携 patch / patchOmitted / filesUnavailable：按可选字段收窄，行数保留", () => {
    const closing = toWorkClosing({
      narrative: "修正完成",
      criteria: { buildPassed: true, previewPassed: true },
      files: [
        { path: "/src/App.jsx", added: 4, removed: 0, patch: "diff --git a/src/App.jsx\n+新\n" },
        { path: "/src/Huge.jsx", added: 500, removed: 0, patchOmitted: true },
      ],
      filesUnavailable: true,
      durationMs: 1000,
    });

    expect(closing?.files).toEqual([
      { path: "/src/App.jsx", added: 4, removed: 0, patch: "diff --git a/src/App.jsx\n+新\n" },
      { path: "/src/Huge.jsx", added: 500, removed: 0, patchOmitted: true },
    ]);
    expect(closing?.filesUnavailable).toBe(true);
  });

  it("旧载荷（无 patch / 无 filesUnavailable）：文件级行数照读，不凭空标降级", () => {
    const closing = toWorkClosing({
      summary: "更新了系统",
      files: [{ path: "/src/App.jsx", added: 40, removed: 0 }],
      durationMs: 1,
    });

    expect(closing?.files).toEqual([{ path: "/src/App.jsx", added: 40, removed: 0 }]);
    expect(closing?.filesUnavailable).toBeUndefined();
    expect(closing?.narrative).toBe("更新了系统");
  });
});

describe("toWorkClosing · 首建目录汇总收窄（#620）", () => {
  it("携 directories：按目录/文件数/± 行数收窄（文件清单照常在场供展开）", () => {
    const closing = toWorkClosing({
      criteria: { buildPassed: true, previewPassed: true },
      files: [
        { path: "/src/App.jsx", added: 4, removed: 0 },
        { path: "/src/pages/Orders.jsx", added: 12, removed: 3 },
      ],
      directories: [
        { path: "/src", fileCount: 1, added: 4, removed: 0 },
        { path: "/src/pages", fileCount: 1, added: 12, removed: 3 },
      ],
      durationMs: 1,
    });

    expect(closing?.directories).toEqual([
      { path: "/src", fileCount: 1, added: 4, removed: 0 },
      { path: "/src/pages", fileCount: 1, added: 12, removed: 3 },
    ]);
    expect(closing?.files).toHaveLength(2);
  });

  it("无 directories（迭代/旧载荷）：不凭空造目录", () => {
    const closing = toWorkClosing({
      files: [{ path: "/src/App.jsx", added: 4, removed: 0, patch: "diff --git a/src/App.jsx\n+新\n" }],
      durationMs: 1,
    });

    expect(closing?.directories).toBeUndefined();
  });

  it("畸形目录条目（缺 path / 非数字）跳过，不坏整卡", () => {
    const closing = toWorkClosing({
      files: [],
      directories: [
        { path: "/src", fileCount: 1, added: 2, removed: 0 },
        { fileCount: 3 },
        { path: 7, fileCount: 1 },
      ],
      durationMs: 1,
    });

    expect(closing?.directories).toEqual([
      { path: "/src", fileCount: 1, added: 2, removed: 0 },
    ]);
  });
});
