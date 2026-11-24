import { QueryClient, QueryObserver } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { useChatStore } from "@/lib/store/chat";
import { useGenerationStore } from "@/lib/store/generation";
import { usePrdNoticesStore } from "@/lib/store/prd-notices";
import { useWorkMessageStore } from "@/lib/store/work-message";
import { queryKeys } from "@/lib/api/keys";

import { dispatchAgentEvent, dispatchNotificationEvent } from "./bridge";
import type { SseEvent } from "./connection";

const agentQc = new QueryClient();

function notificationEvent(type: string, payload: Record<string, unknown>): SseEvent {
  return { id: `p1:${Math.random()}`, data: JSON.stringify({ type, payload, ts: "" }) };
}

/** 建一个 active query 并返回其 fetch 次数探针（invalidate → active query 重拉）。 */
function observeActiveQuery(queryClient: QueryClient, key: readonly unknown[]) {
  let fetches = 0;
  const observer = new QueryObserver(queryClient, {
    queryKey: key,
    queryFn: async () => {
      fetches += 1;
      return { ok: true };
    },
  });
  const unsubscribe = observer.subscribe(() => {});
  return {
    unsubscribe,
    fetchCount: () => fetches,
    /** 等当前 fetch 落定（success 态）——避免 invalidate 撞上 pending fetch 的竞态。 */
    waitForSettled: () =>
      vi.waitFor(() => {
        expect(observer.getCurrentResult().isSuccess).toBe(true);
      }),
  };
}

describe("bridge · 通知 → invalidate（issue #17 清场后名册）", () => {
  let queryClient: QueryClient;
  const teardowns: Array<() => void> = [];

  beforeEach(() => {
    queryClient = new QueryClient();
    queryClient.setDefaultOptions({ queries: { retry: false } });
  });

  afterEach(() => {
    teardowns.splice(0).forEach((fn) => fn());
    queryClient.clear();
  });

  it.each([
    "workspace-created",
    "workspace-destroyed",
    "document-updated",
    "project-renamed",
  ] as const)("%s → projects 域 active query 重拉", async (type) => {
    const projects = observeActiveQuery(queryClient, queryKeys.projects.all);
    teardowns.push(projects.unsubscribe);
    await projects.waitForSettled();

    dispatchNotificationEvent(queryClient, notificationEvent(type, { projectId: "p1" }));

    await vi.waitFor(() => expect(projects.fetchCount()).toBe(2));
  });

  it("preview-ready → 不失效任何域，但写预览查询缓存 URL（#105 载荷写入口，免轮询）", async () => {
    const projects = observeActiveQuery(queryClient, queryKeys.projects.all);
    teardowns.push(projects.unsubscribe);
    await projects.waitForSettled();

    dispatchNotificationEvent(
      queryClient,
      notificationEvent("preview-ready", { projectId: "p1", url: "http://localhost:42659" }),
    );

    await new Promise((r) => setTimeout(r, 20));
    expect(projects.fetchCount()).toBe(1); // 不失效（写缓存非失效——防自反馈循环）
    // 载荷写入口：URL 直写预览查询缓存——前端免轮询、事件驱动拿到 URL
    expect(queryClient.getQueryData(queryKeys.projects.preview("p1"))).toEqual({
      url: "http://localhost:42659",
    });
  });

  it("名册外 type（已删事件 stage-changed / task-updated / preview-updated 等）与坏数据：静默忽略，不抛不失效", async () => {
    useGenerationStore.setState({ generations: {} });
    const projects = observeActiveQuery(queryClient, queryKeys.projects.all);
    teardowns.push(projects.unsubscribe);
    await projects.waitForSettled();

    expect(() =>
      dispatchNotificationEvent(queryClient, notificationEvent("no-such-type", { projectId: "p1" })),
    ).not.toThrow();
    expect(() =>
      dispatchNotificationEvent(queryClient, notificationEvent("task-updated", { projectId: "p1" })),
    ).not.toThrow();
    // 退役的逐修改刷新通知（#106）：到达按名册 miss 忽略——无纪元、无失效（无双重刷新）
    expect(() =>
      dispatchNotificationEvent(queryClient, notificationEvent("preview-updated", { projectId: "p1" })),
    ).not.toThrow();
    expect(() => dispatchNotificationEvent(queryClient, { id: "x", data: "not json" })).not.toThrow();

    await new Promise((r) => setTimeout(r, 20));
    expect(projects.fetchCount()).toBe(1);
    expect(useGenerationStore.getState().generations["p1"]).toBeUndefined();
  });

  it("document-updated → documents 域（PRD 重拉）也失效", async () => {
    const documents = observeActiveQuery(queryClient, queryKeys.documents.all);
    teardowns.push(documents.unsubscribe);
    await documents.waitForSettled();

    dispatchNotificationEvent(
      queryClient,
      notificationEvent("document-updated", { projectId: "p1", documentType: "PRD" }),
    );

    await vi.waitFor(() => expect(documents.fetchCount()).toBe(2));
  });
});

describe("bridge · document-updated 载荷展示例外（#20 修订回路）", () => {
  let queryClient: QueryClient;

  beforeEach(() => {
    queryClient = new QueryClient();
    usePrdNoticesStore.setState({ seen: {}, pending: {} });
  });

  afterEach(() => queryClient.clear());

  it("首次写入（PRD）→ 登记 seen 不出胶囊；再写入 → 置 pending（修订）", () => {
    dispatchNotificationEvent(
      queryClient,
      notificationEvent("document-updated", { projectId: "p1", documentType: "PRD" }),
    );
    expect(usePrdNoticesStore.getState().seen.p1).toBe(true);
    expect(usePrdNoticesStore.getState().pending.p1).toBeUndefined();

    dispatchNotificationEvent(
      queryClient,
      notificationEvent("document-updated", { projectId: "p1", documentType: "PRD" }),
    );
    expect(usePrdNoticesStore.getState().pending.p1).toBeDefined();
  });

  it("非 PRD 文档类型：不写 store（守卫，v1 名册外不惊动）", () => {
    dispatchNotificationEvent(
      queryClient,
      notificationEvent("document-updated", { projectId: "p1", documentType: "SOMETHING_ELSE" }),
    );

    expect(usePrdNoticesStore.getState().seen.p1).toBeUndefined();
  });
});

