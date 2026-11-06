/**
 * fetch 封装（基座健壮件·#421）：外部 HTTP 调用的默认超时＋错误归一统一收口。
 *
 * - 超时：AbortController＋计时器（缺省 10s）——对端半开时调用方不无限等待。
 * - 归一：一切失败面收拢为 HttpError（kind: timeout / network / http / parse），
 *   调用方按 kind 分流，不碰裸 fetch 的异形错误（TypeError/AbortError/Response）。
 * - 重试：仅幂等 GET 缺省重试 1 次（网络错/超时后）；带副作用的方法缺省零重试
 *   （重复提交面交调用方自决）。
 *
 * 行为契约测试见同目录 http.test.ts（fetch 全桩，零真实网络）。
 */

export type HttpErrorKind = "timeout" | "network" | "http" | "parse";

/** 归一错误：kind 分流；kind=http 时带 status。 */
export class HttpError extends Error {
  readonly kind: HttpErrorKind;
  readonly status?: number;
  readonly url: string;

  constructor(kind: HttpErrorKind, message: string, url: string, status?: number) {
    super(message);
    this.name = "HttpError";
    this.kind = kind;
    this.url = url;
    this.status = status;
  }
}

export interface HttpFetchOptions {
  /** 超时毫秒（缺省 10_000）。 */
  timeoutMs?: number;
  /** 网络错/超时后的额外重试次数；缺省 GET 1 次、其余方法 0 次。 */
  retries?: number;
}

const DEFAULT_TIMEOUT_MS = 10_000;
const RETRY_BACKOFF_MS = 200;

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

function isAbortError(e: unknown): boolean {
  return e instanceof Error && e.name === "AbortError";
}

async function fetchOnce(
  url: string,
  init: RequestInit | undefined,
  timeoutMs: number,
): Promise<Response> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  // 调用方已传 signal（上游取消链）与超时 signal 二选一尊重调用方——模板面
  // 不合并 AbortSignal（Node 22 有 anySignal 但语义易踩），文档注明即可。
  try {
    const res = await fetch(url, { ...init, signal: controller.signal });
    if (!res.ok) {
      throw new HttpError("http", `HTTP ${res.status}: ${url}`, url, res.status);
    }
    return res;
  } catch (e) {
    if (e instanceof HttpError) {
      throw e;
    }
    if (isAbortError(e)) {
      throw new HttpError("timeout", `请求超时（${timeoutMs}ms）: ${url}`, url);
    }
    throw new HttpError("network", `网络错误: ${e instanceof Error ? e.message : String(e)}`, url);
  } finally {
    clearTimeout(timer);
  }
}

export async function httpFetch(
  url: string,
  init?: RequestInit,
  opts?: HttpFetchOptions,
): Promise<Response> {
  const timeoutMs = opts?.timeoutMs ?? DEFAULT_TIMEOUT_MS;
  const method = (init?.method ?? "GET").toUpperCase();
  const retries = opts?.retries ?? (method === "GET" ? 1 : 0);
  let lastError: unknown;
  for (let attempt = 0; attempt <= retries; attempt++) {
    try {
      return await fetchOnce(url, init, timeoutMs);
    } catch (e) {
      lastError = e;
      const retryable = e instanceof HttpError && (e.kind === "timeout" || e.kind === "network");
      if (!retryable || attempt === retries) {
        throw e;
      }
      await sleep(RETRY_BACKOFF_MS);
    }
  }
  throw lastError; // 不可达（循环内必抛）
}

/** JSON 便捷面：取体解析；坏 JSON 归一为 kind=parse。 */
export async function httpJson<T = unknown>(
  url: string,
  init?: RequestInit,
  opts?: HttpFetchOptions,
): Promise<T> {
  const res = await httpFetch(url, init, opts);
  try {
    return (await res.json()) as T;
  } catch {
    throw new HttpError("parse", `响应不是合法 JSON: ${url}`, url);
  }
}
