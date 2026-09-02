import { QueryClient, QueryObserver } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { useAgentStreamsStore } from "@/lib/store/agent-streams";
import { useChatStore } from "@/lib/store/chat";
import {
  PREVIEW_REFRESH_MIN_INTERVAL_MS,
  useGenerationStore,
} from "@/lib/store/generation";
import { useLiveStore } from "@/lib/store/live";
import { usePrdNoticesStore } from "@/lib/store/prd-notices";
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

  it("preview-ready → 不失效任何域（#45：预览 REST 每次成功都发本帧，失效即自反馈循环）", async () => {
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

describe("bridge · agent 流 → streams store", () => {
  beforeEach(() => {
    useAgentStreamsStore.setState({ runs: {}, order: [] });
  });

  function agentEvent(type: string, payload: Record<string, unknown>, id = "run1:1"): SseEvent {
    return { id, data: JSON.stringify({ type, payload, ts: "" }) };
  }

  it("run-start 建 run；text 透传入段（data 原样，id = SSE 事件 id）", () => {
    dispatchAgentEvent(agentQc, 
      agentEvent("run-start", { projectId: "p1", runId: "run1", prompt: "实现表单", model: "m1" }),
    );
    dispatchAgentEvent(agentQc, 
      agentEvent("text", { projectId: "p1", runId: "run1", data: { text: "最终文本" } }, "run1:2"),
    );

    const run = useAgentStreamsStore.getState().runs["run1"];
    expect(run).toMatchObject({ projectId: "p1", prompt: "实现表单", status: "running" });
    expect(run.segments).toEqual([{ kind: "text", id: "run1:2", data: { text: "最终文本" } }]);
  });

  it("role-assigned 先于 run-start（正本帧序）→ run-start 补 prompt 不丢 role 段", () => {
    // 创建即开场（正本帧序 role-assigned → run-start）：首帧补建 stub 后
    // run-start 必须把 prompt 补进去——否则用户的一句话描述永不出现。
    dispatchAgentEvent(agentQc, 
      agentEvent(
        "role-assigned",
        { projectId: "p1", runId: "run1", role: "BA", roleLabel: "需求分析师", engine: "agentscope" },
        "run1:1",
      ),
    );
    dispatchAgentEvent(agentQc, 
      agentEvent("run-start", { projectId: "p1", runId: "run1", prompt: "做个官网", model: "m1" }, "run1:2"),
    );

    const run = useAgentStreamsStore.getState().runs["run1"];
    expect(run).toMatchObject({ prompt: "做个官网", model: "m1" });
    expect(run.segments).toEqual([
      { kind: "role", id: "run1:1", role: "BA", roleLabel: "需求分析师", engine: "agentscope" },
    ]);
  });

  it("error 帧无 run-start 前置 → 补建 stub run + error 段（起跑即死也可见）", () => {
    // 真机事故口径：BA 起跑即死只发一帧 error（用户可能连上后才到）——run 必须被
    // 补建，错误才不是死寂。message 原样入段，由消费端负责用户口径。
    dispatchAgentEvent(agentQc, 
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

    const run = useAgentStreamsStore.getState().runs["run1"];
    expect(run).toMatchObject({ projectId: "p1", status: "error" });
    expect(run.segments).toEqual([
      {
        kind: "error",
        id: "run1:1",
        message: "Failed to create model: Environment variable DEEPSEEK_API_KEY is required",
      },
    ]);
  });

  it("question-raised → questioning + question 段；run-finish → finished + 终态段", () => {
    dispatchAgentEvent(agentQc, agentEvent("run-start", { projectId: "p1", runId: "run1" }));
    dispatchAgentEvent(agentQc,
      agentEvent(
        "question-raised",
        { projectId: "p1", runId: "run1", kind: "QUESTION", summary: "选哪个配色" },
        "run1:5",
      ),
    );
    expect(useAgentStreamsStore.getState().runs["run1"].status).toBe("questioning");

    dispatchAgentEvent(agentQc,
      agentEvent("run-finish", { projectId: "p1", runId: "run1", sessionId: "s1", finish: "end" }, "run1:9"),
    );
    const run = useAgentStreamsStore.getState().runs["run1"];
    expect(run.status).toBe("finished");
    expect(run.segments).toEqual([
      { kind: "question", id: "run1:5", questionKind: "QUESTION", summary: "选哪个配色" },
      { kind: "finish", id: "run1:9", finish: "end" },
    ]);
  });

  it("run-created → markRunCreated（会话标识挂 run）", () => {
    dispatchAgentEvent(agentQc, agentEvent("run-start", { projectId: "p1", runId: "run1" }));
    dispatchAgentEvent(agentQc, 
      agentEvent("run-created", { projectId: "p1", runId: "run1", sessionId: "s1" }, "run1:2"),
    );
    expect(useAgentStreamsStore.getState().runs["run1"].sessionId).toBe("s1");
  });

  it("未知透传 type → passthrough 段（开放集合不丢事件）；坏数据静默忽略", () => {
    dispatchAgentEvent(agentQc, 
      agentEvent("part-updated", { projectId: "p1", runId: "run1", data: { x: 1 } }, "run1:3"),
    );
    expect(() => dispatchAgentEvent(agentQc, { id: "y", data: "not json" })).not.toThrow();

    const run = useAgentStreamsStore.getState().runs["run1"];
    expect(run.segments).toEqual([
      { kind: "passthrough", id: "run1:3", type: "part-updated", data: { x: 1 } },
    ]);
  });
});

describe("bridge · agent 流 → chat store（指令区对话面，#19）", () => {
  beforeEach(() => {
    useAgentStreamsStore.setState({ runs: {}, order: [] });
    useChatStore.setState({ chats: {} });
  });

  function agentEvent(type: string, payload: Record<string, unknown>, id = "run1:1"): SseEvent {
    return { id, data: JSON.stringify({ type, payload, ts: "" }) };
  }

  it("role-assigned(BA) → run-start 落用户气泡；text(data.delta) 累积带标签气泡", () => {
    dispatchAgentEvent(agentQc,
      agentEvent(
        "role-assigned",
        { projectId: "p1", runId: "run1", role: "BA", roleLabel: "需求分析师", engine: "agentscope" },
        "run1:1",
      ),
    );
    dispatchAgentEvent(agentQc,
      agentEvent("run-start", { projectId: "p1", runId: "run1", prompt: "做个官网", model: "m1" }, "run1:2"),
    );
    dispatchAgentEvent(agentQc,
      agentEvent("text", { projectId: "p1", runId: "run1", sessionId: "ba-p1", data: { delta: "初步理解" } }, "run1:3"),
    );

    const chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages).toEqual([
      { kind: "user", id: expect.any(String), text: "做个官网" },
      { kind: "agent", id: expect.any(String), text: "初步理解", label: "需求分析师", runId: "run1" },
    ]);
  });

  it("role-assigned(ASSISTANT) → 助理轮进对话（#47 咨询分支）：assist- 会话 text 累积、标签随帧", () => {
    dispatchAgentEvent(agentQc,
      agentEvent(
        "role-assigned",
        { projectId: "p1", runId: "run1", role: "ASSISTANT", roleLabel: "项目助理", engine: "agentscope" },
        "run1:1",
      ),
    );
    dispatchAgentEvent(agentQc,
      agentEvent("run-start", { projectId: "p1", runId: "run1", prompt: "我后台的地址是什么？", model: "m1" }, "run1:2"),
    );
    dispatchAgentEvent(agentQc,
      agentEvent("text", { projectId: "p1", runId: "run1", sessionId: "assist-p1", data: { delta: "访问地址是 " } }, "run1:3"),
    );
    dispatchAgentEvent(agentQc,
      agentEvent("text", { projectId: "p1", runId: "run1", sessionId: "assist-p1", data: { delta: "http://localhost:32168/" } }, "run1:4"),
    );
    dispatchAgentEvent(agentQc,
      agentEvent("run-finish", { projectId: "p1", runId: "run1", sessionId: "assist-p1", finish: "end" }, "run1:5"),
    );

    const chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages).toEqual([
      { kind: "user", id: expect.any(String), text: "我后台的地址是什么？" },
      {
        kind: "agent",
        id: expect.any(String),
        text: "访问地址是 http://localhost:32168/",
        label: "项目助理",
        runId: "run1",
      },
    ]);
    expect(chat?.turnActive).toBe(false);
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
    dispatchAgentEvent(agentQc, { ...frame, id: "run1:1" }); // 重放同帧

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
    dispatchAgentEvent(agentQc, 
      agentEvent(
        "question-raised",
        {
          projectId: "p1",
          runId: "run1",
          sessionId: "ba-p1",
          kind: "QUESTION",
          summary: "面向谁?",
          engineRef: "reply-7",
          data: {
            type: "question",
            toolCalls: [{ id: "tc-1", name: "ask_user", input: {} }],
            questions: [{ header: "目标用户", question: "面向谁?", multiple: false, custom: true, options: [{ label: "企业客户" }] }],
          },
        },
        "run1:5",
      ),
    );

    const chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages).toHaveLength(1);
    expect(chat?.messages[0]).toMatchObject({
      kind: "question",
      runId: "run1",
      engineRef: "reply-7",
      options: ["企业客户"],
    });
  });

  it("PERMISSION 挂起不成卡；非 BA 会话的 text 不进对话", () => {
    dispatchAgentEvent(agentQc, 
      agentEvent(
        "question-raised",
        { projectId: "p1", runId: "run1", sessionId: "s1", kind: "PERMISSION", summary: "write_file", engineRef: "reply-1", data: {} },
        "run1:3",
      ),
    );
    dispatchAgentEvent(agentQc, 
      agentEvent("text", { projectId: "p1", runId: "run1", sessionId: "coder-p1", data: { delta: "写代码" } }, "run1:4"),
    );

    expect(useChatStore.getState().chats["p1"]).toBeUndefined();
  });

  it("error / run-finish（BA 会话）→ 收轮 + 中断提示", () => {
    dispatchAgentEvent(agentQc, 
      agentEvent("role-assigned", { projectId: "p1", runId: "run1", role: "BA", roleLabel: "需求分析师", engine: "agentscope" }, "run1:1"),
    );
    dispatchAgentEvent(agentQc, agentEvent("run-start", { projectId: "p1", runId: "run1", prompt: "需求" }, "run1:2"));
    dispatchAgentEvent(agentQc, 
      agentEvent("error", { projectId: "p1", runId: "run1", message: "模型调用失败" }, "run1:3"),
    );
    dispatchAgentEvent(agentQc, 
      agentEvent("run-finish", { projectId: "p1", runId: "run1", sessionId: "ba-p1", finish: "end" }, "run1:4"),
    );

    const chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages.filter((m) => m.kind === "error")).toHaveLength(1);
    expect(chat?.turnActive).toBe(false);
  });
});

