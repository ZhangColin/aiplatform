import { QueryClient, QueryObserver } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { useAgentRunsStore } from "@/lib/store/agent-runs";
import { useChatStore } from "@/lib/store/chat";
import {
  PREVIEW_REFRESH_MIN_INTERVAL_MS,
  useGenerationStore,
} from "@/lib/store/generation";
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

describe("bridge · 通知 → invalidate（issue #17 清场后名册；preview-ready 空登为例外）", () => {
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

  it("preview-ready → 不失效任何域（#45：预览 REST 每次成功都发本事件，失效即自反馈循环）", async () => {
    const projects = observeActiveQuery(queryClient, queryKeys.projects.all);
    teardowns.push(projects.unsubscribe);
    await projects.waitForSettled();

    dispatchNotificationEvent(
      queryClient,
      notificationEvent("preview-ready", { projectId: "p1", url: "http://localhost:42659" }),
    );

    await new Promise((r) => setTimeout(r, 20));
    expect(projects.fetchCount()).toBe(1);
  });

  it("名册外 type（已删事件 stage-changed / task-updated 等）与坏数据：静默忽略，不抛不失效", async () => {
    const projects = observeActiveQuery(queryClient, queryKeys.projects.all);
    teardowns.push(projects.unsubscribe);
    await projects.waitForSettled();

    expect(() =>
      dispatchNotificationEvent(queryClient, notificationEvent("no-such-type", { projectId: "p1" })),
    ).not.toThrow();
    expect(() =>
      dispatchNotificationEvent(queryClient, notificationEvent("task-updated", { projectId: "p1" })),
    ).not.toThrow();
    expect(() => dispatchNotificationEvent(queryClient, { id: "x", data: "not json" })).not.toThrow();

    await new Promise((r) => setTimeout(r, 20));
    expect(projects.fetchCount()).toBe(1);
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

describe("bridge · preview-updated → 逐修改刷新（#49）", () => {
  let queryClient: QueryClient;

  beforeEach(() => {
    queryClient = new QueryClient();
    useGenerationStore.setState({ generations: {} });
  });

  afterEach(() => {
    queryClient.clear();
    vi.restoreAllMocks();
  });

  function previewUpdated(): SseEvent {
    return notificationEvent("preview-updated", { projectId: "p1" });
  }

  it("通知计预览纪元 +1（iframe 重挂信号，与 run-finish 共一套机制）；不失效任何 REST 域", async () => {
    const projects = observeActiveQuery(queryClient, queryKeys.projects.all);
    await projects.waitForSettled();

    dispatchNotificationEvent(queryClient, previewUpdated());

    expect(useGenerationStore.getState().generations["p1"]?.previewEpoch).toBe(1);
    // 内容在 iframe 背后的沙箱应用里、URL 不变——REST 域无可失效（重载走纪元非失效）
    await new Promise((r) => setTimeout(r, 20));
    expect(projects.fetchCount()).toBe(1);
  });

  it("节流：秒级最小间隔内的连续通知合并（纪元不重复计），出窗后再计", () => {
    // 不钉具体毫秒（测试决策）：时点全部由常量推导——间隔内（差 1s）合并、
    // 满最小间隔（边界值）出窗再计
    const interval = PREVIEW_REFRESH_MIN_INTERVAL_MS;
    const base = 10_000;
    const now = vi.spyOn(Date, "now");
    now.mockReturnValue(base);
    dispatchNotificationEvent(queryClient, previewUpdated());
    expect(useGenerationStore.getState().generations["p1"]?.previewEpoch).toBe(1);

    // 间隔内（差 1s）的连续通知：合并丢弃——连续通知不闪烁
    now.mockReturnValue(base + interval - 1000);
    dispatchNotificationEvent(queryClient, previewUpdated());
    dispatchNotificationEvent(queryClient, previewUpdated());
    expect(useGenerationStore.getState().generations["p1"]?.previewEpoch).toBe(1);

    // 出窗（满最小间隔，边界值）：下一次通知再计
    now.mockReturnValue(base + interval);
    dispatchNotificationEvent(queryClient, previewUpdated());
    expect(useGenerationStore.getState().generations["p1"]?.previewEpoch).toBe(2);
  });

  it("通知按 projectId 隔离，不串门", () => {
    dispatchNotificationEvent(queryClient, previewUpdated());
    dispatchNotificationEvent(
      queryClient,
      notificationEvent("preview-updated", { projectId: "p2" }),
    );

    expect(useGenerationStore.getState().generations["p1"]?.previewEpoch).toBe(1);
    expect(useGenerationStore.getState().generations["p2"]?.previewEpoch).toBe(1);
  });
});

describe("bridge · 智能体事件 → 运行注册表（agent-runs，顶栏 LIVE 锚）", () => {
  beforeEach(() => {
    useAgentRunsStore.setState({ runs: {}, order: [] });
  });

  /** 事件工厂：带信封 ts（起跑锚的时间源）。 */
  function agentEvent(
    type: string,
    payload: Record<string, unknown>,
    id = "run1:1",
    ts = "",
  ): SseEvent {
    return { id, data: JSON.stringify({ type, payload, ts }) };
  }

  it("run-start 建 run（startedAt = 信封 ts）；question → questioning；run-finish → finished；run-failed → error", () => {
    dispatchAgentEvent(
      agentQc,
      agentEvent("run-start", { projectId: "p1", runId: "run1", prompt: "实现表单", model: "m1" }, "run1:1", "2026-09-05T06:00:00Z"),
    );
    expect(useAgentRunsStore.getState().runs["run1"]).toMatchObject({
      projectId: "p1",
      status: "running",
      startedAt: Date.parse("2026-09-05T06:00:00Z"),
    });

    dispatchAgentEvent(
      agentQc,
      agentEvent("question-raised", { projectId: "p1", runId: "run1", summary: "选哪个配色" }, "run1:5"),
    );
    expect(useAgentRunsStore.getState().runs["run1"].status).toBe("questioning");

    dispatchAgentEvent(
      agentQc,
      agentEvent("run-finish", { projectId: "p1", runId: "run1", sessionId: "s1", finish: "end" }, "run1:9"),
    );
    expect(useAgentRunsStore.getState().runs["run1"].status).toBe("finished");

    dispatchAgentEvent(
      agentQc,
      agentEvent("run-start", { projectId: "p1", runId: "run2" }, "run2:1"),
    );
    dispatchAgentEvent(
      agentQc,
      agentEvent("run-failed", { projectId: "p1", runId: "run2" }, "run2:2"),
    );
    expect(useAgentRunsStore.getState().runs["run2"].status).toBe("error");
  });

  it("error 事件无 run-start 前置 → 补建 stub（起跑即死也可见——重放补发面）", () => {
    dispatchAgentEvent(
      agentQc,
      agentEvent(
        "error",
        {
          projectId: "p1",
          runId: "run1",
          message: "Failed to create model: Environment variable DEEPSEEK_API_KEY is required",
        },
        "run1:1",
      ),
    );

    expect(useAgentRunsStore.getState().runs["run1"]).toMatchObject({
      projectId: "p1",
      status: "error",
    });
  });

  it("run-start 重放（同 runId）不重开：LIVE 计时锚稳定", () => {
    dispatchAgentEvent(
      agentQc,
      agentEvent("run-start", { projectId: "p1", runId: "run1" }, "run1:1", "2026-09-05T06:00:00Z"),
    );
    dispatchAgentEvent(
      agentQc,
      agentEvent("run-start", { projectId: "p1", runId: "run1" }, "run1:1", "2026-09-05T06:00:00Z"),
    );

    expect(useAgentRunsStore.getState().runs["run1"].startedAt).toBe(Date.parse("2026-09-05T06:00:00Z"));
  });
});

describe("bridge · 智能体事件 → chat store（对话面，#19）", () => {
  beforeEach(() => {
    useAgentRunsStore.setState({ runs: {}, order: [] });
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
      { kind: "user", id: expect.any(String), text: "做个官网" },
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
      { kind: "user", id: expect.any(String), text: "我后台的地址是什么？" },
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
      { kind: "user", id: expect.any(String), text: "你好呀" },
      {
        kind: "agent",
        id: expect.any(String),
        text: "我在这里帮您把系统做出来：想改哪里、想加什么功能，直接告诉我。",
        label: "平台",
      },
    ]);
    expect(chat?.turnActive).toBe(false);
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

  it("PERMISSION 挂起不进对话（#83 起拆 permission-required 走工作消息确认卡）；非对话 run 的 text 不进对话", () => {
    dispatchAgentEvent(agentQc,
      agentEvent(
        "permission-required",
        { projectId: "p1", runId: "run1", sessionId: "coder-p1", summary: "rm -rf data", engineRef: "reply-1", data: { toolCalls: [{ id: "tc-1", name: "command", input: { command: "rm -rf data" } }] } },
        "run1:3",
      ),
    );
    dispatchAgentEvent(agentQc,
      agentEvent("text", { projectId: "p1", runId: "run1", sessionId: "coder-p1", data: { delta: "写代码" } }, "run1:4"),
    );

    expect(useChatStore.getState().chats["p1"]).toBeUndefined();
    // 权限挂起落工作消息确认卡（待答，engineRef 随卡作答）
    const work = useWorkMessageStore.getState().works["p1"];
    expect(work?.parts).toHaveLength(1);
    expect(work?.parts[0]).toMatchObject({
      kind: "permission",
      engineRef: "reply-1",
      summary: "rm -rf data",
      state: "pending",
    });
    expect(useAgentRunsStore.getState().runs["run1"].status).toBe("questioning");
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

  it("受理动作卡全轮序（#87）：意见 → acceptance-start 落卡（先于 run-start——服务端守卫后、受理动作前发）→ 追问挂起不落定 → 答复续跑收口（run-finish）落定——衔接更新 run 工作消息", () => {
    // 镜面服务端 MainAgentAppServiceTest 脚本化受理轮（追问分岔）：事件序 =
    // acceptance-start → run-start(agent=main) → 解说 → question-raised →
    // （作答）run-finish → run-start(agent=executor，更新 run 工作消息起锚）
    dispatchAgentEvent(agentQc, agentEvent("acceptance-start", { projectId: "p1", runId: "run1" }, "run1:1"));
    dispatchAgentEvent(agentQc, agentEvent("run-start", { projectId: "p1", runId: "run1", prompt: "把系统的主色调改成绿色", model: "m1", agent: "main" }, "run1:2"));
    dispatchAgentEvent(agentQc, agentEvent("text", { projectId: "p1", runId: "run1", sessionId: "main-p1", data: { delta: "我来处理这个需求" } }, "run1:3"));
    dispatchAgentEvent(agentQc,
      agentEvent(
        "question-raised",
        {
          projectId: "p1",
          runId: "run1",
          sessionId: "main-p1",
          summary: "想要哪种绿?",
          engineRef: "reply-7",
          data: {
            toolCalls: [{ id: "tc-1", name: "ask_user", input: {} }],
            questions: [{ header: "主色调", question: "想要哪种绿?", multiple: false, custom: true, options: [{ label: "薄荷绿" }] }],
          },
        },
        "run1:4",
      ),
    );
    let chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages).toEqual([
      { kind: "user", id: expect.any(String), text: "把系统的主色调改成绿色" },
      { kind: "acceptance", id: "run1:1", runId: "run1", settled: false },
      { kind: "agent", id: expect.any(String), text: "我来处理这个需求", runId: "run1" },
      expect.objectContaining({ kind: "question", runId: "run1" }),
    ]);

    // 答复续跑收口：run-finish 落定受理卡（受理完成）；随后更新 run 起跑（executor
    // 工作消息）——对话区连续衔接
    dispatchAgentEvent(agentQc, agentEvent("run-finish", { projectId: "p1", runId: "run1", sessionId: "main-p1", finish: "end" }, "run1:5"));
    dispatchAgentEvent(agentQc, agentEvent("run-start", { projectId: "p1", runId: "run9", prompt: "系统更新：把主色调改为绿", model: "m1", agent: "executor" }, "run9:1"));

    chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages[1]).toMatchObject({ kind: "acceptance", runId: "run1", settled: true });
    expect(useWorkMessageStore.getState().works["p1"]).toMatchObject({ runId: "run9" });
  });

  it("受理轮失败（error）：受理卡落定不死转（#87——炸轮有中断提示兜底，卡不悬转）", () => {
    dispatchAgentEvent(agentQc, agentEvent("acceptance-start", { projectId: "p1", runId: "run1" }, "run1:1"));
    dispatchAgentEvent(agentQc, agentEvent("run-start", { projectId: "p1", runId: "run1", prompt: "意见", model: "m1", agent: "main" }, "run1:2"));
    dispatchAgentEvent(agentQc, agentEvent("error", { projectId: "p1", runId: "run1", message: "模型调用失败" }, "run1:3"));

    const chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages[1]).toMatchObject({ kind: "acceptance", runId: "run1", settled: true });
    expect(chat?.messages.filter((m) => m.kind === "error")).toHaveLength(1);
  });
});

describe("bridge · 智能体事件 → generation store（生成面，#22）", () => {
  beforeEach(() => {
    useAgentRunsStore.setState({ runs: {}, order: [] });
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
    dispatchAgentEvent(agentQc, agentEvent("part-text", { projectId: "p1", runId: "run1", sessionId: "coder-p1", engine: "agentscope", text: "从中断处继续" }, "run1:10"));

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

  it("无中途闪错（#84 AC④）：失败终态前每一拍都无错误 UI——状态恒 running、对话面零错误气泡", () => {
    // 事件序列断言（与服务端脚本化用例同源）：run-start → 部件生长 →（中间错误
    // 与重试信号不进用户面事件流）→ run-failed 唯一失败终态。逐拍断言错误 UI
    // 永不短暂出现
    const base = { projectId: "p1", runId: "run1", sessionId: "coder-p1", engine: "agentscope" };
    const sequence: [string, Record<string, unknown>][] = [
      ["run-start", { ...base, prompt: "做系统", model: "m", agent: "executor" }],
      ["part-step", { ...base, step: 1 }],
      ["part-text", { ...base, text: "先搭骨架" }],
      ["part-action", { ...base, toolCallId: "tc-1", toolName: "write_file", state: "running", label: "编写【首页】" }],
    ];
    sequence.forEach(([type, payload], index) => {
      dispatchAgentEvent(agentQc, agentEvent(type, payload, `run1:${index + 1}`));
      // 每一拍：生成面已登记且无错误态、对话面零错误气泡（中途闪错 = 回归）
      expect(useGenerationStore.getState().generations["p1"]?.coderStatus).toBe("running");
      expect(useChatStore.getState().chats["p1"]?.messages ?? []).toHaveLength(0);
    });

    // 防御位：编码 run 的 error 事件（服务端投影失守的事件序异常）也不闪任何
    // 错误 UI——对话面不写失败气泡、生成面不写错误态、运行注册表不转 error
    dispatchAgentEvent(agentQc, agentEvent("error", { projectId: "p1", runId: "run1", message: "模型调用失败" }, "run1:8"));
    expect(useGenerationStore.getState().generations["p1"]?.coderStatus).toBe("running");
    expect(useChatStore.getState().chats["p1"]?.messages ?? []).toHaveLength(0);
    expect(useAgentRunsStore.getState().runs["run1"]?.status).toBe("running");

    // 唯一失败终态：run-failed 到达才转 error——工作消息定格、部件留驻
    dispatchAgentEvent(agentQc, agentEvent("run-failed", { projectId: "p1", runId: "run1" }, "run1:9"));
    expect(useGenerationStore.getState().generations["p1"]?.coderStatus).toBe("error");
    const work = useWorkMessageStore.getState().works["p1"];
    expect(work?.frozen).toBe(true);
    expect(work?.parts).toHaveLength(3);
  });

  it("run-finish 重放（同事件 id）不重复计预览纪元", () => {
    dispatchAgentEvent(agentQc, agentEvent("run-start", { projectId: "p1", runId: "run1", prompt: "开始做系统", agent: "executor" }, "run1:1"));
    const finish = agentEvent("run-finish", { projectId: "p1", runId: "run1", sessionId: "coder-p1", finish: "end" }, "run1:9");
    dispatchAgentEvent(agentQc, finish);
    dispatchAgentEvent(agentQc, finish); // 重放（通道带缓冲热流，重挂载重收近期事件）

    expect(useGenerationStore.getState().generations["p1"]?.previewEpoch).toBe(1);
  });

  it("超限终态：run-failed → 状态 error（恢复出口的唯一判定锚，run 失败为唯一失败终态）", () => {
    dispatchAgentEvent(agentQc, agentEvent("run-start", { projectId: "p1", runId: "run1", prompt: "开始做系统", agent: "executor" }, "run1:1"));

    dispatchAgentEvent(agentQc, agentEvent("run-failed", { projectId: "p1", runId: "run1" }, "run1:9"));
    expect(useGenerationStore.getState().generations["p1"]?.coderStatus).toBe("error");
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

    expect(useAgentRunsStore.getState().runs).toEqual({});
    expect(useChatStore.getState().chats).toEqual({});
    expect(useGenerationStore.getState().generations).toEqual({});
    expect(useWorkMessageStore.getState().works).toEqual({});
  });
});

describe("bridge · agent 流 → 工作消息 store（#81 parts 契约前端切新）", () => {
  beforeEach(() => {
    useAgentRunsStore.setState({ runs: {}, order: [] });
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
   */
  it("编码 run 全部件序：步骤分组 → 解说 → 动作 started/running/completed → 解说 → 收口定格", () => {
    const t0 = "2026-09-05T06:00:00.000Z";
    const at = (sec: number) => new Date(Date.parse(t0) + sec * 1000).toISOString();
    const base = { projectId: "p1", runId: "run1", sessionId: "coder-p1", engine: "agentscope" };

    dispatchAgentEvent(agentQc, agentEvent(
      "run-start",
      { ...base, prompt: "做系统", model: "deepseek-v4-pro", agent: "executor" },
      "run1:1",
      at(0),
    ));
    dispatchAgentEvent(agentQc, agentEvent("part-step", { ...base, step: 1 }, "run1:2", at(1)));
    dispatchAgentEvent(agentQc, agentEvent("part-text", { ...base, text: "正在编写订单管理页面。" }, "run1:3", at(2)));
    dispatchAgentEvent(agentQc, agentEvent(
      "part-action",
      { ...base, toolCallId: "tc-1", toolName: "write_file", state: "started", label: "编写【代码文件】" },
      "run1:4",
      at(3),
    ));
    dispatchAgentEvent(agentQc, agentEvent(
      "part-action",
      { ...base, toolCallId: "tc-1", toolName: "write_file", state: "running", label: "编写【订单管理】" },
      "run1:5",
      at(4),
    ));
    dispatchAgentEvent(agentQc, agentEvent(
      "part-action",
      { ...base, toolCallId: "tc-1", toolName: "write_file", state: "completed", label: "编写【订单管理】" },
      "run1:6",
      at(8),
    ));
    dispatchAgentEvent(agentQc, agentEvent("part-text", { ...base, text: "订单管理完成" }, "run1:7", at(9)));
    dispatchAgentEvent(agentQc, agentEvent(
      "run-finish",
      { ...base, finish: "end" },
      "run1:8",
      at(10),
    ));

    const work = useWorkMessageStore.getState().works["p1"];
    expect(work?.runId).toBe("run1");
    expect(work?.frozen).toBe(true); // 收口定格
    expect(work?.frozenAt).toBe(Date.parse(at(10)));
    expect(work?.parts).toEqual([
      { kind: "step", id: "run1:2", step: 1 },
      { kind: "text", id: "run1:3", text: "正在编写订单管理页面。" },
      {
        kind: "action",
        id: "run1:4",
        toolCallId: "tc-1",
        toolName: "write_file",
        state: "completed",
        label: "编写【订单管理】", // running 起具体对象，终态复述不闪换
        startedAt: Date.parse(at(3)),
        endedAt: Date.parse(at(8)), // 时长 5 秒（信封 ts 差）
      },
      { kind: "text", id: "run1:7", text: "订单管理完成" },
    ]);
  });

  /**
   * 自检播报（#85，镜面服务端 GenerationAppServiceTest·收口核验事件序断言）：
   * 收口判据核验「检查中 → 通过」位于真收口 run-finish 前——工作消息尾部一个
   * 自检部件原位换装，run-finish 定格后留驻（终值 = 探活结果，收尾卡统计行
   * 随 #88 消费）。
   */
  it("part-check：checking → passed 原位换装后随 run-finish 定格（无 engine 字段的平台侧部件）", () => {
    const t0 = "2026-09-05T06:00:00.000Z";
    const at = (sec: number) => new Date(Date.parse(t0) + sec * 1000).toISOString();
    const base = { projectId: "p1", runId: "run1", sessionId: "coder-p1" };

    dispatchAgentEvent(agentQc, agentEvent(
      "run-start",
      { ...base, prompt: "做系统", model: "m", engine: "agentscope", agent: "executor" },
      "run1:1",
      at(0),
    ));
    dispatchAgentEvent(agentQc, agentEvent("part-text", { ...base, engine: "agentscope", text: "骨架已就位" }, "run1:2", at(1)));
    dispatchAgentEvent(agentQc, agentEvent("part-check", { ...base, state: "checking" }, "run1:3", at(8)));
    dispatchAgentEvent(agentQc, agentEvent("part-check", { ...base, state: "passed" }, "run1:4", at(9)));
    dispatchAgentEvent(agentQc, agentEvent("run-finish", { ...base, engine: "agentscope", finish: "end" }, "run1:5", at(10)));

    const work = useWorkMessageStore.getState().works["p1"];
    expect(work?.frozen).toBe(true);
    expect(work?.parts).toEqual([
      { kind: "text", id: "run1:2", text: "骨架已就位" },
      {
        kind: "check",
        id: "run1:3", // 首见 checking 事件 id——原位换装不改键
        state: "passed",
        startedAt: Date.parse(at(8)),
        endedAt: Date.parse(at(9)),
      },
    ]);
  });

  /**
   * 自检终态面（镜面服务端「核验全程不过」断言）：末次核验未过 ❌ 先于
   * run-failed 到达——自检部件定格 failed，工作消息随 run-failed 定格。
   */
  it("part-check failed → run-failed：❌ 定格留驻，消息定格", () => {
    dispatchAgentEvent(agentQc, agentEvent(
      "run-start",
      { projectId: "p1", runId: "run1", sessionId: "coder-p1", prompt: "做系统", model: "m", agent: "executor" },
      "run1:1",
    ));
    dispatchAgentEvent(agentQc, agentEvent(
      "part-check",
      { projectId: "p1", runId: "run1", sessionId: "coder-p1", state: "checking" },
      "run1:2",
    ));
    dispatchAgentEvent(agentQc, agentEvent(
      "part-check",
      { projectId: "p1", runId: "run1", sessionId: "coder-p1", state: "failed" },
      "run1:3",
    ));
    dispatchAgentEvent(agentQc, agentEvent(
      "run-failed",
      { projectId: "p1", runId: "run1" },
      "run1:4",
    ));

    const work = useWorkMessageStore.getState().works["p1"];
    expect(work?.frozen).toBe(true);
    expect(work?.parts).toHaveLength(1);
    expect(work?.parts[0]).toMatchObject({ kind: "check", state: "failed" });
  });

  it("run-start 无 executor 配置键（main/一次性调用）不起工作消息；对话部件不误建", () => {
    dispatchAgentEvent(agentQc, agentEvent(
      "run-start",
      { projectId: "p1", runId: "rb", prompt: "追问", model: "m", agent: "main", sessionId: "main-p1" },
      "rb:1",
    ));
    dispatchAgentEvent(agentQc, agentEvent(
      "part-text",
      { projectId: "p1", runId: "rb", sessionId: "main-p1", engine: "agentscope", text: "对话解说段" },
      "rb:2",
    ));

    expect(useWorkMessageStore.getState().works["p1"]).toBeUndefined();
  });

  it("动作失败态（镜面服务端 given_tool_error_result…）：工具结果 error → part-action failed → 部件定格 failed 带时长", () => {
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
        { ...base, toolCallId: "tc-9", toolName: "command", state, label: "执行【安装依赖】" },
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
      startedAt: Date.parse(at(2)),
      endedAt: Date.parse(at(7)), // 失败也落时长（5 秒）
    });
  });

  it("permission-required → 确认卡部件（pending）→ permission-resolved 落定终态（镜面服务端 #83 拆分）", () => {
    const t0 = "2026-09-05T06:00:00.000Z";
    const at = (sec: number) => new Date(Date.parse(t0) + sec * 1000).toISOString();
    const base = { projectId: "p1", runId: "run1", sessionId: "coder-p1", engine: "agentscope" };

    dispatchAgentEvent(agentQc, agentEvent(
      "run-start",
      { ...base, prompt: "做系统", model: "m", agent: "executor" },
      "run1:1",
      at(0),
    ));
    dispatchAgentEvent(agentQc, agentEvent(
      "permission-required",
      { ...base, summary: "rm -rf /workspace/data", engineRef: "reply-9",
        data: { toolCalls: [{ id: "tc-9", name: "command", input: { command: "rm -rf /workspace/data" } }] } },
      "run1:5",
      at(5),
    ));

    const work = useWorkMessageStore.getState().works["p1"];
    expect(work?.parts).toEqual([
      {
        kind: "permission",
        id: "run1:5",
        engineRef: "reply-9",
        summary: "rm -rf /workspace/data",
        state: "pending",
        at: Date.parse(at(5)),
      },
    ]);
    expect(useAgentRunsStore.getState().runs["run1"].status).toBe("questioning"); // 等用户 ≠ 终态

    // 作答落定（批准）：确认卡转已批终态 + run 回 running（续跑中；终态仍归 run-finish/failed）
    dispatchAgentEvent(agentQc, agentEvent(
      "permission-resolved",
      { projectId: "p1", runId: "run1", engineRef: "reply-9", approved: true },
      "run1:9",
      at(12),
    ));
    expect(useWorkMessageStore.getState().works["p1"]?.parts[0])
      .toMatchObject({ kind: "permission", state: "approved" });
    expect(useAgentRunsStore.getState().runs["run1"].status).toBe("running");

    // 重放（断线重连先收缓冲）：required+resolved 双达确认卡不回退成待答（事件 id 去重 + 同值幂等）
    dispatchAgentEvent(agentQc, agentEvent(
      "permission-required",
      { ...base, summary: "rm -rf /workspace/data", engineRef: "reply-9",
        data: { toolCalls: [{ id: "tc-9", name: "command", input: {} }] } },
      "run1:5",
      at(5),
    ));
    dispatchAgentEvent(agentQc, agentEvent(
      "permission-resolved",
      { projectId: "p1", runId: "run1", engineRef: "reply-9", approved: true },
      "run1:9",
      at(12),
    ));
    expect(useWorkMessageStore.getState().works["p1"]?.parts).toHaveLength(1);
    expect(useWorkMessageStore.getState().works["p1"]?.parts[0])
      .toMatchObject({ kind: "permission", state: "approved" });
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
    dispatchAgentEvent(agentQc, agentEvent("part-text", { ...base, runId: "run1", text: "第一尝试解说" }, "run1:2"));
    dispatchAgentEvent(agentQc, agentEvent(
      "run-start",
      { ...base, runId: "run2", prompt: "做系统", model: "m", agent: "executor" },
      "run2:1",
    ));

    const work = useWorkMessageStore.getState().works["p1"];
    expect(work?.runId).toBe("run2");
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

    await vi.waitFor(() => expect(projects.fetchCount()).toBe(2));
    projects.unsubscribe();
    queryClient.clear();
  });
});

describe("bridge · run-finish 收口扩载 → 工作消息定格收尾卡（#88）", () => {
  beforeEach(() => {
    useAgentRunsStore.setState({ runs: {}, order: [] });
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
    summary: "修订了需求文档，并更新了系统",
    prdChanged: true,
    prdNote: "配送范围改为全国",
    systemChanged: true,
    systemNote: "下单页新增配送范围说明",
    files: [
      { path: "/src/App.jsx", added: 4, removed: 0 },
      { path: "/src/pages/Orders.jsx", added: 12, removed: 3 },
    ],
    durationMs: 183_420,
  };

  /**
   * 镜面服务端断言（IterationAppServiceTest·given_scripted_update_round_when_fix_
   * closes_then_run_finish_carries_authoritative_closing）：编码 run 真收口的
   * run-finish 携 closing——工作消息定格为收尾卡（权威事实入 store、过程部件
   * 退场），判定行不由前端推导。
   */
  it("编码 run 收口携 closing：收尾卡权威事实落 store、过程明细清空（收尾卡即凝聚物）", () => {
    const base = { projectId: "p1", runId: "run1", sessionId: "coder-p1", engine: "agentscope" };
    dispatchAgentEvent(agentQc, agentEvent(
      "run-start",
      { ...base, prompt: "系统修正：下单页加配送范围说明", model: "m", agent: "executor" },
      "run1:1",
    ));
    dispatchAgentEvent(agentQc, agentEvent("part-text", { ...base, text: "正在更新下单页" }, "run1:2"));
    dispatchAgentEvent(agentQc, agentEvent("part-check", { ...base, state: "passed" }, "run1:3"));
    dispatchAgentEvent(agentQc, agentEvent(
      "run-finish",
      { ...base, finish: "end", closing },
      "run1:4",
    ));

    const work = useWorkMessageStore.getState().works["p1"];
    expect(work?.frozen).toBe(true);
    expect(work?.closing).toEqual(closing);
    expect(work?.parts).toEqual([]);
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
    expect(useChatStore.getState().chats["p1"]?.messages.length).toBeGreaterThan(0);
  });

  it("closing 形状异常（非对象）：视同无收尾卡，工作消息保持定格流水不出坏卡", () => {
    const base = { projectId: "p1", runId: "run1", sessionId: "coder-p1", engine: "agentscope" };
    dispatchAgentEvent(agentQc, agentEvent(
      "run-start",
      { ...base, prompt: "做系统", model: "m", agent: "executor" },
      "run1:1",
    ));
    dispatchAgentEvent(agentQc, agentEvent("part-text", { ...base, text: "正在做" }, "run1:2"));
    dispatchAgentEvent(agentQc, agentEvent(
      "run-finish",
      { ...base, finish: "end", closing: "坏形状" },
      "run1:3",
    ));

    const work = useWorkMessageStore.getState().works["p1"];
    expect(work?.closing).toBeUndefined();
    expect(work?.parts).toHaveLength(1);
  });
});
