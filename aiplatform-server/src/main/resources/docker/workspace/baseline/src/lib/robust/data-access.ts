import { Pool, type QueryResultRow } from "pg";
import { createClient } from "redis";

/**
 * 数据访问层（基座健壮件·#421）：pg/redis 访问的超时＋重试＋降级统一收口。
 * 机制/呈现分离——本件只担行为契约（不挂死、错误归一），业务长相归调用方。
 *
 * - query：超时（race 计时器，调用方永不无限等待）＋瞬时错误重试（退避）；
 *   非瞬时错误（约束冲突/语法错等）立即归一抛出，不空烧重试。
 * - cacheGet/cacheSet/cacheDelete：redis 读写恒降级——失败/超时返回 null / false，
 *   不抛、不挂死（redis 半开时页面照常出，锚案例 #407 的挂死面收口）。
 *
 * 测试跑道就位：行为契约测试见同目录 data-access.test.ts（注入假 pool/kv，
 * 零真实连接）。
 */

/** 事务客户端的窄结构面（BEGIN/COMMIT/ROLLBACK 由本件发、事务体只用这两件；
 * 真 pg PoolClient 结构性满足）。 */
export interface TxClient {
  query(text: string, params?: unknown[]): Promise<unknown>;
  release(err?: Error): void;
}

/** pg 连接池的最小面（注入假件测试用；真实件由 pg Pool 承担）。 */
export interface PgLike {
  query(text: string, params?: unknown[]): Promise<unknown>;
  connect(): Promise<TxClient>;
}

/** redis 键值的最小面（注入假件测试用；真实件由 redis 客户端适配承担）。 */
export interface KvLike {
  get(key: string): Promise<string | null>;
  set(key: string, value: string, ttlSeconds?: number): Promise<unknown>;
  del(key: string): Promise<unknown>;
}

/** 归一错误：kind 分流（timeout / exhausted / pg / kv）。 */
export class DataAccessError extends Error {
  readonly kind: "timeout" | "exhausted" | "pg";
  readonly code?: string;

  constructor(kind: "timeout" | "exhausted" | "pg", message: string, code?: string) {
    super(message);
    this.name = "DataAccessError";
    this.kind = kind;
    this.code = code;
  }
}

export interface QueryOptions {
  /** 单次尝试超时毫秒（缺省 5000）。 */
  timeoutMs?: number;
  /** 瞬时错误重试次数（缺省 1，0 = 不重试）。 */
  retries?: number;
}

export interface CacheOptions {
  /** 超时毫秒（缺省 2000）——超时按降级处理，不抛。 */
  timeoutMs?: number;
}

/** 瞬时错误码（网络抖动/连接类）——值得重试；其余（约束冲突 23505、语法错 42601
 * 等）重试只是空烧。 */
const TRANSIENT_PG_CODES = new Set([
  "ECONNRESET",
  "ECONNREFUSED",
  "ETIMEDOUT",
  "EPIPE",
  "ENOTFOUND",
  "08000",
  "08003",
  "08006",
  "40001",
  "40P01",
  "53300",
  "57P01",
  "57P02",
  "57P03",
]);

const DEFAULT_QUERY_TIMEOUT_MS = 5_000;
const DEFAULT_QUERY_RETRIES = 1;
const DEFAULT_RETRY_BACKOFF_MS = 100;
const DEFAULT_CACHE_TIMEOUT_MS = 2_000;

function isTransientPgError(e: unknown): boolean {
  return (
    e instanceof Object &&
    "code" in e &&
    typeof (e as { code: unknown }).code === "string" &&
    TRANSIENT_PG_CODES.has((e as { code: string }).code)
  );
}

function pgErrorCode(e: unknown): string | undefined {
  if (e instanceof Object && "code" in e && typeof (e as { code: unknown }).code === "string") {
    return (e as { code: string }).code;
  }
  return undefined;
}

/** race 计时器：fn 落定前先到 timeoutMs 即拒绝（调用方不挂死；底层操作被弃置）。 */
function withTimeout<T>(ms: number, onTimeout: () => Error, fn: () => Promise<T>): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => reject(onTimeout()), ms);
    fn().then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      (e) => {
        clearTimeout(timer);
        reject(e);
      },
    );
  });
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

