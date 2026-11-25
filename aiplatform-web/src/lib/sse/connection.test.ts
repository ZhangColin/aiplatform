import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { useSseStatusStore } from "@/lib/store/sse-status";

import { probeSessionAlive, SseConnection } from "./connection";

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
  /** 心跳具名事件（#652：服务端每 15s 一条，看门狗的起搏点）。 */
  simulatePing() {
    this.listeners.get("ping")?.({ data: "", lastEventId: "" });
  }
}

/** 薄 client 依赖 window.location 与事件目标（#326 401 出口），node 环境补最小 stub。 */
function stubWindow() {
  vi.stubGlobal("window", makeWindowStub());
}

function makeWindowStub() {
  const location = { origin: "http://localhost:3333", pathname: "/", search: "", href: "" };
  // #326：401 出口事件化（window.dispatchEvent）——node stub 补最小事件目标。
  const listeners = new Map<string, Set<(e: unknown) => void>>();
  return {
    location,
    addEventListener: (name: string, fn: (e: unknown) => void) => {
      if (!listeners.has(name)) listeners.set(name, new Set());
      listeners.get(name)!.add(fn);
    },
    removeEventListener: (name: string, fn: (e: unknown) => void) => {
      listeners.get(name)?.delete(fn);
    },
    dispatchEvent: (event: { type: string }) => {
      for (const fn of listeners.get(event.type) ?? []) fn(event);
      return true;
    },
  };
}

