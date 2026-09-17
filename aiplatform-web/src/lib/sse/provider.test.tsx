// @vitest-environment happy-dom
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { StrictMode, useEffect } from "react";

import { useSseStatusStore } from "@/lib/store/sse-status";

import { SseProvider } from "./provider";

/**
 * 通知通道的 StrictMode 实证（issue #60）：provider 与 agent-channel 守卫同构
 * （probe-cancel），root 级挂载同样吃双挂载——agent 侧已锁，此处补齐另一条
 * 通道，单端点单流的声明才算完整锁定。另钉路由上下文登记接线（#206 toast
 * 分流——桥的 router 取用缝在此）。
 */

// useRouter 在 App Router 上下文外抛错（next 16）：mock 掉；bridge 的登记口
// spy（其余导出保持原样——事件分发不在此测）
const pushMock = vi.hoisted(() => vi.fn());
const noteRouteContextMock = vi.hoisted(() => vi.fn());
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock }),
  usePathname: () => "/",
}));
vi.mock("./bridge", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  noteRouteContext: noteRouteContextMock,
}));

class FakeEventSource {
  static instances: FakeEventSource[] = [];
  closeSpy = vi.fn();
  constructor(public url: string) {
    FakeEventSource.instances.push(this);
  }
  addEventListener() {}
  close() {
    this.closeSpy();
  }
}

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

let probe: ReturnType<typeof deferred<Response>>;

beforeEach(() => {
  FakeEventSource.instances = [];
  useSseStatusStore.getState().setStatus("notification", "offline");
  probe = deferred<Response>();
  pushMock.mockClear();
  noteRouteContextMock.mockClear();
  vi.stubGlobal("fetch", vi.fn().mockReturnValue(probe.promise));
  vi.stubGlobal("EventSource", FakeEventSource);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("SseProvider：StrictMode 双挂载（issue #60）", () => {
  it("双挂载全程只建一条 /api/events；unmount 即断", async () => {
    let setups = 0;
    function Host() {
      useEffect(() => {
        setups += 1;
      }, []);
      return null;
    }

    const { unmount } = render(
      <StrictMode>
        <QueryClientProvider client={new QueryClient()}>
          <SseProvider>
            <Host />
          </SseProvider>
        </QueryClientProvider>
      </StrictMode>,
    );

    expect(setups).toBe(2); // 双挂载前提成立（哨兵）
    expect(FakeEventSource.instances).toHaveLength(0); // 探针在途未建连

    await act(async () => {
      probe.resolve(new Response(JSON.stringify({ data: { accountId: "u1" } })));
    });

    expect(FakeEventSource.instances).toHaveLength(1); // 首个探针已作废，只建一条
    expect(FakeEventSource.instances[0].url).toBe("/api/events");
    expect(useSseStatusStore.getState().statuses.notification).toBe("connecting");

    unmount();
    expect(FakeEventSource.instances[0].closeSpy).toHaveBeenCalledTimes(1);
    expect(useSseStatusStore.getState().statuses.notification).toBe("offline");
  });
});

describe("SseProvider：路由上下文登记（#206 toast 分流）", () => {
  it("挂载即登记当前路由与 router.push——桥的组件内跳转由此取用", () => {
    render(
      <QueryClientProvider client={new QueryClient()}>
        <SseProvider>
          <div />
        </SseProvider>
      </QueryClientProvider>,
    );

    expect(noteRouteContextMock).toHaveBeenLastCalledWith({
      pathname: "/",
      push: expect.any(Function),
    });
    const { push } = noteRouteContextMock.mock.lastCall![0];
    push("/projects/900");
    expect(pushMock).toHaveBeenCalledWith("/projects/900");
  });
});