describe("bridge · 智能体事件 → chat store（对话面，#19）", () => {
  beforeEach(() => {
    useChatStore.setState({ chats: {} });
    useGenerationStore.setState({ generations: {} });
    useWorkMessageStore.setState({ works: {} });
  });

  function agentEvent(type: string, payload: Record<string, unknown>, id = "run1:1"): SseEvent {
    return { id, data: JSON.stringify({ type, payload, ts: "" }) };
  }

  it("run-start(agent=main) 落用户气泡起轮；text(data.delta) 累积气泡（无标签）", () => {
    dispatchAgentEvent(agentQc,
      agentEvent("run-start", { projectId: "p1", runId: "run1", prompt: "做个官网", model: "m1", agent: "main" }, "run1:1"),
    );
    dispatchAgentEvent(agentQc,
      agentEvent("text", { projectId: "p1", runId: "run1", sessionId: "main-p1", data: { delta: "初步理解" } }, "run1:3"),
    );

    const chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages).toEqual([
      { kind: "user", id: expect.any(String), text: "做个官网", runId: "run1" },
      { kind: "agent", id: expect.any(String), text: "初步理解", runId: "run1" },
    ]);
    expect(chat?.turnActive).toBe(true);
  });

  it("run-start(agent=main) → 答询轮进对话（#47 咨询分支同会话，#86）：text 累积", () => {
    dispatchAgentEvent(agentQc,
      agentEvent("run-start", { projectId: "p1", runId: "run1", prompt: "我后台的地址是什么？", model: "m1", agent: "main" }, "run1:1"),
    );
    dispatchAgentEvent(agentQc,
      agentEvent("text", { projectId: "p1", runId: "run1", sessionId: "main-p1", data: { delta: "访问地址是 " } }, "run1:3"),
    );
    dispatchAgentEvent(agentQc,
      agentEvent("text", { projectId: "p1", runId: "run1", sessionId: "main-p1", data: { delta: "http://localhost:32168/" } }, "run1:4"),
    );
    dispatchAgentEvent(agentQc,
      agentEvent("run-finish", { projectId: "p1", runId: "run1", sessionId: "main-p1", finish: "end" }, "run1:5"),
    );

    const chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages).toEqual([
      { kind: "user", id: expect.any(String), text: "我后台的地址是什么？", runId: "run1" },
      {
        kind: "agent",
        id: expect.any(String),
        text: "访问地址是 http://localhost:32168/",
        runId: "run1",
      },
    ]);
    expect(chat?.turnActive).toBe(false);
  });

  it("run-start 无配置键（一次性调用）不进对话；executor 轮不落用户气泡", () => {
    dispatchAgentEvent(agentQc,
      agentEvent("run-start", { projectId: "p1", runId: "rx", prompt: "取名", model: "m1" }, "rx:1"),
    );
    dispatchAgentEvent(agentQc,
      agentEvent("run-start", { projectId: "p1", runId: "rc", prompt: "开始做系统", model: "m1", agent: "executor" }, "rc:1"),
    );

    expect(useChatStore.getState().chats["p1"]).toBeUndefined();
  });

  it("guide-reply（#47 兜底分支）→ 用户气泡 + 平台标签引导气泡（重放按事件 id 只收一次）", () => {
    const frame = agentEvent(
      "guide-reply",
      {
        projectId: "p1",
        runId: "run1",
        prompt: "你好呀",
        label: "平台",
        text: "我在这里帮您把系统做出来：想改哪里、想加什么功能，直接告诉我。",
      },
      "run1:1",
    );
    dispatchAgentEvent(agentQc, frame);
    dispatchAgentEvent(agentQc, { ...frame, id: "run1:1" }); // 重放同事件

    const chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages).toEqual([
      { kind: "user", id: expect.any(String), text: "你好呀", runId: "run1" },
      {
        kind: "agent",
        id: expect.any(String),
        text: "我在这里帮您把系统做出来：想改哪里、想加什么功能，直接告诉我。",
        label: "平台",
        runId: "run1",
      },
    ]);
    expect(chat?.turnActive).toBe(false);
  });

  it("intercept-card（#533 门②）→ 拦截卡落对话流（卡内引用原文、不重建用户气泡；重放按事件 id 只收一次；新卡取代旧卡）", () => {
    dispatchAgentEvent(agentQc, agentEvent(
      "intercept-card",
      {
        projectId: "p1",
        runId: "run-i1",
        interceptId: "ic-1",
        lane: "OPINION",
        prompt: "把主色调改成绿色",
        sourceSelected: false,
      },
      "i1:1",
    ));
    dispatchAgentEvent(agentQc, agentEvent(
      "intercept-card",
      {
        projectId: "p1",
        runId: "run-i1",
        interceptId: "ic-1",
        lane: "OPINION",
        prompt: "把主色调改成绿色",
        sourceSelected: false,
      },
      "i1:1", // 重放同事件
    ));
    // 更新的拦截顶替旧待决（服务端取代）——新卡取代旧卡，只留最新
    dispatchAgentEvent(agentQc, agentEvent(
      "intercept-card",
      {
        projectId: "p1",
        runId: "run-i2",
        interceptId: "ic-2",
        lane: "FALLBACK",
        prompt: "你好呀",
        sourceSelected: true,
        deductionSource: 2,
        sourcePoolBalance: 5000,
      },
      "i2:1",
    ));

    const chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages).toEqual([
      {
        kind: "intercept",
        id: "i2:1",
        settled: false,
        card: {
          interceptId: "ic-2",
          lane: "FALLBACK",
          prompt: "你好呀",
          sourceSelected: true,
          deductionSource: 2,
          sourcePoolBalance: 5000,
          runId: "run-i2",
        },
      },
    ]);
    expect(chat?.turnActive).toBe(false); // 拦下即收口
  });

  it("balance-reminder（#537 段间硬门）→ 余额提醒卡落对话流（显负额、重放只收一次、新卡取代旧卡；run 场卡无留存）", () => {
    dispatchAgentEvent(agentQc, agentEvent(
      "balance-reminder",
      {
        projectId: "p1",
        runId: "run-b1",
        holdId: "bh-1",
        lane: "OPINION",
        prompt: "把报表加个导出",
        sourcePoolBalance: -500,
      },
      "b1:1",
    ));
    dispatchAgentEvent(agentQc, agentEvent(
      "balance-reminder",
      {
        projectId: "p1",
        runId: "run-b1",
        holdId: "bh-1",
        lane: "OPINION",
        prompt: "把报表加个导出",
        sourcePoolBalance: -500,
      },
      "b1:1", // 重放同事件（GET 回声同形）——不双卡
    ));
    // 新拦顶替旧待决（服务端取代）——新卡取代旧卡，只留最新
    dispatchAgentEvent(agentQc, agentEvent(
      "balance-reminder",
      {
        projectId: "p1",
        runId: "run-b2",
        holdId: "bh-2",
        lane: "INQUIRY",
        prompt: "把导出格式说明一下",
        sourcePoolBalance: -1200,
      },
      "b2:1",
    ));
    // run 场拦截（生成/修正）——无留存无原文，取代上一张卡
    dispatchAgentEvent(agentQc, agentEvent(
      "balance-reminder",
      { projectId: "p1", runId: "run-b3", sourcePoolBalance: -300 },
      "b3:1",
    ));

    const chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages).toEqual([
      {
        kind: "balance-reminder",
        id: "b3:1",
        card: { runId: "run-b3", sourcePoolBalance: -300 },
      },
    ]);
    expect(chat?.turnActive).toBe(false); // 拦下即收口
  });

  it("question-raised(QUESTION + data.questions) → 问答卡（engineRef 随卡，作答回传面）", () => {
    // 问答挂起先有对话面 run 登记（run-start agent=main——runId 锚定判定）
    dispatchAgentEvent(agentQc, agentEvent(
      "run-start", { projectId: "p1", runId: "run1", prompt: "做个官网", model: "m1", agent: "main" }, "run1:1"),
    );
    dispatchAgentEvent(agentQc,
      agentEvent(
        "question-raised",
        {
          projectId: "p1",
          runId: "run1",
          sessionId: "main-p1",
          summary: "面向谁?",
          engineRef: "reply-7",
          data: {
            toolCalls: [{ id: "tc-1", name: "ask_user", input: {} }],
            questions: [{ header: "目标用户", question: "面向谁?", multiple: false, custom: true, options: [{ label: "企业客户" }] }],
          },
        },
        "run1:5",
      ),
    );

    const chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages).toHaveLength(2); // run-start 用户气泡 + 问答卡
    expect(chat?.messages[1]).toMatchObject({
      kind: "question",
      runId: "run1",
      engineRef: "reply-7",
      options: ["企业客户"],
    });
  });

  it("question-raised → 失效五问额度读面（追问挂起轮不发 run-finish，否则「已答 N/5」chip 冻在 0/5）", async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const quota = observeActiveQuery(qc, queryKeys.conversation.quota("p1"));
    await quota.waitForSettled();

    dispatchAgentEvent(qc, agentEvent(
      "run-start", { projectId: "p1", runId: "run1", prompt: "做个官网", model: "m1", agent: "main" }, "run1:1"),
    );
    // 作答后的续跑轮再挂一问：上一答已在作答 POST 同步落库，本事件是额度变化点
    dispatchAgentEvent(qc,
      agentEvent(
        "question-raised",
        {
          projectId: "p1",
          runId: "run1",
          sessionId: "main-p1",
          summary: "面向谁?",
          engineRef: "reply-7",
          data: {
            toolCalls: [{ id: "tc-1", name: "ask_user", input: {} }],
            questions: [{ header: "目标用户", question: "面向谁?", multiple: false, custom: true, options: [] }],
          },
        },
        "run1:5",
      ),
    );

    await vi.waitFor(() => expect(quota.fetchCount()).toBe(2));
    quota.unsubscribe();
    qc.clear();
  });

  it("非对话 run 的 text 不进对话（编码 run 解说归工作消息部件）", () => {
    dispatchAgentEvent(agentQc,
      agentEvent("text", { projectId: "p1", runId: "run1", sessionId: "coder-p1", data: { delta: "写代码" } }, "run1:4"),
    );

    expect(useChatStore.getState().chats["p1"]).toBeUndefined();
  });

  it("error / run-finish（对话 run）→ 收轮 + 中断提示", () => {
    dispatchAgentEvent(agentQc, agentEvent("run-start", { projectId: "p1", runId: "run1", prompt: "需求", agent: "main" }, "run1:2"));
    dispatchAgentEvent(agentQc,
      agentEvent("error", { projectId: "p1", runId: "run1", message: "模型调用失败" }, "run1:3"),
    );
    dispatchAgentEvent(agentQc,
      agentEvent("run-finish", { projectId: "p1", runId: "run1", sessionId: "main-p1", finish: "end" }, "run1:4"),
    );

    const chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages.filter((m) => m.kind === "error")).toHaveLength(1);
    expect(chat?.turnActive).toBe(false);
  });

});