export interface DataAccess {
  /** pg 查询：超时＋瞬时重试；非瞬时/耗尽归一抛 DataAccessError。返回行数组。 */
  query<Row extends QueryResultRow = QueryResultRow>(
    text: string,
    params?: unknown[],
    opts?: QueryOptions,
  ): Promise<Row[]>;
  /** 单行便捷面：首行或 null。 */
  queryOne<Row extends QueryResultRow = QueryResultRow>(
    text: string,
    params?: unknown[],
    opts?: QueryOptions,
  ): Promise<Row | null>;
  /** 事务：客户端借出回调（BEGIN/COMMIT/ROLLBACK 由本件包办）。超时恒在
   * （借出与事务体各自 race 计时）；重试缺省 0——事务是副作用面，不自动重放，
   * 确需重试由调用方显式传 opts.retries 自决。 */
  withTransaction<T>(fn: (client: TxClient) => Promise<T>, opts?: QueryOptions): Promise<T>;
  /** redis 读（降级面）：失败/超时 → null，不抛不挂死。 */
  cacheGet(key: string, opts?: CacheOptions): Promise<string | null>;
  /** redis 写（降级面）：成功 true；失败/超时 false，不抛。 */
  cacheSet(key: string, value: string, ttlSeconds?: number, opts?: CacheOptions): Promise<boolean>;
  /** redis 删（降级面）：成功 true；失败/超时 false，不抛。 */
  cacheDelete(key: string, opts?: CacheOptions): Promise<boolean>;
}

/**
 * 工厂（测试注入口）：pool/kv 缺省从 .env 连接串惰性建真实件——模块加载零连接
 * 动作（骨架页阶段不碰库）；首次 cache 调用才连 redis，连不上即走降级。
 */
export function createDataAccess(deps: { pool?: PgLike; kv?: KvLike } = {}): DataAccess {
  const pool: PgLike = deps.pool ?? new Pool({ connectionString: process.env.DATABASE_URL });
  const kv: KvLike | null = deps.kv !== undefined ? deps.kv : defaultRedisKv();

  async function queryOnce(text: string, params: unknown[] | undefined, timeoutMs: number) {
    const raw = await withTimeout(
      timeoutMs,
      () => new DataAccessError("timeout", `查询超时（${timeoutMs}ms）: ${text.slice(0, 80)}`),
      () => pool.query(text, params),
    );
    return raw as { rows: unknown[] };
  }

  async function query<Row extends QueryResultRow = QueryResultRow>(
    text: string,
    params?: unknown[],
    opts?: QueryOptions,
  ): Promise<Row[]> {
    const { rows } = await runWithRetry(text, params, opts);
    return rows as Row[];
  }

  async function runWithRetry(
    text: string,
    params: unknown[] | undefined,
    opts?: QueryOptions,
  ): Promise<{ rows: unknown[] }> {
    const timeoutMs = opts?.timeoutMs ?? DEFAULT_QUERY_TIMEOUT_MS;
    const retries = opts?.retries ?? DEFAULT_QUERY_RETRIES;
    let lastError: unknown;
    for (let attempt = 0; attempt <= retries; attempt++) {
      try {
        return await queryOnce(text, params, timeoutMs);
      } catch (e) {
        lastError = e;
        const timedOut = e instanceof DataAccessError && e.kind === "timeout";
        if (!timedOut && !isTransientPgError(e)) {
          throw normalizePgError(e);
        }
        if (attempt < retries) {
          await sleep(DEFAULT_RETRY_BACKOFF_MS * (attempt + 1));
        }
      }
    }
    throw terminalErrorOf(lastError, retries);
  }

  /** 末次错误如实归一：超时就是 timeout（哪怕重试过）；非超时瞬时错重试后仍败
   * 才是 exhausted；其余归 pg。调用方按 kind 分流不被重试细节误导。 */
  function terminalErrorOf(lastError: unknown, retries: number): DataAccessError {
    if (lastError instanceof DataAccessError && lastError.kind === "timeout") {
      return new DataAccessError(
        "timeout",
        retries > 0 ? `重试 ${retries + 1} 次后仍超时: ${lastError.message}` : lastError.message,
      );
    }
    if (lastError instanceof DataAccessError) {
      throw lastError; // 不可达（timeout 之外的 DataAccessError 不进重试环）
    }
    if (isTransientPgError(lastError) && retries > 0) {
      return new DataAccessError(
        "exhausted",
        `重试 ${retries + 1} 次后仍瞬时失败: ${normalizePgError(lastError).message}`,
        pgErrorCode(lastError),
      );
    }
    return normalizePgError(lastError);
  }

  function normalizePgError(e: unknown): DataAccessError {
    const code = pgErrorCode(e);
    const message = e instanceof Error ? e.message : String(e);
    return new DataAccessError("pg", message, code);
  }

  async function queryOne<Row extends QueryResultRow = QueryResultRow>(
    text: string,
    params?: unknown[],
    opts?: QueryOptions,
  ): Promise<Row | null> {
    const rows = await query<Row>(text, params, opts);
    return rows.length > 0 ? rows[0] : null;
  }

  async function withTransaction<T>(fn: (client: TxClient) => Promise<T>, opts?: QueryOptions): Promise<T> {
    const timeoutMs = opts?.timeoutMs ?? DEFAULT_QUERY_TIMEOUT_MS;
    const retries = opts?.retries ?? 0; // 事务不自动重试（副作用面），调用方自决
    let lastError: unknown;
    for (let attempt = 0; attempt <= retries; attempt++) {
      let client: TxClient | null = null;
      try {
        const acquired = (await withTimeout(
          timeoutMs,
          () => new DataAccessError("timeout", `连接获取超时（${timeoutMs}ms）`),
          () => pool.connect(),
        ));
        client = acquired;
        await acquired.query("BEGIN");
        const result = await withTimeout(timeoutMs, () => new DataAccessError("timeout", "事务体超时"), () => fn(acquired));
        await acquired.query("COMMIT");
        client.release();
        return result;
      } catch (e) {
        if (client) {
          try {
            client.release(e instanceof Error ? e : new Error(String(e)));
          } catch {
            // release 自身异常不影响归一
          }
        }
        lastError = e;
        const timedOut = e instanceof DataAccessError && e.kind === "timeout";
        if (!timedOut && !isTransientPgError(e)) {
          throw normalizePgError(e);
        }
        if (attempt < retries) {
          await sleep(DEFAULT_RETRY_BACKOFF_MS * (attempt + 1));
        }
      }
    }
    throw terminalErrorOf(lastError, retries);
  }

  /** 降级包装：kv 缺位/失败/超时一律按降级值落定（读 null / 写删 false），不抛。 */
  async function degraded<T>(fallback: T, op: (client: KvLike) => Promise<T>, timeoutMs: number): Promise<T> {
    if (!kv) {
      return fallback;
    }
    try {
      return await withTimeout(
        timeoutMs,
        () => new DataAccessError("timeout", "缓存超时"),
        () => op(kv),
      );
    } catch (e) {
      console.warn(`[data-access] 缓存降级: ${e instanceof Error ? e.message : String(e)}`);
      return fallback;
    }
  }

  return {
    query,
    queryOne,
    withTransaction,
    cacheGet: (key, opts) => degraded(null, (c) => c.get(key), opts?.timeoutMs ?? DEFAULT_CACHE_TIMEOUT_MS),
    cacheSet: (key, value, ttlSeconds, opts) =>
      degraded(false, async (c) => {
        await c.set(key, value, ttlSeconds);
        return true;
      }, opts?.timeoutMs ?? DEFAULT_CACHE_TIMEOUT_MS),
    cacheDelete: (key, opts) =>
      degraded(false, async (c) => {
        await c.del(key);
        return true;
      }, opts?.timeoutMs ?? DEFAULT_CACHE_TIMEOUT_MS),
  };
}

