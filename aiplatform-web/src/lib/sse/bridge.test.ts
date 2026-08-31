import { QueryClient, QueryObserver } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { useAgentStreamsStore } from "@/lib/store/agent-streams";
import { useChatStore } from "@/lib/store/chat";
import { usePrdNoticesStore } from "@/lib/store/prd-notices";
import { queryKeys } from "@/lib/api/keys";

import { dispatchAgentEvent, dispatchNotificationEvent } from "./bridge";
import type { SseEvent } from "./connection";

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

describe("bridge · 通知 → invalidate（issue #17 清场后名册：全部失效 projects 域）", () => {
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
    "preview-ready",
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

describe("bridge · agent 流 → streams store", () => {
  beforeEach(() => {
    useAgentStreamsStore.setState({ runs: {}, order: [] });
  });

  function agentEvent(type: string, payload: Record<string, unknown>, id = "run1:1"): SseEvent {
    return { id, data: JSON.stringify({ type, payload, ts: "" }) };
  }

  it("task-start 建 run；text 透传入段（data 原样，id = SSE 事件 id）", () => {
    dispatchAgentEvent(
      agentEvent("task-start", { projectId: "p1", runId: "run1", prompt: "实现表单", model: "m1" }),
    );
    dispatchAgentEvent(
      agentEvent("text", { projectId: "p1", runId: "run1", data: { text: "最终文本" } }, "run1:2"),
    );

    const run = useAgentStreamsStore.getState().runs["run1"];
    expect(run).toMatchObject({ projectId: "p1", prompt: "实现表单", status: "running" });
    expect(run.segments).toEqual([{ kind: "text", id: "run1:2", data: { text: "最终文本" } }]);
  });

  it("role-assigned 先于 task-start（正本帧序）→ task-start 补 prompt 不丢 role 段", () => {
    // 创建即开场（正本帧序 role-assigned → task-start）：首帧补建 stub 后
    // task-start 必须把 prompt 补进去——否则用户的一句话描述永不出现。
    dispatchAgentEvent(
      agentEvent(
        "role-assigned",
        { projectId: "p1", runId: "run1", role: "BA", roleLabel: "需求分析师", engine: "agentscope" },
        "run1:1",
      ),
    );
    dispatchAgentEvent(
      agentEvent("task-start", { projectId: "p1", runId: "run1", prompt: "做个官网", model: "m1" }, "run1:2"),
    );

    const run = useAgentStreamsStore.getState().runs["run1"];
    expect(run).toMatchObject({ prompt: "做个官网", model: "m1" });
    expect(run.segments).toEqual([
      { kind: "role", id: "run1:1", role: "BA", roleLabel: "需求分析师", engine: "agentscope" },
    ]);
  });

  it("error 帧无 task-start 前置 → 补建 stub run + error 段（起跑即死也可见）", () => {
    // 真机事故口径：BA 起跑即死只发一帧 error（用户可能连上后才到）——run 必须被
    // 补建，错误才不是死寂。message 原样入段，由消费端负责用户口径。
    dispatchAgentEvent(
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

  it("wait-raised → waiting + wait 段；task-finish → finished + 终态段", () => {
    dispatchAgentEvent(agentEvent("task-start", { projectId: "p1", runId: "run1" }));
    dispatchAgentEvent(
      agentEvent(
        "wait-raised",
        { projectId: "p1", runId: "run1", kind: "QUESTION", summary: "选哪个配色" },
        "run1:5",
      ),
    );
    expect(useAgentStreamsStore.getState().runs["run1"].status).toBe("waiting");

    dispatchAgentEvent(
      agentEvent("task-finish", { projectId: "p1", runId: "run1", sessionId: "s1", finish: "end" }, "run1:9"),
    );
    const run = useAgentStreamsStore.getState().runs["run1"];
    expect(run.status).toBe("finished");
    expect(run.segments).toEqual([
      { kind: "wait", id: "run1:5", waitKind: "QUESTION", summary: "选哪个配色" },
      { kind: "finish", id: "run1:9", finish: "end" },
    ]);
  });

  it("session-created → markSession（会话标识挂 run）", () => {
    dispatchAgentEvent(agentEvent("task-start", { projectId: "p1", runId: "run1" }));
    dispatchAgentEvent(
      agentEvent("session-created", { projectId: "p1", runId: "run1", sessionId: "s1" }, "run1:2"),
    );
    expect(useAgentStreamsStore.getState().runs["run1"].sessionId).toBe("s1");
  });

  it("未知透传 type → passthrough 段（开放集合不丢事件）；坏数据静默忽略", () => {
    dispatchAgentEvent(
      agentEvent("part-updated", { projectId: "p1", runId: "run1", data: { x: 1 } }, "run1:3"),
    );
    expect(() => dispatchAgentEvent({ id: "y", data: "not json" })).not.toThrow();

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

  it("role-assigned(BA) → task-start 落用户气泡；text(data.delta) 累积 BA 气泡", () => {
    dispatchAgentEvent(
      agentEvent(
        "role-assigned",
        { projectId: "p1", runId: "run1", role: "BA", roleLabel: "需求分析师", engine: "agentscope" },
        "run1:1",
      ),
    );
    dispatchAgentEvent(
      agentEvent("task-start", { projectId: "p1", runId: "run1", prompt: "做个官网", model: "m1" }, "run1:2"),
    );
    dispatchAgentEvent(
      agentEvent("text", { projectId: "p1", runId: "run1", sessionId: "ba-p1", data: { delta: "初步理解" } }, "run1:3"),
    );

    const chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages.map((m) => [m.kind, m.kind === "question" ? m.question : m.text])).toEqual([
      ["user", "做个官网"],
      ["ba", "初步理解"],
    ]);
  });

  it("wait-raised(QUESTION + data.questions) → 问答卡（engineRef 随卡，作答回传面）", () => {
    dispatchAgentEvent(
      agentEvent(
        "wait-raised",
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
    dispatchAgentEvent(
      agentEvent(
        "wait-raised",
        { projectId: "p1", runId: "run1", sessionId: "s1", kind: "PERMISSION", summary: "write_file", engineRef: "reply-1", data: {} },
        "run1:3",
      ),
    );
    dispatchAgentEvent(
      agentEvent("text", { projectId: "p1", runId: "run1", sessionId: "coder-p1", data: { delta: "写代码" } }, "run1:4"),
    );

    expect(useChatStore.getState().chats["p1"]).toBeUndefined();
  });

  it("error / task-finish（BA 会话）→ 收轮 + 中断提示", () => {
    dispatchAgentEvent(
      agentEvent("role-assigned", { projectId: "p1", runId: "run1", role: "BA", roleLabel: "需求分析师", engine: "agentscope" }, "run1:1"),
    );
    dispatchAgentEvent(agentEvent("task-start", { projectId: "p1", runId: "run1", prompt: "需求" }, "run1:2"));
    dispatchAgentEvent(
      agentEvent("error", { projectId: "p1", runId: "run1", message: "模型调用失败" }, "run1:3"),
    );
    dispatchAgentEvent(
      agentEvent("task-finish", { projectId: "p1", runId: "run1", sessionId: "ba-p1", finish: "end" }, "run1:4"),
    );

    const chat = useChatStore.getState().chats["p1"];
    expect(chat?.messages.filter((m) => m.kind === "error")).toHaveLength(1);
    expect(chat?.turnActive).toBe(false);
  });
});