describe("bridge · fix-unchanged → 指令区「未动系统」通告（#46）", () => {
  beforeEach(() => {
    useAgentStreamsStore.setState({ runs: {}, order: [] });
    useChatStore.setState({ chats: {} });
    useGenerationStore.setState({ generations: {} });
  });

  function agentEvent(type: string, payload: Record<string, unknown>, id = "run1:1"): SseEvent {
    return { id, data: JSON.stringify({ type, payload, ts: "" }) };
  }

  it("编码 run 的 fix-unchanged → chat store 落通告条（reason 原文）；重放按事件 id 只收一次", () => {
    dispatchAgentEvent(agentQc,
      agentEvent(
        "role-assigned",
        { projectId: "p1", runId: "run1", role: "CODER", roleLabel: "编码智能体", engine: "agentscope" },
        "run1:1",
      ),
    );
    dispatchAgentEvent(
      agentQc,
      agentEvent(
        "fix-unchanged",
        { projectId: "p1", runId: "run1", reason: "纯文档性修订，系统现状已满足" },
        "run1:9",
      ),
    );
    dispatchAgentEvent(
      agentQc,
      agentEvent(
        "fix-unchanged",
        { projectId: "p1", runId: "run1", reason: "纯文档性修订，系统现状已满足" },
        "run1:9",
      ),
    );

    const chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages).toEqual([
      { kind: "notice", id: expect.any(String), text: "纯文档性修订，系统现状已满足" },
    ]);
  });

  it("无 CODER 登记的 runId → 忽略（帧序异常防御位），不动对话面", () => {
    dispatchAgentEvent(
      agentQc,
      agentEvent("fix-unchanged", { projectId: "p1", runId: "ghost", reason: "杂音" }, "ghost:1"),
    );

    expect(useChatStore.getState().chats["p1"]).toBeUndefined();
  });
});

