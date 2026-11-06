import { describe, expect, it, vi } from "vitest";
import { createDataAccess, DataAccessError, type PgLike, type KvLike, type TxClient } from "./data-access";

/** 假 pg 连接池：脚本化 query 行为（失败序列 + 可控挂死）。 */
class FakePool implements PgLike {
  calls: string[] = [];
  private script: ((text: string) => unknown)[];

  constructor(script: ((text: string) => unknown)[]) {
    this.script = script;
  }

  connect(): Promise<never> {
    return Promise.reject(new Error("测试不触事务面"));
  }

  query(text: string): Promise<unknown> {
    this.calls.push(text);
    const step = this.script[Math.min(this.calls.length - 1, this.script.length - 1)];
    const outcome = step(text);
    if (outcome instanceof Promise) return outcome;
    if (outcome instanceof Error) return Promise.reject(outcome);
    return Promise.resolve(outcome);
  }
}

const err = (code: string, message = code) => {
  const e = new Error(message) as NodeJS.ErrnoException & { code: string };
  e.code = code;
  return e;
};

const fakeKv = (): KvLike & { store: Map<string, string> } => {
  const store = new Map<string, string>();
  return {
    store,
    get: (key: string) => Promise.resolve(store.get(key) ?? null),
    set: (key: string, value: string) => {
      store.set(key, value);
      return Promise.resolve("OK");
    },
    del: (key: string) => {
      store.delete(key);
      return Promise.resolve(1);
    },
  };
};