describe("bridge · 智能体事件 → generation store（生成面，#22）", () => {
  beforeEach(() => {
    useChatStore.setState({ chats: {} });
    useGenerationStore.setState({ generations: {} });
    useWorkMessageStore.setState({ works: {} });
  });

  function agentEvent(type: string, payload: Record<string, unknown>, id = "run1:1"): SseEvent {
    return { id, data: JSON.stringify({ type, payload, ts: "" }) };
  }

  it("编码 run 事件序：run-start(agent=executor) 登记即 running → 静默重试零信号 → 收口纪元 +1", () => {
    dispatchAgentEvent(agentQc, agentEvent("run-start", { projectId: "p1", runId: "run1", prompt: "开始做系统", agent: "executor" }, "run1:1"));
    expect(useGenerationStore.getState().generations["p1"]?.coderStatus).toBe("running");

    // 静默重试（#84）：重试族过程事实不进用户面事件流——重试不新发 run-start、
    // 用户面 run 身份 = 首试 runId 全程不变（中途只见部件正常生长）；退役名
    // 到达按 miss 忽略，状态不因杂音漂移
    dispatchAgentEvent(agentQc, agentEvent("run-retrying", { projectId: "p1", runId: "run1", attempt: 2, message: "遇到问题，正在重试" }, "run1:9"));
    expect(useGenerationStore.getState().generations["p1"]?.coderStatus).toBe("running");
    dispatchAgentEvent(agentQc, agentEvent("part-action", { projectId: "p1", runId: "run1", sessionId: "coder-p1", engine: "agentscope", toolCallId: "tc-r", toolName: "write_file", state: "running", label: "编写【首页】" }, "run1:10"));

    // 同锚收口 → finished + 预览纪元 +1（重挂信号）
    dispatchAgentEvent(agentQc, agentEvent("run-finish", { projectId: "p1", runId: "run1", sessionId: "coder-p1", finish: "end" }, "run1:11"));

    const generation = useGenerationStore.getState().generations["p1"];
    expect(generation?.coderStatus).toBe("finished");
    expect(generation?.previewEpoch).toBe(1);
    // 工作消息持续生长后定格（重试不重置——run-start 恰一次）
    const work = useWorkMessageStore.getState().works["p1"];
    expect(work?.runId).toBe("run1");
    expect(work?.parts).toHaveLength(1);
    expect(work?.frozen).toBe(true);

    // 编码事件不进对话面（对话面只收主智能体对话轮）
    expect(useChatStore.getState().chats["p1"]).toBeUndefined();
  });

  it("run-start(agent=executor) 失效 projects 域（#630：首建阶段门读生成态，版本记行退场不再连带 documents）；main 轮不失效 documents", () => {
    const invalidate = vi.spyOn(agentQc, "invalidateQueries");

    dispatchAgentEvent(agentQc, agentEvent(
      "run-start", { projectId: "p1", runId: "run1", prompt: "开始做系统", agent: "executor" }, "run1:1",
    ));
    expect(invalidate).toHaveBeenCalledWith({ queryKey: queryKeys.projects.all });
    expect(invalidate).not.toHaveBeenCalledWith({ queryKey: queryKeys.documents.all });

    invalidate.mockClear();
    dispatchAgentEvent(agentQc, agentEvent(
      "run-start", { projectId: "p1", runId: "run2", prompt: "需求", agent: "main" }, "run2:1",
    ));
    expect(invalidate).not.toHaveBeenCalledWith({ queryKey: queryKeys.documents.all });

    invalidate.mockRestore();
  });

  it("无中途闪错（#84 AC④）：失败终态前每一拍都无错误 UI——状态恒 running、对话面零错误气泡", () => {
    // 事件序列断言（与服务端脚本化用例同源）：run-start → 部件生长 →（中间错误
    // 与重试信号不进用户面事件流）→ run-failed 唯一失败终态。逐拍断言错误 UI
    // 永不短暂出现
    const base = { projectId: "p1", runId: "run1", sessionId: "coder-p1", engine: "agentscope" };
    const sequence: [string, Record<string, unknown>][] = [
      ["run-start", { ...base, prompt: "做系统", model: "m", agent: "executor" }],
      ["part-action", { ...base, toolCallId: "tc-1", toolName: "write_file", state: "running", label: "编写【首页】" }],
    ];
    sequence.forEach(([type, payload], index) => {
      dispatchAgentEvent(agentQc, agentEvent(type, payload, `run1:${index + 1}`));
      // 每一拍：生成面已登记且无错误态、对话面零错误气泡（中途闪错 = 回归）
      expect(useGenerationStore.getState().generations["p1"]?.coderStatus).toBe("running");
      expect(useChatStore.getState().chats["p1"]?.messages ?? []).toHaveLength(0);
    });

    // 防御位：编码 run 的 error 事件（服务端投影失守的事件序异常）也不闪任何
    // 错误 UI——对话面不写失败气泡、生成面不写错误态
    dispatchAgentEvent(agentQc, agentEvent("error", { projectId: "p1", runId: "run1", message: "模型调用失败" }, "run1:8"));
    expect(useGenerationStore.getState().generations["p1"]?.coderStatus).toBe("running");
    expect(useChatStore.getState().chats["p1"]?.messages ?? []).toHaveLength(0);

    // 唯一失败终态：run-failed 到达才转 error——工作消息定格、部件留驻
    dispatchAgentEvent(agentQc, agentEvent("run-failed", { projectId: "p1", runId: "run1" }, "run1:9"));
    expect(useGenerationStore.getState().generations["p1"]?.coderStatus).toBe("error");
    const work = useWorkMessageStore.getState().works["p1"];
    expect(work?.frozen).toBe(true);
    expect(work?.parts).toHaveLength(1);
  });

  it("run-finish 重放（同事件 id）不重复计预览纪元", () => {
    dispatchAgentEvent(agentQc, agentEvent("run-start", { projectId: "p1", runId: "run1", prompt: "开始做系统", agent: "executor" }, "run1:1"));
    const finish = agentEvent("run-finish", { projectId: "p1", runId: "run1", sessionId: "coder-p1", finish: "end" }, "run1:9");
    dispatchAgentEvent(agentQc, finish);
    dispatchAgentEvent(agentQc, finish); // 重放（通道带缓冲热流，重挂载重收近期事件）

    expect(useGenerationStore.getState().generations["p1"]?.previewEpoch).toBe(1);
  });

  it("run 级时钟锚（#225，ADR-0010 窄修订）：run-start 信封 ts 落 startedAt、收口信封 ts 落 endedAt；缺 ts 不落锚", () => {
    // 信封 ts 有值的完整生命周期：起跑 → 收口，时钟锚双双落位
    dispatchAgentEvent(
      agentQc,
      { id: "run1:1", data: JSON.stringify({ type: "run-start", payload: { projectId: "p1", runId: "run1", prompt: "开始做系统", agent: "executor", slice: { title: "用户能注册登录", index: 1, total: 3 } }, ts: "2026-09-20T09:00:00Z" }) },
    );
    expect(useWorkMessageStore.getState().works["p1"]?.startedAt)
      .toBe(Date.parse("2026-09-20T09:00:00Z"));

    dispatchAgentEvent(
      agentQc,
      { id: "run1:9", data: JSON.stringify({ type: "run-finish", payload: { projectId: "p1", runId: "run1", sessionId: "coder-p1", finish: "end" }, ts: "2026-09-20T09:06:30Z" }) },
    );
    const work = useWorkMessageStore.getState().works["p1"];
    expect(work?.endedAt).toBe(Date.parse("2026-09-20T09:06:30Z"));
    expect(work?.slice).toEqual({ title: "用户能注册登录", index: 1, total: 3 });

    // 缺 ts（测试信封 / 异常信封）：不落锚——活性锚定真实事件，不伪造起点
    dispatchAgentEvent(agentQc, agentEvent("run-start", { projectId: "p2", runId: "run2", prompt: "做系统", agent: "executor" }, "run2:1"));
    dispatchAgentEvent(agentQc, agentEvent("run-finish", { projectId: "p2", runId: "run2", sessionId: "coder-p2", finish: "end" }, "run2:9"));
    const noTs = useWorkMessageStore.getState().works["p2"];
    expect(noTs?.startedAt).toBeUndefined();
    expect(noTs?.endedAt).toBeUndefined();
  });

  it("超限终态：run-failed → 状态 error（恢复出口的唯一判定锚，run 失败为唯一失败终态）", () => {
    dispatchAgentEvent(agentQc, agentEvent("run-start", { projectId: "p1", runId: "run1", prompt: "开始做系统", agent: "executor" }, "run1:1"));

    dispatchAgentEvent(agentQc, agentEvent("run-failed", { projectId: "p1", runId: "run1" }, "run1:9"));
    expect(useGenerationStore.getState().generations["p1"]?.coderStatus).toBe("error");
  });

  it("用户停止终态（#359）：run-failed 载荷 stopped → 状态 error＋停止旗标（失败档文案分岔「已停止」）", () => {
    dispatchAgentEvent(agentQc, agentEvent("run-start", { projectId: "p1", runId: "run1", prompt: "开始做系统", agent: "executor" }, "run1:1"));

    dispatchAgentEvent(agentQc, agentEvent("run-failed",
      { projectId: "p1", runId: "run1", message: "已按你的要求停止，已完成的进度都保留", stopped: true },
      "run1:9"));
    const generation = useGenerationStore.getState().generations["p1"];
    expect(generation?.coderStatus).toBe("error");
    expect(generation?.coderStoppedByUser).toBe(true);
    expect(generation?.failureReason).toBe("已按你的要求停止，已完成的进度都保留");
  });

  it("run-failed 无 CODER 登记的 runId → 忽略（事件序异常防御位）", () => {
    dispatchAgentEvent(agentQc, agentEvent("run-failed", { projectId: "p1", runId: "ghost" }, "ghost:1"));

    expect(useGenerationStore.getState().generations["p1"]).toBeUndefined();
  });

  it("对话轮与未登记 run 的事件不进生成面", () => {
    dispatchAgentEvent(agentQc, agentEvent("run-start", { projectId: "p1", runId: "run1", prompt: "需求", agent: "main" }, "run1:1"));
    dispatchAgentEvent(agentQc, agentEvent("run-finish", { projectId: "p1", runId: "run1", sessionId: "main-p1", finish: "end" }, "run1:9"));
    // 未登记（无 CODER run-start）的 run 事件：判定锚缺失，不惊动生成面
    dispatchAgentEvent(agentQc, agentEvent("run-finish", { projectId: "p1", runId: "runX", sessionId: "coder-p1", finish: "end" }, "runX:9"));

    expect(useGenerationStore.getState().generations["p1"]).toBeUndefined();
  });

  it("退役五族零写入（#82 收缩验收）：到达按名册 miss 忽略，任何 store 不惊动", () => {
    for (const [type, payload] of [
      ["role-assigned", { projectId: "p1", runId: "r", agent: "executor", roleLabel: "编码智能体", engine: "agentscope" }],
      ["run-created", { projectId: "p1", runId: "r", sessionId: "s" }],
      ["run-retrying", { projectId: "p1", runId: "r", attempt: 2, message: "遇到问题，正在重试" }],
      ["fix-unchanged", { projectId: "p1", runId: "r", reason: "纯文档性修订" }],
      ["dispatch-stage", { projectId: "p1", runId: "r", stage: "fixing" }],
      ["live-text", { projectId: "p1", runId: "r", sessionId: "coder-p1", engine: "agentscope", text: "解说段" }],
      ["live-action", { projectId: "p1", runId: "r", sessionId: "coder-p1", engine: "agentscope", action: "正在编写【订单管理】" }],
      ["live-step", { projectId: "p1", runId: "r", sessionId: "coder-p1", engine: "agentscope", step: 1 }],
    ] as const) {
      expect(() => dispatchAgentEvent(agentQc, agentEvent(type, payload as Record<string, unknown>))).not.toThrow();
    }

    expect(useChatStore.getState().chats).toEqual({});
    expect(useGenerationStore.getState().generations).toEqual({});
    expect(useWorkMessageStore.getState().works).toEqual({});
  });
});