describe("bridge · agent 流 → generation store（生成面，#22）", () => {
  beforeEach(() => {
    useAgentStreamsStore.setState({ runs: {}, order: [] });
    useChatStore.setState({ chats: {} });
    useGenerationStore.setState({ generations: {} });
  });

  function agentEvent(type: string, payload: Record<string, unknown>, id = "run1:1"): SseEvent {
    return { id, data: JSON.stringify({ type, payload, ts: "" }) };
  }

  it("编码 run 全帧序：登记 → running → error → retrying → 重试收口 → 预览纪元 +1", () => {
    dispatchAgentEvent(agentQc, 
      agentEvent(
        "role-assigned",
        { projectId: "p1", runId: "run1", role: "CODER", roleLabel: "编码智能体", engine: "agentscope" },
        "run1:1",
      ),
    );
    dispatchAgentEvent(agentQc, agentEvent("run-start", { projectId: "p1", runId: "run1", prompt: "开始做系统" }, "run1:2"));
    expect(useGenerationStore.getState().generations["p1"]?.coderStatus).toBe("running");

    // 尝试失败（error）→ 自动重试帧（话术承载）→ 状态回 retrying（同一场生成在途）
    dispatchAgentEvent(agentQc, agentEvent("error", { projectId: "p1", runId: "run1", message: "中断" }, "run1:8"));
    expect(useGenerationStore.getState().generations["p1"]?.coderStatus).toBe("error");
    dispatchAgentEvent(agentQc, 
      agentEvent("run-retrying", { projectId: "p1", runId: "run1", attempt: 2, message: "遇到问题，正在重试" }, "run1:9"),
    );
    expect(useGenerationStore.getState().generations["p1"]?.coderStatus).toBe("retrying");
    // 话术正本随帧下发（UI 呈现取帧内 message，不在前端再写一份）
    expect(useGenerationStore.getState().generations["p1"]?.retryMessage).toBe("遇到问题，正在重试");
    // 顶栏 LIVE 判定锚：retrying 把 run 状态拉回 in-flight
    expect(useAgentStreamsStore.getState().runs["run1"].status).toBe("running");

    // 重试尝试（新 runId，同样登记）收口 → finished + 预览纪元 +1（重挂信号）
    dispatchAgentEvent(agentQc, 
      agentEvent("role-assigned", { projectId: "p1", runId: "run2", role: "CODER", roleLabel: "编码智能体", engine: "agentscope" }, "run2:1"),
    );
    dispatchAgentEvent(agentQc, agentEvent("run-start", { projectId: "p1", runId: "run2", prompt: "继续完成" }, "run2:2"));
    dispatchAgentEvent(agentQc, agentEvent("run-finish", { projectId: "p1", runId: "run2", sessionId: "coder-p1", finish: "end" }, "run2:9"));

    const generation = useGenerationStore.getState().generations["p1"];
    expect(generation?.coderStatus).toBe("finished");
    expect(generation?.previewEpoch).toBe(1);

    // 编码帧不进对话面（指令区只收 BA）
    expect(useChatStore.getState().chats["p1"]).toBeUndefined();
  });

  it("run-finish 重放（同事件 id）不重复计预览纪元", () => {
    dispatchAgentEvent(agentQc, 
      agentEvent("role-assigned", { projectId: "p1", runId: "run1", role: "CODER", roleLabel: "编码智能体", engine: "agentscope" }, "run1:1"),
    );
    const finish = agentEvent("run-finish", { projectId: "p1", runId: "run1", sessionId: "coder-p1", finish: "end" }, "run1:9");
    dispatchAgentEvent(agentQc, finish);
    dispatchAgentEvent(agentQc, finish); // 重放（通道带缓冲热流，重挂载重收近期帧）

    expect(useGenerationStore.getState().generations["p1"]?.previewEpoch).toBe(1);
  });

  it("BA 帧与未登记 run 的帧不进生成面", () => {
    dispatchAgentEvent(agentQc, 
      agentEvent("role-assigned", { projectId: "p1", runId: "run1", role: "BA", roleLabel: "需求分析师", engine: "agentscope" }, "run1:1"),
    );
    dispatchAgentEvent(agentQc, agentEvent("run-finish", { projectId: "p1", runId: "run1", sessionId: "ba-p1", finish: "end" }, "run1:9"));
    // 未登记（无 role-assigned CODER 前置）的 run 帧：判定锚缺失，不惊动生成面
    dispatchAgentEvent(agentQc, agentEvent("run-finish", { projectId: "p1", runId: "runX", sessionId: "coder-p1", finish: "end" }, "runX:9"));

    expect(useGenerationStore.getState().generations["p1"]).toBeUndefined();
  });
});

