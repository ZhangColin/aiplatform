// @vitest-environment happy-dom
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { StrictMode, useEffect } from "react";

import { useSseStatusStore } from "@/lib/store/sse-status";

import { useAgentStreamChannel } from "./agent-channel";

/**
 * agent 流通道的 StrictMode 实证（issue #60）：双挂载下 probe-cancel 守卫
 * （首个探针随 cleanup 作废）必须保证全程只建一条 EventSource——这层防线
 * 此前只有注释声明，无测试锁定。哨兵 effect 计数先自证本环境双挂载真的
 * 在跑（react dev build），防测试空转。
 */

class FakeEventSource {
  static instances: FakeEventSource[] = [];
  readyState = 0;
  closeSpy = vi.fn();
  constructor(public url: string) {
    FakeEventSource.instances.push(this);
  }
  // connection.ts 建连即挂 event 监听；本测试不模拟事件，空实现即可
  addEventListener() {}
  close() {
    this.readyState = 2;
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
let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  FakeEventSource.instances = [];
  useSseStatusStore.getState().setStatus("agent", "offline");
  // 探针 /api/me 的 fetch 桩：deferred 挂起，测试决定何时放行
  probe = deferred<Response>();
  fetchMock = vi.fn().mockReturnValue(probe.promise);
  vi.stubGlobal("fetch", fetchMock);
  vi.stubGlobal("EventSource", FakeEventSource);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("useAgentStreamChannel：StrictMode 双挂载（issue #60）", () => {
  it("哨兵自证：本环境 effect 双跑（否则以下断言空转）", () => {
    let setups = 0;
    function Sentinel() {
      useEffect(() => {
        setups += 1;
      }, []);
      return null;
    }
    render(<StrictMode><Sentinel /></StrictMode>);
    expect(setups).toBe(2);
  });

  it("双挂载全程只建一条 EventSource；两个探针搭同一 in-flight /api/me；unmount 即断", async () => {
    let setups = 0;
    function Host() {
      useEffect(() => {
        setups += 1;
      }, []);
      useAgentStreamChannel("p1");
      return null;
    }

    const { unmount } = render(
      <StrictMode>
        <QueryClientProvider client={new QueryClient()}>
          <Host />
        </QueryClientProvider>
      </StrictMode>,
    );

    // 双挂载已发生；探针在途，尚未建连
    expect(setups).toBe(2);
    expect(FakeEventSource.instances).toHaveLength(0);

    await act(async () => {
      probe.resolve(new Response(JSON.stringify({ data: { accountId: "u1" } })));
    });

    // 首个探针被 cleanup 作废 → 只建一条；两个 probe 搭同一 in-flight GET（client 去重）
    expect(FakeEventSource.instances).toHaveLength(1);
    expect(FakeEventSource.instances[0].url).toBe("/api/agent-events?projectId=p1");
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(useSseStatusStore.getState().statuses.agent).toBe("connecting");

    unmount();
    expect(FakeEventSource.instances[0].closeSpy).toHaveBeenCalledTimes(1);
    expect(useSseStatusStore.getState().statuses.agent).toBe("offline");
  });
});