/** 惰性 redis 适配（连接串缺位 → null 即全程降级）。 */
function defaultRedisKv(): KvLike | null {
  if (!process.env.REDIS_URL) {
    return null;
  }
  let client: ReturnType<typeof createClient> | null = null;
  let connecting: Promise<ReturnType<typeof createClient>> | null = null;
  const ensure = () => {
    if (client) {
      return Promise.resolve(client);
    }
    connecting ??= createClient({ url: process.env.REDIS_URL })
      .connect()
      .then((c) => {
        client = c;
        return c;
      })
      .catch((e: unknown) => {
        connecting = null; // 失败可重连（下次调用再试），不缓存拒因
        throw e;
      });
    return connecting;
  };
  return {
    get: async (key) => {
      const c = await ensure();
      return (await c.get(key)) ?? null;
    },
    set: async (key, value, ttlSeconds) => {
      const c = await ensure();
      return ttlSeconds
        ? c.set(key, value, { EX: ttlSeconds })
        : c.set(key, value);
    },
    del: async (key) => {
      const c = await ensure();
      return c.del(key);
    },
  };
}

/** 缺省单例（应用代码的常用入口；测试用 createDataAccess 注入假件）。 */
const defaultAccess = createDataAccess();
export const query = defaultAccess.query;
export const queryOne = defaultAccess.queryOne;
export const withTransaction = defaultAccess.withTransaction;
export const cacheGet = defaultAccess.cacheGet;
export const cacheSet = defaultAccess.cacheSet;
export const cacheDelete = defaultAccess.cacheDelete;