describe("bridge · agent 流 → live store（直播面，#23）", () => {
  beforeEach(() => {
    useLiveStore.setState({ lives: {} });
  });

  function agentEvent(type: string, payload: Record<string, unknown>, id: string): SseEvent {
    return { id, data: JSON.stringify({ type, payload, ts: "" }) };
  }

  it("live-* 帧按 run 落直播段；engine 透传帧不进直播面（不耦合引擎格式）", () => {
    dispatchAgentEvent(agentQc, agentEvent(
      "live-step",
      { projectId: "p1", runId: "run1", sessionId: "coder-p1", engine: "agentscope", step: 1 },
      "run1:2",
    ));
    dispatchAgentEvent(agentQc, agentEvent(
      "live-text",
      { projectId: "p1", runId: "run1", sessionId: "coder-p1", engine: "agentscope", text: "正在准备演示数据。" },
      "run1:3",
    ));
    dispatchAgentEvent(agentQc, agentEvent(
      "live-action",
      { projectId: "p1", runId: "run1", sessionId: "coder-p1", engine: "agentscope", action: "正在编写【订单管理】" },
      "run1:4",
    ));
    // 引擎透传帧同流到达：直播面不收（text 增量是引擎格式，直播段已由服务端成型）
    dispatchAgentEvent(agentQc, agentEvent(
      "text",
      { projectId: "p1", runId: "run1", data: { delta: "raw", blockId: "b1" } },
      "run1:5",
    ));

    expect(useLiveStore.getState().lives["p1"]).toEqual({
      runId: "run1",
      segments: [
        { kind: "step", id: "run1:2", step: 1 },
        { kind: "text", id: "run1:3", text: "正在准备演示数据。" },
        { kind: "action", id: "run1:4", action: "正在编写【订单管理】" },
      ],
    });
  });
});

describe("bridge · 编码 run 收口 → 项目域失效（#22，失效归桥）", () => {
  it("coder run-finish → projects 域 active query 重拉（generated_at 详情事实）", async () => {
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
    // 未登记的 run（无 role-assigned CODER 前置）：不失效
    expect(projects.fetchCount()).toBe(1);

    dispatchAgentEvent(
      queryClient,
      {
        id: "run1:0",
        data: JSON.stringify({
          type: "role-assigned",
          payload: { projectId: "p1", runId: "run1", role: "CODER", roleLabel: "编码智能体", engine: "agentscope" },
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