describe("bridge · agent 流 → 工作消息 store（#81 parts 契约前端切新）", () => {
  beforeEach(() => {
    useChatStore.setState({ chats: {} });
    useGenerationStore.setState({ generations: {} });
    useWorkMessageStore.setState({ works: {} });
  });

  /** 事件工厂：带信封 ts（部件时长与起跑锚的时间源）。 */
  function agentEvent(
    type: string,
    payload: Record<string, unknown>,
    id: string,
    ts = "",
  ): SseEvent {
    return { id, data: JSON.stringify({ type, payload, ts }) };
  }

  /**
   * 镜面服务端断言（AgentscopeAgentClientTest·given_scripted_coding_run_
   * when_converse_then_part_events_full_lifecycle）：同一剧本的部件序列 →
   * 前端工作消息部件结构——双侧同源于契约正本（SSE事件清单·消息部件事件节）。
   * #608 起模型文本不直播：解说事件退役，只余动作部件。
   */
  it("编码 run 部件序：动作 started/running/completed → 收口定格", () => {
    const t0 = "2026-09-05T06:00:00.000Z";
    const at = (sec: number) => new Date(Date.parse(t0) + sec * 1000).toISOString();
    const base = { projectId: "p1", runId: "run1", sessionId: "coder-p1", engine: "agentscope" };

    dispatchAgentEvent(agentQc, agentEvent(
      "run-start",
      { ...base, prompt: "做系统", model: "deepseek-v4-pro", agent: "executor" },
      "run1:1",
      at(0),
    ));
    dispatchAgentEvent(agentQc, agentEvent(
      "part-action",
      { ...base, toolCallId: "tc-1", toolName: "write_file", state: "started", label: "编写【代码文件】" },
      "run1:4",
      at(3),
    ));
    dispatchAgentEvent(agentQc, agentEvent(
      "part-action",
      { ...base, toolCallId: "tc-1", toolName: "write_file", state: "running", label: "编写【订单管理】",
        path: "src/app/orders/page.tsx" }, // #618：写文件路径（落定起携带）
      "run1:5",
      at(4),
    ));
    dispatchAgentEvent(agentQc, agentEvent(
      "part-action",
      { ...base, toolCallId: "tc-1", toolName: "write_file", state: "completed", label: "编写【订单管理】",
        path: "src/app/orders/page.tsx" },
      "run1:6",
      at(8),
    ));
    dispatchAgentEvent(agentQc, agentEvent(
      "run-finish",
      { ...base, finish: "end" },
      "run1:8",
      at(10),
    ));

    const work = useWorkMessageStore.getState().works["p1"];
    expect(work?.runId).toBe("run1");
    expect(work?.frozen).toBe(true); // 收口定格
    expect(work?.parts).toEqual([
      {
        kind: "action",
        id: "run1:4",
        source: undefined,
        toolCallId: "tc-1",
        toolName: "write_file",
        state: "completed",
        label: "编写【订单管理】", // running 起具体对象，终态复述不闪换
        error: undefined,
        path: "src/app/orders/page.tsx", // #618 预览跟随的路径来源（跨状态保留）
      },
    ]);
  });

  /**
   * 呈现面清场（#608）：part-check（自检播报）随发射源退役——桥对存量事件按
   * 未知平台类型忽略，不进部件流水、不影响工作消息。
   */
  it("part-check（已退役）：桥忽略该类型事件，不进部件流水", () => {
    const base = { projectId: "p1", runId: "run1", sessionId: "coder-p1" };
    dispatchAgentEvent(agentQc, agentEvent(
      "run-start",
      { ...base, prompt: "做系统", model: "m", engine: "agentscope", agent: "executor" },
      "run1:1",
    ));
    dispatchAgentEvent(agentQc, agentEvent("part-check", { ...base, state: "checking" }, "run1:2"));
    dispatchAgentEvent(agentQc, agentEvent("part-check", { ...base, state: "passed" }, "run1:3"));

    expect(useWorkMessageStore.getState().works["p1"]?.parts).toEqual([]);
  });

  it("run-start 无 executor 配置键（main/一次性调用）不起工作消息；对话部件不误建", () => {
    dispatchAgentEvent(agentQc, agentEvent(
      "run-start",
      { projectId: "p1", runId: "rb", prompt: "追问", model: "m", agent: "main", sessionId: "main-p1" },
      "rb:1",
    ));
    dispatchAgentEvent(agentQc, agentEvent(
      "part-action",
      { projectId: "p1", runId: "rb", sessionId: "main-p1", engine: "agentscope",
        toolCallId: "tc-m", toolName: "write_file", state: "running", label: "编写【PRD】" },
      "rb:2",
    ));

    expect(useWorkMessageStore.getState().works["p1"]).toBeUndefined();
  });

  it("run-start(executor) 携 slice（#118）：工作消息落用户语言标题（#608 起无切片进度）", () => {
    dispatchAgentEvent(agentQc, agentEvent(
      "run-start",
      { projectId: "p1", runId: "run1", prompt: "做系统", model: "m", agent: "executor",
        slice: { title: "系统初始化" } },
      "run1:1",
    ));

    expect(useWorkMessageStore.getState().works["p1"]?.slice).toEqual({ title: "系统初始化" });
  });

  it("更新 run 的 run-start（#118）：slice 只携用户语言标题（无 index/total，无进度）", () => {
    dispatchAgentEvent(agentQc, agentEvent(
      "run-start",
      { projectId: "p1", runId: "run1", prompt: "系统更新", model: "m", agent: "executor",
        slice: { title: "系统更新" } },
      "run1:1",
    ));

    expect(useWorkMessageStore.getState().works["p1"]?.slice).toEqual({ title: "系统更新" });
  });

  it("动作失败态（镜面服务端 given_tool_error_result…）：工具结果 error → part-action failed → 部件定格 failed", () => {
    const t0 = "2026-09-05T06:00:00.000Z";
    const at = (sec: number) => new Date(Date.parse(t0) + sec * 1000).toISOString();
    const base = { projectId: "p1", runId: "run1", sessionId: "coder-p1", engine: "agentscope" };

    dispatchAgentEvent(agentQc, agentEvent(
      "run-start",
      { ...base, prompt: "做系统", model: "m", agent: "executor" },
      "run1:1",
      at(0),
    ));
    for (const [id, sec, state] of [
      ["run1:2", 2, "started"],
      ["run1:3", 3, "running"],
      ["run1:4", 7, "failed"],
    ] as const) {
      dispatchAgentEvent(agentQc, agentEvent(
        "part-action",
        { ...base, toolCallId: "tc-9", toolName: "execute", state, label: "执行【安装依赖】" },
        id,
        at(sec),
      ));
    }

    const parts = useWorkMessageStore.getState().works["p1"]?.parts ?? [];
    const card = parts.find((part) => part.kind === "action");
    expect(card).toMatchObject({
      kind: "action",
      toolCallId: "tc-9",
      state: "failed",
      label: "执行【安装依赖】",
    });
  });

  it("run-failed 也定格（run 失败是唯一失败终态，恢复出口在生成面）", () => {
    const base = { projectId: "p1", runId: "run1", sessionId: "coder-p1", engine: "agentscope" };
    dispatchAgentEvent(agentQc, agentEvent(
      "run-start",
      { ...base, prompt: "做系统", model: "m", agent: "executor" },
      "run1:1",
    ));
    dispatchAgentEvent(agentQc, agentEvent(
      "run-failed",
      { projectId: "p1", runId: "run1" },
      "run1:2",
    ));

    expect(useWorkMessageStore.getState().works["p1"]?.frozen).toBe(true);
  });

  it("重试下一尝试（新 runId 的 run-start）重开工作消息——旧尝试部件不残留", () => {
    const base = { projectId: "p1", sessionId: "coder-p1", engine: "agentscope" };
    dispatchAgentEvent(agentQc, agentEvent(
      "run-start",
      { ...base, runId: "run1", prompt: "做系统", model: "m", agent: "executor" },
      "run1:1",
    ));
    dispatchAgentEvent(agentQc, agentEvent("part-action", { ...base, runId: "run1", toolCallId: "tc-a", toolName: "write_file", state: "running", label: "编写【A】" }, "run1:2"));
    dispatchAgentEvent(agentQc, agentEvent(
      "run-start",
      { ...base, runId: "run2", prompt: "做系统", model: "m", agent: "executor" },
      "run2:1",
    ));

    const work = useWorkMessageStore.getState().works["p1"];
    expect(work?.runId).toBe("run2");
    expect(work?.parts).toEqual([]);
  });

  /**
   * 呈现面清场（#608）：part-plan（步骤清单快照）随发射源退役——桥对存量事件按
   * 未知平台类型忽略，不进部件流水、不落任何计划态。
   */
  it("part-plan（已退役）：桥忽略该类型事件，不进部件流水", () => {
    const base = { projectId: "p1", runId: "run1", sessionId: "coder-p1", engine: "agentscope" };
    dispatchAgentEvent(agentQc, agentEvent(
      "run-start",
      { ...base, prompt: "改配色", model: "m", agent: "executor" },
      "run1:1",
    ));
    dispatchAgentEvent(agentQc, agentEvent("part-plan", { ...base, steps: [
      { id: "s1", title: "读取现有配色", state: "completed" },
    ] }, "run1:2"));

    expect(useWorkMessageStore.getState().works["p1"]?.parts).toEqual([]);
  });

  /**
   * 脱轨信号（#240 part-signal）：守卫吞机器语法段的人话留痕——入 parts 流水
   * （活性行脱轨变体的推导源，呈现归组件）；source 委派位照携；不产动作部件。
   */
  it("part-signal：signal 部件入流水（活性行推导源），重放同 id 去重", () => {
    const base = { projectId: "p1", runId: "run1", sessionId: "coder-p1", engine: "agentscope" };
    dispatchAgentEvent(agentQc, agentEvent(
      "run-start",
      { ...base, prompt: "改配色", model: "m", agent: "executor" },
      "run1:1",
    ));
    dispatchAgentEvent(agentQc, agentEvent(
      "part-signal",
      { ...base, source: "verify", signal: "derailed" },
      "run1:3",
    ));
    // 断线补发重放同 id：去重
    dispatchAgentEvent(agentQc, agentEvent(
      "part-signal",
      { ...base, source: "verify", signal: "derailed" },
      "run1:3",
    ));

    const work = useWorkMessageStore.getState().works["p1"];
    expect(work?.parts).toEqual([
      { kind: "signal", id: "run1:3", source: "verify", signal: "derailed" },
    ]);
  });

  /**
   * part-hint 退役钉（2026-11-12）：决策时微指令不再进用户面——后端已停发，
   * 桥对存量/未知 part-hint 事件按未知平台类型忽略（不进部件流水、不报错）。
   */
  it("part-hint（已退役）：桥忽略该类型事件，不进部件流水", () => {
    const base = { projectId: "p1", runId: "run1", sessionId: "coder-p1" };
    dispatchAgentEvent(agentQc, agentEvent(
      "run-start",
      { ...base, engine: "agentscope", prompt: "改配色", model: "m", agent: "executor" },
      "run1:1",
    ));
    dispatchAgentEvent(agentQc, agentEvent(
      "part-hint",
      { ...base, kind: "failure_stop_loss", text: "同一错误已连续失败 3 次，请换用别的策略。" },
      "run1:3",
    ));

    const work = useWorkMessageStore.getState().works["p1"];
    expect(work?.parts).toEqual([]);
  });
});

