import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { useSseStatusStore } from "@/lib/store/sse-status";

import { SseConnection } from "./connection";

/**
 * 原生 EventSource 的最小替身（公开行为 seam：构造次数 / readyState / close 调用）。
 * 遵循浏览器语义：非 2xx 与显式 close → CLOSED；断流 → CONNECTING（自动重试）。
 */
class FakeEventSource {
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSED = 2;
  static instances: FakeEventSource[] = [];

  readyState = FakeEventSource.CONNECTING;
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  closeSpy = vi.fn();

  private listeners = new Map<string, (ev: { data: string; lastEventId: string }) => void>();

  constructor(public url: string) {
    FakeEventSource.instances.push(this);
  }

  addEventListener(name: string, cb: (ev: { data: string; lastEventId: string }) => void) {
    this.listeners.set(name, cb);
  }

  close() {
    this.readyState = FakeEventSource.CLOSED;
    this.closeSpy();
  }

  // —— 测试驱动 helper ——
  simulateOpen() {
    this.readyState = FakeEventSource.OPEN;
    this.onopen?.();
  }
  simulateError(next: 0 | 2) {
    this.readyState = next;
    this.onerror?.();
  }
  simulateEvent(id: string, data: string) {
    this.listeners.get("event")?.({ data, lastEventId: id });
  }
}

/** 薄 client 依赖 window.location 构造 URL，node 环境补最小 stub。 */
function stubWindow() {
  vi.stubGlobal("window", {
    location: { origin: "http://localhost:3333", pathname: "/", search: "", href: "" },
  });
}