/** 会话探针的 fetch stub：status 可控，默认 200（探 /api/projects，访客 era 起）。 */
function stubProbe(status: number) {
  const fetchMock = vi.fn().mockResolvedValue(
    new Response(JSON.stringify({ data: [] }), { status }),
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
      url: "/api/events?projectId=p1",
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
      url: "/api/events",
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

  it("401 盲区：连续 5 次失败探一次会话探针；非 401 不动原生重连（不 close、不另建连接）", async () => {
    const fetchMock = stubProbe(200);
    const conn = new SseConnection({ channel: "notification", url: "/api/events", onEvent: vi.fn() });
    conn.connect();
    const es = FakeEventSource.instances[0];
    es.simulateOpen();

    for (let i = 0; i < 4; i++) es.simulateError(FakeEventSource.CONNECTING);
    await vi.waitFor(() => expect(fetchMock).not.toHaveBeenCalled()); // 前 4 次不探

    es.simulateError(FakeEventSource.CONNECTING); // 第 5 次
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    // buildUrl 产出 URL 对象，字符串化后断言目标端点（/api/projects：账户或访客任一在场即活）
    expect(String(fetchMock.mock.calls[0][0])).toContain("/api/projects");

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
      const conn = new SseConnection({ channel: "agent", url: "/api/events", onEvent: vi.fn() });
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

describe("SseConnection 看门狗（#652：僵死连接判死——开着但沉默的流 45s 无心跳即主动重建）", () => {
  it("健康连接不判死：事件与 ping 持续到达，连接长期存活", async () => {
    vi.useFakeTimers();
    try {
      const conn = new SseConnection({ channel: "notification", url: "/api/events", onEvent: vi.fn() });
      conn.connect();
      const es = FakeEventSource.instances[0];
      es.simulateOpen();

      await vi.advanceTimersByTimeAsync(20_000);
      es.simulateEvent("p1:1", "{}");
      await vi.advanceTimersByTimeAsync(30_000);
      es.simulatePing(); // 心跳刷新看门狗
      await vi.advanceTimersByTimeAsync(44_000);

      expect(es.closeSpy).not.toHaveBeenCalled();
      expect(FakeEventSource.instances).toHaveLength(1);
      expect(statusOf("notification")).toBe("connected");
    } finally {
      vi.useRealTimers();
    }
  });

  it("僵死连接判死：45s 无任何事件（含 ping）→ 主动关闭、offline、~3s 再播种重建，重建成功走 onReconnect", async () => {
    vi.useFakeTimers();
    try {
      const onReconnect = vi.fn();
      const conn = new SseConnection({
        channel: "agent",
        url: "/api/events?projectId=p1",
        onEvent: vi.fn(),
        onReconnect,
      });
      conn.connect();
      const zombie = FakeEventSource.instances[0];
      zombie.simulateOpen();

      await vi.advanceTimersByTimeAsync(44_999);
      expect(zombie.closeSpy).not.toHaveBeenCalled(); // 未到阈值不动

      await vi.advanceTimersByTimeAsync(1);
      expect(zombie.closeSpy).toHaveBeenCalledTimes(1); // 判死即关：释放连接池槽位
      expect(statusOf("agent")).toBe("offline"); // 判死可见：既有 toast／门控轮询兜底生效
      expect(FakeEventSource.instances).toHaveLength(1); // 不立即重建

      await vi.advanceTimersByTimeAsync(2_999);
      expect(FakeEventSource.instances).toHaveLength(1); // 再播种节奏未到

      await vi.advanceTimersByTimeAsync(1);
      expect(FakeEventSource.instances).toHaveLength(2); // 重建
      expect(statusOf("agent")).toBe("connecting");

      FakeEventSource.instances[1].simulateOpen();
      expect(statusOf("agent")).toBe("connected");
      expect(onReconnect).toHaveBeenCalledTimes(1); // 判死后恢复 → 广谱 invalidate
    } finally {
      vi.useRealTimers();
    }
  });

  it("ping 是有效活动：只有心跳、无业务事件也不判死", async () => {
    vi.useFakeTimers();
    try {
      const conn = new SseConnection({ channel: "notification", url: "/api/events", onEvent: vi.fn() });
      conn.connect();
      const es = FakeEventSource.instances[0];
      es.simulateOpen();

      await vi.advanceTimersByTimeAsync(44_000);
      es.simulatePing();
      await vi.advanceTimersByTimeAsync(44_000); // 距上次 ping 44s < 45s

      expect(es.closeSpy).not.toHaveBeenCalled();
      expect(FakeEventSource.instances).toHaveLength(1);
    } finally {
      vi.useRealTimers();
    }
  });

  it("close() 后看门狗随实例停摆：判死与再播种都不再发生", async () => {
    vi.useFakeTimers();
    try {
      const conn = new SseConnection({ channel: "agent", url: "/api/events", onEvent: vi.fn() });
      conn.connect();
      const es = FakeEventSource.instances[0];
      es.simulateOpen();

      conn.close();
      await vi.advanceTimersByTimeAsync(60_000);

      expect(FakeEventSource.instances).toHaveLength(1); // 不判死重建（已显式关闭）
    } finally {
      vi.useRealTimers();
    }
  });

  it("ping 只喂看门狗：不进消息回调（onEvent 零调用）、无 id 不进去重集", async () => {
    vi.useFakeTimers();
    try {
      const onEvent = vi.fn();
      const conn = new SseConnection({
        channel: "agent",
        url: "/api/events?projectId=p1",
        dedupe: true,
        onEvent,
      });
      conn.connect();
      const es = FakeEventSource.instances[0];
      es.simulateOpen();

      es.simulatePing();
      es.simulatePing();
      await vi.advanceTimersByTimeAsync(0);

      expect(onEvent).not.toHaveBeenCalled(); // 心跳不进消息面
      expect(statusOf("agent")).toBe("connected"); // 看门狗被喂着
    } finally {
      vi.useRealTimers();
    }
  });

  it("error 断流（原生重试中）>45s 无活动：同样判死，重建为新连接（无 Last-Event-ID——REST 收敛，与刷新同语义）", async () => {
    vi.useFakeTimers();
    try {
      const conn = new SseConnection({ channel: "notification", url: "/api/events", onEvent: vi.fn() });
      conn.connect();
      const es = FakeEventSource.instances[0];
      es.simulateOpen();
      es.simulateEvent("p1:1", "{}");
      es.simulateError(FakeEventSource.CONNECTING); // 断流：浏览器自动重试（期间无事件无 open）

      await vi.advanceTimersByTimeAsync(44_999);
      expect(es.closeSpy).not.toHaveBeenCalled(); // <45s：原生重连路径不受扰动

      await vi.advanceTimersByTimeAsync(1);
      expect(es.closeSpy).toHaveBeenCalledTimes(1); // 重试僵持同样判死
      await vi.advanceTimersByTimeAsync(3_000);
      expect(FakeEventSource.instances).toHaveLength(2); // 新实例（新 EventSource 不带锚——取舍见 declareDead 注释）
    } finally {
      vi.useRealTimers();
    }
  });
});

describe("probeSessionAlive：访客 era 探针语义（/api/projects）", () => {
  it("访客凭证在场：探针 200 → alive（/me 对访客 401 与本探针无关）", async () => {
    stubProbe(200);
    await expect(probeSessionAlive()).resolves.toBe(true);
  });

  it("全无身份（匿名/凭证过期）：探针 401 → not alive（SSE 停手语义不变）", async () => {
    stubProbe(401);
    await expect(probeSessionAlive()).resolves.toBe(false);
  });

  it("网络错/5xx：会话未知 → alive（按原生节奏继续重连）", async () => {
    stubProbe(503);
    await expect(probeSessionAlive()).resolves.toBe(true);
  });
});