describe("bridge · 编码 run 收口 → 项目域失效（#22，失效归桥）", () => {
  it("coder run-finish → projects 域 active query 重拉（generated_at 详情事实）", async () => {
    useGenerationStore.setState({ generations: {} }); // 判定锚从零起算（同文件前序用例残留）
    const queryClient = new QueryClient();
    queryClient.setDefaultOptions({ queries: { retry: false } });
    const projects = observeActiveQuery(queryClient, queryKeys.projects.all);
    await projects.waitForSettled();

    dispatchAgentEvent(
      queryClient,
      {
        id: "run1:9",
        data: JSON.stringify({
          type: "run-finish",
          payload: { projectId: "p1", runId: "run1", sessionId: "coder-p1", finish: "end" },
          ts: "",
        }),
      },
    );
    // 未登记的 run（无 CODER run-start 前置）：不失效
    expect(projects.fetchCount()).toBe(1);

    // 起跑即失效（#222 生成态投影回「生成中」——收口观测直派无对话面事件可搭）
    dispatchAgentEvent(
      queryClient,
      {
        id: "run1:0",
        data: JSON.stringify({
          type: "run-start",
          payload: { projectId: "p1", runId: "run1", prompt: "开始做系统", agent: "executor" },
          ts: "",
        }),
      },
    );
    await vi.waitFor(() => expect(projects.fetchCount()).toBe(2));

    dispatchAgentEvent(
      queryClient,
      {
        id: "run1:9",
        data: JSON.stringify({
          type: "run-finish",
          payload: { projectId: "p1", runId: "run1", sessionId: "coder-p1", finish: "end" },
          ts: "",
        }),
      },
    );

    await vi.waitFor(() => expect(projects.fetchCount()).toBe(3));
    projects.unsubscribe();
    queryClient.clear();
  });

  it("coder run-failed → projects 域 active query 重拉（#222 终态收口在途标记已清——未生成的项目生成态投影回「从未生成」；#604 起失败不落投影）", async () => {
    useGenerationStore.setState({ generations: {} });
    const queryClient = new QueryClient();
    queryClient.setDefaultOptions({ queries: { retry: false } });
    const projects = observeActiveQuery(queryClient, queryKeys.projects.all);
    await projects.waitForSettled();

    dispatchAgentEvent(
      queryClient,
      {
        id: "run2:0",
        data: JSON.stringify({
          type: "run-start",
          payload: { projectId: "p1", runId: "run2", prompt: "做系统", agent: "executor" },
          ts: "",
        }),
      },
    );
    await vi.waitFor(() => expect(projects.fetchCount()).toBe(2));

    dispatchAgentEvent(
      queryClient,
      {
        id: "run2:9",
        data: JSON.stringify({
          type: "run-failed",
          payload: { projectId: "p1", runId: "run2" },
          ts: "",
        }),
      },
    );

    await vi.waitFor(() => expect(projects.fetchCount()).toBe(3));
    projects.unsubscribe();
    queryClient.clear();
  });
});

