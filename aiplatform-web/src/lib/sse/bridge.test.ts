import { QueryClient, QueryObserver } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { useAgentStreamsStore } from "@/lib/store/agent-streams";
import { useProjectNoticesStore } from "@/lib/store/project-notices";
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

describe("bridge · 通知 → invalidate", () => {
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

  it("workspace-created → projects 域 active query 重拉", async () => {
    const projects = observeActiveQuery(queryClient, queryKeys.projects.all);
    teardowns.push(projects.unsubscribe);
    await projects.waitForSettled();

    dispatchNotificationEvent(
      queryClient,
      notificationEvent("workspace-created", {
        projectId: "p1",
        projectName: "官网 demo",
        container: "c1",
        projectType: "WEBSITE",
        engine: "opencode",
      }),
    );

    await vi.waitFor(() => expect(projects.fetchCount()).toBe(2));
  });

  it("task-updated → tasks 与 projects 双域失效", async () => {
    const tasks = observeActiveQuery(queryClient, queryKeys.tasks.all);
    const projects = observeActiveQuery(queryClient, queryKeys.projects.all);
    teardowns.push(tasks.unsubscribe, projects.unsubscribe);
    await tasks.waitForSettled();
    await projects.waitForSettled();

    dispatchNotificationEvent(
      queryClient,
      notificationEvent("task-updated", { projectId: "p1", taskId: "t1", status: "SUBMITTED" }),
    );

    await vi.waitFor(() => {
      expect(tasks.fetchCount()).toBe(2);
      expect(projects.fetchCount()).toBe(2);
    });
  });

  it("document-updated → documents 与 projects 双域失效（PRD 重拉 + 门就绪连带，#58）", async () => {
    const documents = observeActiveQuery(queryClient, queryKeys.documents.all);
    const projects = observeActiveQuery(queryClient, queryKeys.projects.all);
    teardowns.push(documents.unsubscribe, projects.unsubscribe);
    await documents.waitForSettled();
    await projects.waitForSettled();

    dispatchNotificationEvent(
      queryClient,
      notificationEvent("document-updated", { projectId: "p1", documentType: "PRD" }),
    );

    await vi.waitFor(() => {
      expect(documents.fetchCount()).toBe(2);
      expect(projects.fetchCount()).toBe(2);
    });
  });

  it("preview-ready → tasks 域连带失效（OPC 卡片/详情的 previewUrl 点亮链，#22）", async () => {
    const tasks = observeActiveQuery(queryClient, queryKeys.tasks.all);
    const projects = observeActiveQuery(queryClient, queryKeys.projects.all);
    teardowns.push(tasks.unsubscribe, projects.unsubscribe);
    await tasks.waitForSettled();
    await projects.waitForSettled();

    dispatchNotificationEvent(
      queryClient,
      notificationEvent("preview-ready", { projectId: "p1", url: "http://p1.localhost:4040" }),
    );

    await vi.waitFor(() => {
      expect(tasks.fetchCount()).toBe(2);
      expect(projects.fetchCount()).toBe(2);
    });
  });

  it("stage-changed / task-updated → todos 域一并失效（待办为计算式投影，重拉而非增量）", async () => {
    const todos = observeActiveQuery(queryClient, queryKeys.todos.all);
    teardowns.push(todos.unsubscribe);
    await todos.waitForSettled();

    dispatchNotificationEvent(
      queryClient,
      notificationEvent("stage-changed", { projectId: "p1", stage: "DEV", stageLabel: "开发" }),
    );
    dispatchNotificationEvent(
      queryClient,
      notificationEvent("task-updated", { projectId: "p1", taskId: "t1", status: "SUBMITTED" }),
    );

    await vi.waitFor(() => expect(todos.fetchCount()).toBe(3));
  });

  it("名册外 type 与坏数据：静默忽略，不抛不失效", async () => {
    const projects = observeActiveQuery(queryClient, queryKeys.projects.all);
    teardowns.push(projects.unsubscribe);
    await projects.waitForSettled();

    expect(() =>
      dispatchNotificationEvent(queryClient, notificationEvent("no-such-type", { projectId: "p1" })),
    ).not.toThrow();
    expect(() => dispatchNotificationEvent(queryClient, { id: "x", data: "not json" })).not.toThrow();

    await new Promise((r) => setTimeout(r, 20));
    expect(projects.fetchCount()).toBe(1);
  });

  describe("载荷展示白名单 → project-notices store", () => {
    beforeEach(() => {
      useProjectNoticesStore.setState({ notices: {}, order: [] });
    });

  it("stage-changed（rejected + reason）→ 写入驳回理由；失效照常发生", async () => {
    const projects = observeActiveQuery(queryClient, queryKeys.projects.all);
    teardowns.push(projects.unsubscribe);
    await projects.waitForSettled();

    dispatchNotificationEvent(
      queryClient,
      notificationEvent("stage-changed", {
        projectId: "p1",
        stage: "DEMO",
        stageLabel: "原型",
        rejected: true,
        reason: "配色太深",
      }),
    );

    expect(useProjectNoticesStore.getState().notices["p1"].rejection).toEqual({
      stageLabel: "原型",
      reason: "配色太深",
    });
    await vi.waitFor(() => expect(projects.fetchCount()).toBe(2));
  });

  it("stage-changed（approved）→ 清除该项目的驳回理由", () => {
    useProjectNoticesStore.getState().setRejection("p1", { stageLabel: "原型", reason: "旧意见" });
    dispatchNotificationEvent(
      queryClient,
      notificationEvent("stage-changed", {
        projectId: "p1",
        stage: "DEMO",
        stageLabel: "原型",
        approved: true,
      }),
    );
    expect(useProjectNoticesStore.getState().notices["p1"]?.rejection).toBeUndefined();
  });

  it("stage-changed（rejected 但缺 reason / 纯推进）→ 不写 store", () => {
    dispatchNotificationEvent(
      queryClient,
      notificationEvent("stage-changed", {
        projectId: "p1",
        stage: "DEV",
        stageLabel: "开发",
        rejected: true,
      }),
    );
    dispatchNotificationEvent(
      queryClient,
      notificationEvent("stage-changed", { projectId: "p2", stage: "DEV", stageLabel: "开发" }),
    );
    expect(useProjectNoticesStore.getState().notices).toEqual({});
  });

  it("preview-ready → 置「有更新」位（预览面板手动刷新的信号源）", () => {
    dispatchNotificationEvent(
      queryClient,
      notificationEvent("preview-ready", { projectId: "p1", url: "http://p1.localhost:4040" }),
    );
    expect(useProjectNoticesStore.getState().notices["p1"].previewUpdate).toBe(true);
  });

  it("document-updated → 置「PRD 有更新」位（对话区提示胶囊的信号源，#54）", () => {
    dispatchNotificationEvent(
      queryClient,
      notificationEvent("document-updated", { projectId: "p1", documentType: "PRD" }),
    );
    expect(useProjectNoticesStore.getState().notices["p1"].documentUpdate).toBe(true);
  });

  it("白名单外事件（workspace-created 等）→ 不写 store", () => {
    dispatchNotificationEvent(
      queryClient,
      notificationEvent("workspace-created", {
        projectId: "p1",
        projectName: "官网",
        container: "c1",
        projectType: "WEBSITE",
        engine: "opencode",
      }),
    );
    expect(useProjectNoticesStore.getState().notices).toEqual({});
  });
  });
});