describe("数据访问层·超时（不挂死）", () => {
  it("query 挂死的 SQL 在 timeoutMs 内以超时错误归一拒绝（不无限等待）", async () => {
    vi.useFakeTimers();
    try {
      const pool = new FakePool([() => new Promise(() => {})]);
      const access = createDataAccess({ pool });
      const pending = access.query("SELECT 1", [], { timeoutMs: 50, retries: 0 });
      // 不 advance 定时器前不得落定（挂死防护是 race 结构，非轮询）
      const settled = await Promise.race([pending.then(() => true, () => true), Promise.resolve(false)]);
      expect(settled).toBe(false);
      vi.advanceTimersByTime(60);
      await expect(pending).rejects.toSatisfy((e: unknown) => {
        return e instanceof DataAccessError && e.kind === "timeout";
      });
      expect(pool.calls.length).toBe(1);
    } finally {
      vi.useRealTimers();
    }
  });

  it("cacheGet 对挂死的 redis 读按超时降级为 null（读路径永不挂死、不抛）", async () => {
    vi.useFakeTimers();
    try {
      const hangingKv: KvLike = {
        get: () => new Promise(() => {}),
        set: () => Promise.resolve("OK"),
        del: () => Promise.resolve(1),
      };
      const access = createDataAccess({ kv: hangingKv });
      const pending = access.cacheGet("k", { timeoutMs: 50 });
      vi.advanceTimersByTime(60);
      await expect(pending).resolves.toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });
});

describe("数据访问层·重试", () => {
  it("瞬时错误（ECONNRESET）重试后成功——三次尝试后拿到结果", async () => {
    vi.useFakeTimers();
    try {
      const pool = new FakePool([
        () => err("ECONNRESET"),
        () => err("ECONNRESET"),
        () => ({ rows: [{ id: 7 }] }),
      ]);
      const access = createDataAccess({ pool });
      const pending = access.query("SELECT id FROM t", [], { timeoutMs: 1000, retries: 2 });
      await vi.advanceTimersByTimeAsync(1000); // 两次退避（100+200ms）走完
      await expect(pending).resolves.toEqual([{ id: 7 }]);
      expect(pool.calls.length).toBe(3);
    } finally {
      vi.useRealTimers();
    }
  });

  it("非瞬时错误（唯一约束 23505）不重试——立即归一抛出", async () => {
    const pool = new FakePool([() => err("23505", "duplicate key")]);
    const access = createDataAccess({ pool });
    await expect(access.query("INSERT ...", [], { retries: 3 })).rejects.toSatisfy(
      (e: unknown) => e instanceof DataAccessError && e.kind === "pg" && e.code === "23505",
    );
    expect(pool.calls.length).toBe(1);
  });

  it("瞬时错误重试耗尽——kind 如实标 exhausted", async () => {
    vi.useFakeTimers();
    try {
      const pool = new FakePool([() => err("ECONNREFUSED")]);
      const access = createDataAccess({ pool });
      const pending = access.query("SELECT 1", [], { timeoutMs: 100, retries: 1 });
      void pending.catch(() => {}); // 先挂处置（advance 窗口内拒绝不算 unhandled）
      await vi.advanceTimersByTimeAsync(500);
      await expect(pending).rejects.toSatisfy(
        (e: unknown) => e instanceof DataAccessError && e.kind === "exhausted",
      );
      expect(pool.calls.length).toBe(2);
    } finally {
      vi.useRealTimers();
    }
  });
});

describe("数据访问层·缓存降级", () => {
  it("cacheGet 失败降级 null；cacheSet 失败降级 false——均不抛", async () => {
    const brokenKv: KvLike = {
      get: () => Promise.reject(err("ECONNREFUSED")),
      set: () => Promise.reject(err("ECONNREFUSED")),
      del: () => Promise.reject(err("ECONNREFUSED")),
    };
    const access = createDataAccess({ kv: brokenKv });
    await expect(access.cacheGet("k")).resolves.toBeNull();
    await expect(access.cacheSet("k", "v", 60)).resolves.toBe(false);
    await expect(access.cacheDelete("k")).resolves.toBe(false);
  });

  it("kv 正常时读写删直通（TTL 传达到位）", async () => {
    const kv = fakeKv();
    const access = createDataAccess({ kv });
    await expect(access.cacheSet("k", "v1", 60)).resolves.toBe(true);
    await expect(access.cacheGet("k")).resolves.toBe("v1");
    await expect(access.cacheDelete("k")).resolves.toBe(true);
    await expect(access.cacheGet("k")).resolves.toBeNull();
  });

  it("queryOne 返回首行或 null", async () => {
    const pool = new FakePool([() => ({ rows: [{ id: 1 }, { id: 2 }] }), () => ({ rows: [] })]);
    const access = createDataAccess({ pool });
    await expect(access.queryOne("SELECT ...")).resolves.toEqual({ id: 1 });
    await expect(access.queryOne("SELECT ...")).resolves.toBeNull();
  });
});

/** 事务假件：借出可脚本化 PoolClient，记 BEGIN/COMMIT/release 序列。 */
class FakeTxPool implements PgLike {
  readonly calls: string[] = [];
  private released: (Error | undefined)[] = [];
  private fn: (client: PoolClientLike) => Promise<unknown>;
  private readonly client: PoolClientLike;

  constructor(fn: (client: PoolClientLike) => Promise<unknown>) {
    this.fn = fn;
    const pool = this;
    this.client = {
      query(text: string): Promise<unknown> {
        pool.calls.push(text);
        return Promise.resolve({ rows: [] });
      },
      release(e?: Error) {
        pool.released.push(e);
      },
    };
  }

  query(): Promise<unknown> {
    return Promise.reject(new Error("事务测试不触池级 query"));
  }

  connect(): Promise<PoolClientLike> {
    this.calls.push("__connect__");
    return Promise.resolve(this.client);
  }

  /** 断言面：借出命令序与 release 携错态。 */
  get releaseArgs(): (Error | undefined)[] {
    return this.released;
  }
}

interface PoolClientLike {
  query(text: string): Promise<unknown>;
  release(e?: Error): void;
}

describe("数据访问层·事务", () => {
  /** 事务假池：借出记序客户端（query 记命令、release 记携错态）。 */
  function fakeTxPool() {
    const calls: string[] = [];
    const releaseArgs: (Error | undefined)[] = [];
    const client: TxClient = {
      query: (text: string) => {
        calls.push(text);
        return Promise.resolve({ rows: [] });
      },
      release: (e?: Error) => releaseArgs.push(e),
    };
    const pool: PgLike = {
      query: () => Promise.reject(new Error("事务测试不触池级 query")),
      connect: () => {
        calls.push("__connect__");
        return Promise.resolve(client);
      },
    };
    return { pool, calls, releaseArgs };
  }

  it("成功路径：BEGIN → 事务体 → COMMIT、连接无错归还、结果透传", async () => {
    const tx = fakeTxPool();
    const access = createDataAccess({ pool: tx.pool });
    const body: string[] = [];
    await expect(
      access.withTransaction(async (client) => {
        await client.query("UPDATE t SET v = 1");
        body.push("ran");
        return "done";
      }),
    ).resolves.toBe("done");
    expect(body).toEqual(["ran"]);
    expect(tx.calls).toEqual(["__connect__", "BEGIN", "UPDATE t SET v = 1", "COMMIT"]);
    expect(tx.releaseArgs).toEqual([undefined]);
  });

  it("事务体抛错：归一 DataAccessError(pg)、连接携错归还（不回池复用）", async () => {
    const tx = fakeTxPool();
    const access = createDataAccess({ pool: tx.pool });
    const boom = Object.assign(new Error("bad sql"), { code: "42601" });
    await expect(access.withTransaction(async () => Promise.reject(boom))).rejects.toSatisfy(
      (e: unknown) => e instanceof DataAccessError && e.kind === "pg" && e.code === "42601",
    );
    expect(tx.releaseArgs[0]).toBe(boom);
  });

  it("事务体挂死：按 timeoutMs 归一 timeout（不无限等待）", async () => {
    vi.useFakeTimers();
    try {
      const tx = fakeTxPool();
      const access = createDataAccess({ pool: tx.pool });
      const pending = access.withTransaction(() => new Promise<never>(() => {}), { timeoutMs: 50 });
      void pending.catch(() => {});
      // async 形态：计时器与微任务交错推进——connect/BEGIN 的微任务先落定，
      // 只留事务体的超时计时器真正起爆
      await vi.advanceTimersByTimeAsync(60);
      await expect(pending).rejects.toSatisfy(
        (e: unknown) => e instanceof DataAccessError && e.kind === "timeout",
      );
      expect(tx.releaseArgs.length).toBe(1); // 超时也归还连接（携错）
    } finally {
      vi.useRealTimers();
    }
  });
});