describe("bridge · run-finish 收口扩载 → 工作消息定格收尾卡（#88）", () => {
  beforeEach(() => {
    useChatStore.setState({ chats: {} });
    useGenerationStore.setState({ generations: {} });
    useWorkMessageStore.setState({ works: {} });
  });

  function agentEvent(
    type: string,
    payload: Record<string, unknown>,
    id: string,
    ts = "",
  ): SseEvent {
    return { id, data: JSON.stringify({ type, payload, ts }) };
  }

  const closing = {
    narrative: "下单页已加入配送范围说明。",
    criteria: { buildPassed: true, previewPassed: true },
    files: [
      { path: "/src/App.jsx", added: 4, removed: 0 },
      { path: "/src/pages/Orders.jsx", added: 12, removed: 3 },
    ],
    durationMs: 183_420,
  };

  /**
   * 镜面服务端断言（IterationAppServiceTest·given_scripted_update_round_when_fix_
   * closes_then_run_finish_carries_authoritative_closing）：编码 run 真收口的
   * run-finish 携 closing——收尾卡归对话流（#89：chat store 常驻、live 到达经
   * appendClosing），工作消息原地定格留驻（#117：部件保留、不再清空——「过程上文、
   * 结果下卡」，判据/清单不由前端推导）；对话史域失效（水合增量接管）。
   */
  it("编码 run 收口携 closing：收尾卡落对话流、工作消息定格留驻、对话史域失效（#89/#117）", () => {
    const base = { projectId: "p1", runId: "run1", sessionId: "coder-p1", engine: "agentscope" };
    dispatchAgentEvent(agentQc, agentEvent(
      "run-start",
      { ...base, prompt: "系统修正：下单页加配送范围说明", model: "m", agent: "executor" },
      "run1:1",
    ));
    dispatchAgentEvent(agentQc, agentEvent("part-action", { ...base, toolCallId: "tc-u", toolName: "edit_file", state: "completed", label: "修改【下单页】" }, "run1:2"));
    dispatchAgentEvent(agentQc, agentEvent(
      "run-finish",
      { ...base, finish: "end", closing },
      "run1:4",
    ));

    const work = useWorkMessageStore.getState().works["p1"];
    expect(work?.frozen).toBe(true);
    expect(work?.parts).toHaveLength(1); // 工作消息定格留驻（#117 不再清空）
    // 收尾卡归对话流（#89）：chat store 常驻（live 到达，id = 事件 id 去重锚）
    const messages = useChatStore.getState().chats["p1"]?.messages ?? [];
    const card = messages.find((message) => message.kind === "closing");
    expect(card).toMatchObject({ runId: "run1", closing });
    // 闭史以 REST 为准：轮收口即失效对话史域（水合增量接管 live 片段）
    const invalidate = vi.spyOn(agentQc, "invalidateQueries");
    dispatchAgentEvent(agentQc, agentEvent(
      "run-finish",
      { projectId: "p1", runId: "run2", sessionId: "coder-p1", finish: "end" },
      "run2:1",
    ));
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["conversation"] });
    invalidate.mockRestore();
  });

  it("咨询/纯追问轮 run-finish 无 closing：不产收尾卡（对话面照常收轮）", () => {
    dispatchAgentEvent(agentQc, agentEvent(
      "run-start",
      { projectId: "p1", runId: "run1", prompt: "系统现在什么状态？", model: "m", agent: "main" },
      "run1:1",
    ));
    dispatchAgentEvent(agentQc, agentEvent(
      "run-finish",
      { projectId: "p1", runId: "run1", sessionId: "main-p1", finish: "end" },
      "run1:2",
    ));

    // 主智能体轮不锚工作消息（对话面走气泡）——无收尾卡可言
    expect(useWorkMessageStore.getState().works["p1"]).toBeUndefined();
    const messages = useChatStore.getState().chats["p1"]?.messages ?? [];
    expect(messages.some((message) => message.kind === "closing")).toBe(false);
    expect(messages.length).toBeGreaterThan(0);
  });

  it("closing 形状异常（非对象）：视同无收尾卡，工作消息保持定格流水不出坏卡", () => {
    const base = { projectId: "p1", runId: "run1", sessionId: "coder-p1", engine: "agentscope" };
    dispatchAgentEvent(agentQc, agentEvent(
      "run-start",
      { ...base, prompt: "做系统", model: "m", agent: "executor" },
      "run1:1",
    ));
    dispatchAgentEvent(agentQc, agentEvent("part-action", { ...base, toolCallId: "tc-x", toolName: "write_file", state: "running", label: "编写【首页】" }, "run1:2"));
    dispatchAgentEvent(agentQc, agentEvent(
      "run-finish",
      { ...base, finish: "end", closing: "坏形状" },
      "run1:3",
    ));

    const work = useWorkMessageStore.getState().works["p1"];
    expect(work?.parts).toHaveLength(1);
    const messages = useChatStore.getState().chats["p1"]?.messages ?? [];
    expect(messages.some((message) => message.kind === "closing")).toBe(false);
  });
});