/** /api/me 探针的 fetch stub：status 可控，默认 200。 */
function stubProbe(status: number) {
  const fetchMock = vi.fn().mockResolvedValue(
    new Response(JSON.stringify({ data: { accountId: "u1" } }), { status }),
  );
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

function statusOf(channel: "notification" | "agent") {
  return useSseStatusStore.getState().statuses[channel];
}

function resetStatuses() {
  useSseStatusStore.getState().setStatus("notification", "offline");
  useSseStatusStore.getState().setStatus("agent", "offline");
}

beforeEach(() => {
  FakeEventSource.instances = [];
  vi.stubGlobal("EventSource", FakeEventSource);
  stubWindow();
  stubProbe(200);
  resetStatuses();
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("SseConnection", () => {
  it("connect：置 connecting → onopen 置 connected；重复 connect 幂等（StrictMode 双挂载）", () => {
    const conn = new SseConnection({
      channel: "notification",
      url: "/api/events",
      onEvent: vi.fn(),
    });

    conn.connect();
    conn.connect(); // 双挂载：不得建第二条

    expect(FakeEventSource.instances).toHaveLength(1);
    expect(statusOf("notification")).toBe("connecting");

    FakeEventSource.instances[0].simulateOpen();
    expect(statusOf("notification")).toBe("connected");

    conn.connect(); // 已连：仍只有一条
    expect(FakeEventSource.instances).toHaveLength(1);
  });

  it("事件经 onEvent 透传 {id, data}（SSE name 恒为 event）", () => {
    const onEvent = vi.fn();
    const conn = new SseConnection({ channel: "notification", url: "/api/events", onEvent });
    conn.connect();

    FakeEventSource.instances[0].simulateEvent("p1:42", '{"type":"preview-ready"}');

    expect(onEvent).toHaveBeenCalledWith({ id: "p1:42", data: '{"type":"preview-ready"}' });
  });

  it("dedupe（agent 通道）：重复的完整事件 id 只放行一次", () => {
    const onEvent = vi.fn();
    const conn = new SseConnection({
      channel: "agent",
      url: "/api/agent-events?projectId=p1",
      dedupe: true,
      onEvent,
    });
    conn.connect();
    const es = FakeEventSource.instances[0];

    es.simulateEvent("run1:5", '{"type":"text"}');
    es.simulateEvent("run1:5", '{"type":"text"}'); // 重复
    es.simulateEvent("run1:6", '{"type":"text"}');

    expect(onEvent).toHaveBeenCalledTimes(2);
  });

  it("dedupe 不开（通知通道）：重复事件照常透传（消费端幂等 invalidate，重复无害）", () => {
    const onEvent = vi.fn();
    const conn = new SseConnection({ channel: "notification", url: "/api/events", onEvent });
    conn.connect();
    const es = FakeEventSource.instances[0];

    es.simulateEvent("p1:1", "{}");
    es.simulateEvent("p1:1", "{}");

    expect(onEvent).toHaveBeenCalledTimes(2);
  });

  it("dedupe 上限清空：超限后整表清空，旧 id 再次到达不再被吞（内存有界 + 接补发的缝）", () => {
    const onEvent = vi.fn();
    const conn = new SseConnection({
      channel: "agent",
      url: "/api/agent-events",
      dedupe: true,
      onEvent,
    });
    conn.connect();
    const es = FakeEventSource.instances[0];

    // DEDUP_CAP = 1000：灌满后再来 1 个触发清空
    for (let i = 0; i < 1001; i++) es.simulateEvent(`run:${i}`, "{}");
    expect(onEvent).toHaveBeenCalledTimes(1001);

    es.simulateEvent("run:0", "{}"); // 清空后旧 id 重放 → 放行
    expect(onEvent).toHaveBeenCalledTimes(1002);
  });

  it("断流 → offline；重连成功（error 后的 onopen）触发 onReconnect，且失败计数清零", () => {
    const onReconnect = vi.fn();
    const conn = new SseConnection({
      channel: "notification",
      url: "/api/events",
      onEvent: vi.fn(),
      onReconnect,
    });
    conn.connect();
    const es = FakeEventSource.instances[0];
    es.simulateOpen();
    es.simulateError(FakeEventSource.CONNECTING); // 断流，浏览器自动重试中
    expect(statusOf("notification")).toBe("offline");

    es.simulateOpen(); // 原生重连成功
    expect(statusOf("notification")).toBe("connected");
    expect(onReconnect).toHaveBeenCalledTimes(1);

    // 首次 onopen 不算重连
    FakeEventSource.instances[0].simulateOpen();
    expect(onReconnect).toHaveBeenCalledTimes(1);
  });

  it("401 盲区：连续 5 次失败探一次 /api/me；非 401 不动原生重连（不 close、不另建连接）", async () => {
    const fetchMock = stubProbe(200);
    const conn = new SseConnection({ channel: "notification", url: "/api/events", onEvent: vi.fn() });
    conn.connect();
    const es = FakeEventSource.instances[0];
    es.simulateOpen();

    for (let i = 0; i < 4; i++) es.simulateError(FakeEventSource.CONNECTING);
    await vi.waitFor(() => expect(fetchMock).not.toHaveBeenCalled()); // 前 4 次不探

    es.simulateError(FakeEventSource.CONNECTING); // 第 5 次
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    // buildUrl 产出 URL 对象，字符串化后断言目标端点
    expect(String(fetchMock.mock.calls[0][0])).toContain("/api/me");

    expect(es.closeSpy).not.toHaveBeenCalled();
    expect(FakeEventSource.instances).toHaveLength(1);
    expect(statusOf("notification")).toBe("offline"); // 原生重连仍由浏览器负责

    es.simulateOpen(); // 恢复
    expect(statusOf("notification")).toBe("connected");
  });

  it("探针 401：停手——关闭连接、不再另建，connect() 也无法复活（交全局 401 流程）", async () => {
    stubProbe(401);
    const conn = new SseConnection({ channel: "notification", url: "/api/events", onEvent: vi.fn() });
    conn.connect();
    const es = FakeEventSource.instances[0];
    es.simulateOpen();

    for (let i = 0; i < 5; i++) es.simulateError(FakeEventSource.CONNECTING);
    await vi.waitFor(() => expect(es.closeSpy).toHaveBeenCalled());

    expect(FakeEventSource.instances).toHaveLength(1);
    expect(statusOf("notification")).toBe("offline");

    conn.connect(); // 停手后不复活
    expect(FakeEventSource.instances).toHaveLength(1);
  });

  it("CLOSED（浏览器放弃，如非 2xx）+ 探针非 401：~3s 后再播种新连接", async () => {
    vi.useFakeTimers();
    try {
      const conn = new SseConnection({
        channel: "notification",
        url: "/api/events",
        onEvent: vi.fn(),
      });
      conn.connect();
      const es = FakeEventSource.instances[0];
      es.simulateOpen();
      es.simulateError(FakeEventSource.CLOSED); // 非 2xx：浏览器不再自动重连

      await vi.advanceTimersByTimeAsync(2_999);
      expect(FakeEventSource.instances).toHaveLength(1); // 未到点不重建

      await vi.advanceTimersByTimeAsync(1);
      expect(FakeEventSource.instances).toHaveLength(2); // 再播种
      expect(statusOf("notification")).toBe("connecting");
    } finally {
      vi.useRealTimers();
    }
  });

  it("close()：关连接置 offline，并取消挂起的再播种（agent 通道 unmount 即断）", async () => {
    vi.useFakeTimers();
    try {
      const conn = new SseConnection({ channel: "agent", url: "/api/agent-events", onEvent: vi.fn() });
      conn.connect();
      const es = FakeEventSource.instances[0];
      es.simulateOpen();
      es.simulateError(FakeEventSource.CLOSED);
      await vi.advanceTimersByTimeAsync(0); // 让探针 promise 落定、定时挂起

      conn.close();
      expect(es.closeSpy).toHaveBeenCalled();
      expect(statusOf("agent")).toBe("offline");

      await vi.advanceTimersByTimeAsync(5_000);
      expect(FakeEventSource.instances).toHaveLength(1); // unmount 后不再播种
    } finally {
      vi.useRealTimers();
    }
  });

  it("再播种定时期间若已有新连接（外部 connect 重建）：定时器不误关它", async () => {
    vi.useFakeTimers();
    try {
      const conn = new SseConnection({ channel: "notification", url: "/api/events", onEvent: vi.fn() });
      conn.connect();
      const dead = FakeEventSource.instances[0];
      dead.simulateOpen();
      dead.simulateError(FakeEventSource.CLOSED);
      await vi.advanceTimersByTimeAsync(0); // 探针落定，3s 再播种已挂起

      conn.connect(); // 定时未到，外部重建成功（dead 已 CLOSED，connect 直接过）
      const fresh = FakeEventSource.instances[1];
      fresh.simulateOpen();
      expect(statusOf("notification")).toBe("connected");

      await vi.advanceTimersByTimeAsync(4_000);
      expect(fresh.closeSpy).not.toHaveBeenCalled(); // 旧定时器不得关掉新连接
      expect(FakeEventSource.instances).toHaveLength(2); // 也没有第三条被建出来
    } finally {
      vi.useRealTimers();
    }
  });
});
