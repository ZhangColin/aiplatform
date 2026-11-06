import { afterEach, describe, expect, it, vi } from "vitest";
import {
  SESSION_COOKIE,
  createSession,
  createSessionStore,
  destroySession,
  getSession,
  hashPassword,
  verifyPassword,
  type SessionKv,
} from "./session";

/**
 * next/headers cookies() 假件：vi.hoisted 持有可变 jar（vi.mock 提升到模块顶、
 * 工厂里引用 holder——每个用例换新 jar，模拟 route handler 可写语境）。
 */
const cookieJar = vi.hoisted(() => ({ api: undefined as CookieJarApi | undefined }));
vi.mock("next/headers", () => ({
  cookies: vi.fn(async () => {
    if (!cookieJar.api) {
      throw new Error("测试未装 cookie jar——先调 installCookieJar()");
    }
    return cookieJar.api;
  }),
}));

interface CookieJarApi {
  get(key: string): { name: string; value: string } | undefined;
  set(key: string, value: string, opts?: Record<string, unknown>): void;
  delete(key: string): void;
}

function installCookieJar() {
  const jar = new Map<string, { value: string; opts: Record<string, unknown> }>();
  cookieJar.api = {
    get: (key) => (jar.has(key) ? { name: key, value: jar.get(key)!.value } : undefined),
    set: (key, value, opts) => jar.set(key, { value, opts: opts ?? {} }),
    delete: (key) => jar.delete(key),
  };
  return jar;
}

afterEach(() => {
  cookieJar.api = undefined;
  vi.useRealTimers();
});

/** 内存 kv 假件。 */
function fakeKv(): SessionKv {
  const store = new Map<string, string>();
  return {
    get: async (k) => store.get(k) ?? null,
    set: async (k, v) => {
      store.set(k, v);
      return "OK";
    },
    del: async (k) => {
      store.delete(k);
      return 1;
    },
  };
}

const brokenKv = (): SessionKv => ({
  get: () => Promise.reject(new Error("ECONNREFUSED")),
  set: () => Promise.reject(new Error("ECONNREFUSED")),
  del: () => Promise.reject(new Error("ECONNREFUSED")),
});

describe("会话存储·机制层（redis 存取带降级）", () => {
  it("set/get/delete 往返：载荷 JSON 存取、key 带会话键前缀、TTL 传达到 kv", async () => {
    const kv = fakeKv();
    const seen: unknown[][] = [];
    const recordingKv: SessionKv = {
      get: kv.get,
      set: (k, v, ttl) => {
        seen.push([k, ttl]);
        return kv.set(k, v, ttl);
      },
      del: kv.del,
    };
    const store = createSessionStore(recordingKv);
    await expect(store.set("abc", { userId: 7 }, 3600)).resolves.toBe(true);
    await expect(store.get<{ userId: number }>("abc")).resolves.toEqual({ userId: 7 });
    await expect(store.delete("abc")).resolves.toBe(true);
    expect(seen).toContainEqual(["sess:abc", 3600]);
  });

  it("redis 读失败降级 null、写删降级 false（不抛——锚案例挂死/炸页面面收口）", async () => {
    const store = createSessionStore(brokenKv());
    await expect(store.get("abc")).resolves.toBeNull();
    await expect(store.set("abc", { userId: 1 }, 60)).resolves.toBe(false);
    await expect(store.delete("abc")).resolves.toBe(false);
  });

  it("载荷损坏（坏 JSON）按不存在处理——null 不炸", async () => {
    const kv: SessionKv = {
      get: async () => "{not json",
      set: async () => "OK",
      del: async () => 1,
    };
    await expect(createSessionStore(kv).get("abc")).resolves.toBeNull();
  });
});

describe("会话存储·Next 绑定层（cookie 薄层）", () => {
  it("createSession：cookie 落 httpOnly＋sameSite＋maxAge，载荷进存储，返回 true", async () => {
    const kv = fakeKv();
    const jar = installCookieJar();
    const ok = await createSession({ userId: 42 }, { kv });
    expect(ok).toBe(true);
    const cookie = jar.get(SESSION_COOKIE);
    expect(cookie).toBeTruthy();
    expect(cookie!.opts).toMatchObject({ httpOnly: true, sameSite: "lax", path: "/" });
    expect(typeof cookie!.opts.maxAge).toBe("number");
    expect(await kv.get(`sess:${cookie!.value}`)).toContain("42");
  });

  it("存储失败降级：createSession 返回 false、cookie 不落（不半真会话）", async () => {
    const jar = installCookieJar();
    const ok = await createSession({ userId: 42 }, { kv: brokenKv() });
    expect(ok).toBe(false);
    expect(jar.size).toBe(0);
  });

  it("getSession：cookie＋存储齐全返回载荷；无 cookie/存储空均为 null", async () => {
    const kv = fakeKv();
    const jar = installCookieJar();
    await createSession({ userId: 42 }, { kv });
    await expect(getSession<{ userId: number }>({ kv })).resolves.toEqual({ userId: 42 });
    // 无 cookie
    cookieJar.api!.delete(SESSION_COOKIE);
    await expect(getSession({ kv })).resolves.toBeNull();
    // cookie 在但存储空（过期/被清）
    cookieJar.api!.set(SESSION_COOKIE, "ghost", {});
    await expect(getSession({ kv })).resolves.toBeNull();
    void jar;
  });

  it("getSession 存储降级（redis 挂）返回 null——登录受保护页照常判未登录，不挂死", async () => {
    installCookieJar();
    cookieJar.api!.set(SESSION_COOKIE, "sid123", {});
    await expect(getSession({ kv: brokenKv() })).resolves.toBeNull();
  });

  it("destroySession：cookie 清除＋存储删除；存储失败不炸", async () => {
    const kv = fakeKv();
    const jar = installCookieJar();
    await createSession({ userId: 1 }, { kv });
    await expect(destroySession({ kv })).resolves.toBeUndefined();
    expect(jar.size).toBe(0);
    await expect(destroySession({ kv: brokenKv() })).resolves.toBeUndefined();
  });
});

describe("密码哈希（scrypt）", () => {
  it("hash→verify 往返；错密码 false；存量串不含明文", async () => {
    const stored = await hashPassword("s3cret-密码");
    expect(stored).not.toContain("s3cret-密码");
    expect(stored.startsWith("scrypt$")).toBe(true);
    await expect(verifyPassword("s3cret-密码", stored)).resolves.toBe(true);
    await expect(verifyPassword("wrong", stored)).resolves.toBe(false);
  });

  it("同密码两次哈希不同（盐随机）且都能验过", async () => {
    const a = await hashPassword("same");
    const b = await hashPassword("same");
    expect(a).not.toBe(b);
    await expect(verifyPassword("same", a)).resolves.toBe(true);
    await expect(verifyPassword("same", b)).resolves.toBe(true);
  });

  it("损坏的存量串安全失败 false（不抛）", async () => {
    await expect(verifyPassword("x", "not-a-valid-hash")).resolves.toBe(false);
    await expect(verifyPassword("x", "scrypt$oops")).resolves.toBe(false);
  });
});