describe("bridge · agent 流 → streams store", () => {
  let queryClient: QueryClient;

  beforeEach(() => {
    queryClient = new QueryClient();
    queryClient.setDefaultOptions({ queries: { retry: false } });
    useAgentStreamsStore.setState({ runs: {}, order: [] });
  });

  afterEach(() => {
    queryClient.clear();
  });

  function agentEvent(type: string, payload: Record<string, unknown>, id = "run1:1"): SseEvent {
    return { id, data: JSON.stringify({ type, payload, ts: "" }) };
  }

  it("task-start 建 run；text 透传入段（data 原样，id = SSE 事件 id）", () => {
    dispatchAgentEvent(
      queryClient,
      agentEvent("task-start", { projectId: "p1", runId: "run1", prompt: "实现表单", model: "m1" }),
    );
    dispatchAgentEvent(
      queryClient,
      agentEvent("text", { projectId: "p1", runId: "run1", data: { text: "最终文本" } }, "run1:2"),
    );

    const run = useAgentStreamsStore.getState().runs["run1"];
    expect(run).toMatchObject({ projectId: "p1", prompt: "实现表单", status: "running" });
    expect(run.segments).toEqual([{ kind: "text", id: "run1:2", data: { text: "最终文本" } }]);
  });

  it("role-assigned 先于 task-start（正本帧序）→ task-start 补 prompt 不丢 role 段", () => {
    // 创建即开场（#40）正本帧序 role-assigned → task-start：首帧补建 stub 后
    // task-start 必须把 prompt 补进去——否则用户的一句话描述（右泡）永不出现。
    dispatchAgentEvent(
      queryClient,
      agentEvent(
        "role-assigned",
        { projectId: "p1", runId: "run1", role: "BA", roleLabel: "顾问", stage: "PRD", engine: "agentscope" },
        "run1:1",
      ),
    );
    dispatchAgentEvent(
      queryClient,
      agentEvent("task-start", { projectId: "p1", runId: "run1", prompt: "做个官网", model: "m1" }, "run1:2"),
    );

    const run = useAgentStreamsStore.getState().runs["run1"];
    expect(run).toMatchObject({ prompt: "做个官网", model: "m1" });
    expect(run.segments).toEqual([
      { kind: "role", id: "run1:1", role: "BA", roleLabel: "顾问", stage: "PRD", engine: "agentscope" },
    ]);
  });

  it("error 帧无 task-start 前置 → 补建 stub run + error 段（起跑即死也可见，#61）", () => {
    // 真机事故口径：BA 起跑即死只发一帧 error（用户可能连上后才到）——run 必须被
    // 补建，错误才不是死寂。message 原样入段，由消费端（错误卡）负责用户口径。
    dispatchAgentEvent(
      queryClient,
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
    dispatchAgentEvent(
      queryClient,
      agentEvent("task-start", { projectId: "p1", runId: "run1" }),
    );
    dispatchAgentEvent(
      queryClient,
      agentEvent("wait-raised", { projectId: "p1", runId: "run1", waitId: "w1", kind: "PERMISSION", summary: "pnpm build" }, "run1:5"),
    );
    expect(useAgentStreamsStore.getState().runs["run1"].status).toBe("waiting");

    dispatchAgentEvent(
      queryClient,
      agentEvent("task-finish", { projectId: "p1", runId: "run1", sessionId: "s1", finish: "completed" }, "run1:9"),
    );
    const run = useAgentStreamsStore.getState().runs["run1"];
    expect(run.status).toBe("finished");
    expect(run.segments).toEqual([
      { kind: "wait", id: "run1:5", waitId: "w1", waitKind: "PERMISSION", summary: "pnpm build" },
      { kind: "finish", id: "run1:9", finish: "completed" },
    ]);
  });

  it("wait-raised / wait-settled → todos 域失效（等待点开关即待办增删，重拉走 REST）", async () => {
    const todos = observeActiveQuery(queryClient, queryKeys.todos.all);
    const unsubscribe = todos.unsubscribe;
    await todos.waitForSettled();

    dispatchAgentEvent(
      queryClient,
      agentEvent("wait-raised", { projectId: "p1", runId: "run1", waitId: "w1", kind: "QUESTION", summary: "选哪个配色" }, "run1:5"),
    );
    dispatchAgentEvent(
      queryClient,
      agentEvent("wait-settled", { projectId: "p1", runId: "run1", waitId: "w1", outcome: "answered" }, "run1:6"),
    );

    await vi.waitFor(() => expect(todos.fetchCount()).toBe(3));
    unsubscribe();
  });

  it("knowledge-retrieved → knowledge 段：items 原样入段、帧内顺序保持、段 id = SSE 事件 id（#23）", () => {
    const items1 = [
      { kind: "QA", projectName: "电商官网一期", title: "用哪个前端框架?", snippet: "问：…\n答：React" },
      { kind: "BUG", projectName: "电商官网一期", title: "登录 500" },
    ];
    const items2 = [{ kind: "ARTIFACT", projectName: "官网 demo", title: "PRD.md", snippet: "做一个电商官网…" }];

    dispatchAgentEvent(
      queryClient,
      agentEvent("knowledge-retrieved", { projectId: "p1", runId: "run1", items: items1 }, "run1:2"),
    );
    // 同 run 第二次检索注入（阶段首个任务外的后续任务也注入，A5 §3）：各成一段、顺序不乱
    dispatchAgentEvent(
      queryClient,
      agentEvent("knowledge-retrieved", { projectId: "p1", runId: "run1", items: items2 }, "run1:7"),
    );

    const run = useAgentStreamsStore.getState().runs["run1"];
    expect(run.segments).toEqual([
      { kind: "knowledge", id: "run1:2", items: items1 },
      { kind: "knowledge", id: "run1:7", items: items2 },
    ]);
  });

  it("knowledge-retrieved 不进任何失效注册表：纯 streams store 展示态，无 REST 对应物（#23）", async () => {
    const todos = observeActiveQuery(queryClient, queryKeys.todos.all);
    const projects = observeActiveQuery(queryClient, queryKeys.projects.all);
    const tasks = observeActiveQuery(queryClient, queryKeys.tasks.all);
    await todos.waitForSettled();
    await projects.waitForSettled();
    await tasks.waitForSettled();

    dispatchAgentEvent(
      queryClient,
      agentEvent(
        "knowledge-retrieved",
        { projectId: "p1", runId: "run1", items: [{ kind: "QA", projectName: "官网", title: "t" }] },
        "run1:2",
      ),
    );

    // 等一拍确认没有任何 invalidate 漏出（三段各只 fetch 过初始一次）
    await new Promise((r) => setTimeout(r, 20));
    expect(todos.fetchCount()).toBe(1);
    expect(projects.fetchCount()).toBe(1);
    expect(tasks.fetchCount()).toBe(1);
    todos.unsubscribe();
    projects.unsubscribe();
    tasks.unsubscribe();
  });

  it("未知透传 type → passthrough 段（开放集合不丢事件）；坏数据静默忽略", () => {
    dispatchAgentEvent(
      queryClient,
      agentEvent("part-updated", { projectId: "p1", runId: "run1", data: { x: 1 } }, "run1:3"),
    );
    expect(() => dispatchAgentEvent(queryClient, { id: "y", data: "not json" })).not.toThrow();

    const run = useAgentStreamsStore.getState().runs["run1"];
    expect(run.segments).toEqual([
      { kind: "passthrough", id: "run1:3", type: "part-updated", data: { x: 1 } },
    ]);
  });
});
