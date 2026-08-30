import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/**
 * 薄 client 的 dev 专用 in-flight GET 去重（issue #60）：StrictMode 双挂载让
 * 同一 URL 的 GET 在 ~10ms 内连发两次（首个随 cleanup abort，但 HTTP 已出门，
 * 服务端实打实收到两份）。去重只在非生产构建启用——生产行为零变化。
 *
 * 每用例 vi.resetModules + 动态 import：模块态（in-flight 表 / 401 redirecting
 * 标记）逐用例全新，无需测试专用出口。
 */

type Client = typeof import("./client");

async function importClient(): Promise<Client> {
  vi.resetModules();
  return await import("./client");
}

/** buildUrl 依赖 window.location，node 环境补最小 stub。 */
function stubWindow() {
  vi.stubGlobal("window", {
    location: { origin: "http://localhost:3333", pathname: "/", search: "", href: "" },
  });
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status });
}

/** 恒定应答：每次调用产新 Response（同对象二次 .json() 会炸 body）——不去重路径必用。 */
function always(body: unknown, status = 200) {
  return () => Promise.resolve(jsonResponse(body, status));
}

function deferred<T>(): {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (reason?: unknown) => void;
} {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

/** fetch 桩：可控延迟，默认即刻 200。记录调用以数发次数。 */
function stubFetch() {
  const fetchMock = vi.fn();
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

beforeEach(() => {
  stubWindow();
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
});

describe("薄 client：dev in-flight GET 去重（issue #60）", () => {
  it("并发同参 GET 共享一次请求：两个调用方拿到同一结果，fetch 只发一次", async () => {
    const { api } = await importClient();
    const fetchMock = stubFetch().mockImplementation(always({ data: [{ id: "p1" }] }));

    const [a, b] = await Promise.all([api.get("/projects"), api.get("/projects")]);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(a).toEqual([{ id: "p1" }]);
    expect(b).toEqual([{ id: "p1" }]);
  });

  it("query 参数参与键：不同参数各发各的，相同参数（含数组）共享", async () => {
    const { api } = await importClient();
    const fetchMock = stubFetch().mockImplementation(always({ data: [] }));

    await Promise.all([
      api.get("/todos", { query: { view: "dev" } }),
      api.get("/todos", { query: { view: "dev" } }),
      api.get("/todos", { query: { view: "all" } }),
    ]);

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(String(fetchMock.mock.calls[0][0])).toContain("view=dev");
    expect(String(fetchMock.mock.calls[1][0])).toContain("view=all");
  });

  it("落定即清：上一轮完成后同参 GET 重新发起（只去重并发，不做结果缓存）", async () => {
    const { api } = await importClient();
    const fetchMock = stubFetch().mockImplementation(always({ data: 1 }));

    await api.get("/projects");
    await api.get("/projects");

    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("失败同样共享并落定即清：并发方一起拒绝，之后的重试是真新请求", async () => {
    const { api } = await importClient();
    const { ApiError } = await import("./api-error");
    const fetchMock = stubFetch()
      .mockResolvedValueOnce(jsonResponse({ message: "boom" }, 500))
      .mockResolvedValueOnce(jsonResponse({ data: "ok" }));

    const [a, b] = await Promise.allSettled([
      api.get("/projects"),
      api.get("/projects"),
    ]);
    expect(a.status).toBe("rejected");
    expect(b.status).toBe("rejected");
    expect((a as PromiseRejectedResult).reason).toBeInstanceOf(ApiError);

    await expect(api.get("/projects")).resolves.toBe("ok");
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("写操作不去重：并发相同 POST 各发各的（mutation 幂等性不由 transport 保证）", async () => {
    const { api } = await importClient();
    const fetchMock = stubFetch().mockImplementation(always({ data: "ok" }));

    await Promise.all([
      api.post("/projects", { name: "a" }),
      api.post("/projects", { name: "a" }),
    ]);

    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("共享请求不被消费者 abort 击穿：首个挂载方 cleanup abort 后，重挂载方仍拿到结果", async () => {
    const { api } = await importClient();
    const d = deferred<Response>();
    const fetchMock = stubFetch().mockReturnValue(d.promise);
    const controller = new AbortController();

    const first = api.get("/projects", { signal: controller.signal });
    const second = api.get("/projects");
    controller.abort(); // StrictMode cleanup：首个挂载方作废

    d.resolve(jsonResponse({ data: ["survived"] }));
    await expect(first).resolves.toEqual(["survived"]);
    await expect(second).resolves.toEqual(["survived"]);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    // 共享 fetch 不挂消费者 signal（挂了就会被首个挂载方击穿）
    expect(fetchMock.mock.calls[0][1]?.signal).toBeUndefined();
  });

  it("401 路径不受去重影响：共享方一起拒绝 + 全局出口整页跳", async () => {
    const { api } = await importClient();
    const hrefSetter = vi.fn();
    Object.defineProperty(window, "location", {
      value: {
        origin: "http://localhost:3333",
        pathname: "/projects/p1",
        search: "",
        get href() {
          return "";
        },
        set href(v: string) {
          hrefSetter(v);
        },
      },
      configurable: true,
    });
    stubFetch().mockImplementation(always({ message: "unauthorized" }, 401));

    const [a, b] = await Promise.allSettled([api.get("/me"), api.get("/me")]);

    expect(a.status).toBe("rejected");
    expect(b.status).toBe("rejected");
    expect(hrefSetter).toHaveBeenCalledTimes(1);
    expect(hrefSetter.mock.calls[0][0]).toContain("/auth/login");
  });

  it("生产构建不去重（行为零变化）：并发同参 GET 各发各的", async () => {
    vi.stubEnv("NODE_ENV", "production");
    const { api } = await importClient();
    const fetchMock = stubFetch().mockImplementation(always({ data: 1 }));

    await Promise.all([api.get("/projects"), api.get("/projects")]);

    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});
