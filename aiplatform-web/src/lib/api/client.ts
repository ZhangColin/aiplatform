import { buildLoginRedirectUrl } from "@/lib/auth/login-redirect";

import { ApiError, type ApiEnvelopeMeta } from "./api-error";

/** 后端统一信封（aiplatform-server ADR-0001）。实际字段以首个对接 issue 首跑 gen:api 实测为准。 */
export type ApiResponse<T> = ApiEnvelopeMeta & {
  data: T;
};

/** 分页响应，page 为 1 基。原样返回，不再拆 items。 */
export type PageResponse<T> = {
  items: T[];
  total: number;
  page: number;
  size: number;
};

type QueryValue = string | number | boolean;

type RequestOptions = {
  signal?: AbortSignal;
  /** URL query 参数，undefined / 空数组 的键会被丢弃。 */
  query?: Record<string, QueryValue | QueryValue[] | undefined>;
};

/** 401 全局出口的并发去重：整页跳转期间可能并发触发多个 401。spec 0004 §3。 */
let redirecting = false;

function redirectToLogin() {
  if (redirecting) return;
  redirecting = true;
  // 有意整页跳而非 client router：会话过期需丢弃全部内存态。spec 0004 §3
  window.location.href = buildLoginRedirectUrl(
    window.location.pathname,
    window.location.search,
  );
}

function buildUrl(path: string, query: RequestOptions["query"]) {
  const url = new URL(`/api${path}`, window.location.origin);
  for (const [key, value] of Object.entries(query ?? {})) {
    if (value === undefined) continue;
    for (const v of Array.isArray(value) ? value : [value]) {
      url.searchParams.append(key, String(v));
    }
  }
  return url;
}

/**
 * dev 专用 in-flight GET 去重（issue #60）：StrictMode 双挂载让同一 URL 的 GET
 * 在 ~10ms 内连发两次——首个虽随 cleanup abort，但 HTTP 已出门，服务端实打实
 * 收到两份。落定即清，只去重并发、不做结果缓存；写操作（mutation 用户触发，
 * 非挂载触发）不去重。生产构建置 null：行为零变化。
 *
 * 语义取舍：共享请求不挂消费者 signal——否则首个挂载方 cleanup 的 abort 会
 * 击穿重挂载方正等着的同一请求，去重失效。副作用是 invalidate 触发的 refetch
 * 可能搭上仍在途的上一代请求、拿到改前数据：仅 dev，且窗口为「上一代 GET 尚在
 * 途时完成 mutation」——初载几十 ms 内人类点不了这么快；另一条路径是 SSE 断连
 * 期间 15s 门控轮询的在途 GET 撞上 mutation（后端重启期间可发生），下轮轮询即
 * 自愈。接受。
 */
const inFlightGets =
  process.env.NODE_ENV === "production" ? null : new Map<string, Promise<unknown>>();

async function request<T>(path: string, options: RequestOptions & { method: string; body?: unknown }): Promise<T> {
  const { method, query } = options;
  const url = buildUrl(path, query);

  if (method === "GET" && inFlightGets) {
    const key = url.toString();
    const existing = inFlightGets.get(key);
    if (existing) return existing as Promise<T>;
    const shared = runRequest<T>(url, { ...options, signal: undefined }).finally(() => {
      if (inFlightGets.get(key) === shared) inFlightGets.delete(key);
    });
    inFlightGets.set(key, shared);
    return shared;
  }

  return runRequest<T>(url, options);
}

async function runRequest<T>(url: URL, options: RequestOptions & { method: string; body?: unknown }): Promise<T> {
  const { method, body, signal } = options;
  const res = await fetch(url, {
    method,
    headers: body === undefined ? undefined : { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal,
  });

  if (!res.ok) {
    const payload: ApiResponse<unknown> | null = await res.json().catch(() => null);
    if (res.status === 401) {
      // 一个出口、零分支：无 toast、无确认，整页跳 BFF 登录并带 returnTo 回跳。
      redirectToLogin();
    }
    throw new ApiError({
      status: res.status,
      code: payload?.code,
      message: payload?.message,
      errors: payload?.errors,
      requestId: payload?.requestId ?? res.headers.get("x-request-id") ?? undefined,
    });
  }

  if (res.status === 204) return undefined as T;
  const payload: unknown = await res.json();
  // 统一解包：信封 ApiResponse → data（分页端点的 data 即 PageResponse，原样返回）；
  // 无信封的裸 JSON 按原样返回。
  return payload !== null && typeof payload === "object" && "data" in payload
    ? (payload as ApiResponse<T>).data
    : (payload as T);
}

/** 唯一网络出口：一律相对路径 /api/*（next.config.ts rewrite → 后端，同源 cookie 自动携带）。 */
export const api = {
  get: <T>(path: string, options?: RequestOptions) =>
    request<T>(path, { ...options, method: "GET" }),
  post: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    request<T>(path, { ...options, method: "POST", body }),
  put: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    request<T>(path, { ...options, method: "PUT", body }),
  patch: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    request<T>(path, { ...options, method: "PATCH", body }),
  delete: <T>(path: string, options?: RequestOptions) =>
    request<T>(path, { ...options, method: "DELETE" }),
};
