import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpError, httpFetch, httpJson } from "./http";

/** fetch 桩：脚本化行为序列，尊重 abort（真实 fetch 语义——超时经 signal 触发）。 */
function stubFetch(script: ((input: string, init?: RequestInit) => unknown)[]) {
  let call = 0;
  const fn = vi.fn((input: string | URL, init?: RequestInit) => {
    const step = script[Math.min(call, script.length - 1)];
    call++;
    const outcome = step(String(input), init);
    if (outcome instanceof Error) {
      return Promise.reject(outcome);
    }
    return Promise.resolve(outcome);
  });
  vi.stubGlobal("fetch", fn);
  return fn;
}

/** 半开对端仿真：永不主动落定，但尊重 abort signal（真实 fetch 语义）。 */
const hangingFetch = (init?: RequestInit) =>
  new Promise<Response>((_resolve, reject) => {
    init?.signal?.addEventListener("abort", () => {
      const e = new Error("The operation was aborted");
      e.name = "AbortError";
      reject(e);
    });
  });

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe("fetch 封装·超时（不挂死）", () => {
  it("对端半开挂死时按默认超时拒绝，kind=timeout", async () => {
    vi.useFakeTimers();
    stubFetch([(_input, init) => hangingFetch(init)]);
    const pending = httpFetch("https://api.example.com/data", undefined, { retries: 0 });
    void pending.catch(() => {});
    vi.advanceTimersByTime(10_100);
    await expect(pending).rejects.toSatisfy(
      (e: unknown) => e instanceof HttpError && e.kind === "timeout",
    );
  });

  it("自定义 timeoutMs 生效（短超时快速拒绝）", async () => {
    vi.useFakeTimers();
    stubFetch([(_input, init) => hangingFetch(init)]);
    const pending = httpFetch("https://api.example.com/data", undefined, { timeoutMs: 50, retries: 0 });
    void pending.catch(() => {});
    vi.advanceTimersByTime(60);
    await expect(pending).rejects.toSatisfy(
      (e: unknown) => e instanceof HttpError && e.kind === "timeout",
    );
  });
});

describe("fetch 封装·错误归一", () => {
  it("网络层异常（TypeError）归一为 kind=network", async () => {
    stubFetch([() => new TypeError("fetch failed")]);
    await expect(httpFetch("https://api.example.com/data")).rejects.toSatisfy(
      (e: unknown) => e instanceof HttpError && e.kind === "network",
    );
  });

  it("HTTP 非 2xx 归一为 kind=http 且携带 status", async () => {
    stubFetch([() => new Response("unavailable", { status: 503 })]);
    await expect(httpFetch("https://api.example.com/data")).rejects.toSatisfy(
      (e: unknown) => e instanceof HttpError && e.kind === "http" && e.status === 503,
    );
  });

  it("2xx 正常返回 Response 原文", async () => {
    stubFetch([() => new Response('{"ok":true}', { status: 200 })]);
    const res = await httpFetch("https://api.example.com/data");
    expect(res.status).toBe(200);
    expect(await res.text()).toBe('{"ok":true}');
  });
});

describe("fetch 封装·重试", () => {
  it("GET 网络错缺省重试一次后成功", async () => {
    vi.useFakeTimers();
    const fetchMock = stubFetch([
      () => new TypeError("fetch failed"),
      () => new Response("ok", { status: 200 }),
    ]);
    const pending = httpFetch("https://api.example.com/data");
    await vi.advanceTimersByTimeAsync(500);
    await expect(pending.then((r) => r.status)).resolves.toBe(200);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("POST 不重试（副作用面——缺省零重试）", async () => {
    vi.useFakeTimers();
    const fetchMock = stubFetch([() => new TypeError("fetch failed")]);
    const pending = httpFetch("https://api.example.com/data", { method: "POST", body: "x" });
    void pending.catch(() => {});
    await vi.advanceTimersByTimeAsync(500);
    await expect(pending).rejects.toSatisfy(
      (e: unknown) => e instanceof HttpError && e.kind === "network",
    );
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});

describe("fetch 封装·JSON 便捷面", () => {
  it("httpJson 解析 JSON 返回数据", async () => {
    stubFetch([() => new Response('{"a":1}', { status: 200 })]);
    await expect(httpJson<{ a: number }>("https://api.example.com/data")).resolves.toEqual({ a: 1 });
  });

  it("坏 JSON 归一为 kind=parse", async () => {
    stubFetch([() => new Response("not json", { status: 200 })]);
    await expect(httpJson("https://api.example.com/data")).rejects.toSatisfy(
      (e: unknown) => e instanceof HttpError && e.kind === "parse",
    );
  });
});
