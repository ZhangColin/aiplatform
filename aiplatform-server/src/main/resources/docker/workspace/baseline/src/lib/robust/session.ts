import { randomBytes, scrypt, timingSafeEqual } from "node:crypto";
import { cookies } from "next/headers";
import { cacheDelete, cacheGet, cacheSet } from "./data-access";

/**
 * 会话存储件（基座健壮件·#421）：cookie 管理＋redis 会话存取（超时降级）＋
 * 密码哈希（scrypt）。机制/呈现分离——本件只担行为契约：
 *
 * - 会话读路径（getSession）永降级不挂死：cookie 缺失/存储超时/存储故障一律
 *   null——受保护页面照常按「未登录」走，锚案例的 redis 挂死面收口。
 * - 会话写路径降级不半真：存储失败时 createSession 返回 false 且不落 cookie
 *   （不留「有 cookie 无会话」的半真态）。
 * - 密码：scrypt（node:crypto 内建、参数随存量串走）＋timingSafeEqual 常时比较。
 *
 * 边界（永留执行体）：登录页面/登录流程/角色模型是功能域——本件只供
 * createSession/getSession/destroySession/hashPassword/verifyPassword 原语。
 *
 * 超时由 data-access 缓存面统一担保（cacheGet/cacheSet 带 race 计时器）；本件
 * 在其上再收拢「降级语义」。行为契约测试见同目录 session.test.ts。
 */

/** 会话键值面（与 data-access KvLike 同形；测试注入口）。 */
export interface SessionKv {
  get(key: string): Promise<string | null>;
  set(key: string, value: string, ttlSeconds?: number): Promise<unknown>;
  del(key: string): Promise<unknown>;
}

/** 会话 cookie 名（执行体可用默认；改名走 opts 面，少见）。 */
export const SESSION_COOKIE = "sid";

/** 会话缺省有效期（秒）：7 天。 */
export const DEFAULT_SESSION_TTL_SECONDS = 7 * 24 * 60 * 60;

const sessionKey = (sessionId: string) => `sess:${sessionId}`;

export interface SessionStore {
  /** 存：载荷 JSON 序列化入 kv（带 TTL）；降级 false。 */
  set(sessionId: string, data: unknown, ttlSeconds?: number): Promise<boolean>;
  /** 取：JSON 反解；不存在/损坏/故障一律 null。 */
  get<T = unknown>(sessionId: string): Promise<T | null>;
  /** 删：降级 false。 */
  delete(sessionId: string): Promise<boolean>;
}

/**
 * 机制层工厂（测试注入口）：kv 缺省走 data-access 缓存面（redis＋超时＋降级）。
 */
export function createSessionStore(kv?: SessionKv): SessionStore {
  const client: SessionKv = kv ?? {
    get: (key) => cacheGet(key),
    set: (key, value, ttlSeconds) => cacheSet(key, value, ttlSeconds),
    del: (key) => cacheDelete(key),
  };
  return {
    set: async (sessionId, data, ttlSeconds) => {
      try {
        return (await client.set(sessionKey(sessionId), JSON.stringify(data), ttlSeconds)) !== false;
      } catch {
        return false;
      }
    },
    get: async <T>(sessionId: string): Promise<T | null> => {
      try {
        const raw = await client.get(sessionKey(sessionId));
        return raw === null ? null : (JSON.parse(raw) as T);
      } catch {
        return null; // 坏 JSON 同按不存在（会话脏数据不当故障炸页面）
      }
    },
    delete: async (sessionId) => {
      try {
        await client.del(sessionKey(sessionId));
        return true;
      } catch {
        return false;
      }
    },
  };
}

export interface SessionBindingOptions {
  /** 存储注入（测试用）；缺省 data-access 缓存面。 */
  kv?: SessionKv;
  /** 会话 TTL 秒（缺省 7 天）。 */
  ttlSeconds?: number;
  /** cookie 名（缺省 sid）。 */
  cookieName?: string;
}

/**
 * 建会话（route handler / server action 语境——cookies() 可写面）：生成会话 id、
 * 载荷入存储、httpOnly cookie 落位。存储降级时返回 false 且不落 cookie。
 */
export async function createSession(
  data: unknown,
  opts: SessionBindingOptions = {},
): Promise<boolean> {
  const store = createSessionStore(opts.kv);
  const sessionId = randomBytes(24).toString("base64url");
  const ttl = opts.ttlSeconds ?? DEFAULT_SESSION_TTL_SECONDS;
  const stored = await store.set(sessionId, data, ttl);
  if (!stored) {
    return false;
  }
  const jar = await cookies();
  jar.set(opts.cookieName ?? SESSION_COOKIE, sessionId, {
    httpOnly: true,
    sameSite: "lax",
    path: "/",
    maxAge: ttl,
    secure: process.env.NODE_ENV === "production",
  });
  return true;
}

/** 读会话（server component / route handler 皆可）：缺 cookie/空/降级一律 null。 */
export async function getSession<T = unknown>(
  opts: SessionBindingOptions = {},
): Promise<T | null> {
  const jar = await cookies();
  const sessionId = jar.get(opts.cookieName ?? SESSION_COOKIE)?.value;
  if (!sessionId) {
    return null;
  }
  return createSessionStore(opts.kv).get<T>(sessionId);
}

/** 销毁会话：cookie 清除＋存储删除（存储失败静默——cookie 已清即登出成立）。 */
export async function destroySession(opts: SessionBindingOptions = {}): Promise<void> {
  const jar = await cookies();
  const name = opts.cookieName ?? SESSION_COOKIE;
  const sessionId = jar.get(name)?.value;
  jar.delete(name);
  if (sessionId) {
    await createSessionStore(opts.kv).delete(sessionId);
  }
}

// ---------- 密码哈希（scrypt） ----------

const SCRYPT_N = 16_384;
const SCRYPT_r = 8;
const SCRYPT_p = 1;
const SCRYPT_KEYLEN = 64;

function scryptAsync(password: string, salt: Buffer, N: number, r: number, p: number): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    scrypt(password, salt, SCRYPT_KEYLEN, { N, r, p }, (err, derived) => {
      if (err) {
        reject(err);
      } else {
        resolve(derived);
      }
    });
  });
}

/**
 * 密码哈希：scrypt 随机盐，存量串自述参数（scrypt$N$r$p$salt$hash，base64）——
 * 升参不影响既有存量验签。绝不可逆存明文/可逆加密。
 */
export async function hashPassword(password: string): Promise<string> {
  const salt = randomBytes(16);
  const hash = await scryptAsync(password, salt, SCRYPT_N, SCRYPT_r, SCRYPT_p);
  return ["scrypt", SCRYPT_N, SCRYPT_r, SCRYPT_p, salt.toString("base64"), hash.toString("base64")]
    .join("$");
}

/** 验密码：按存量串自述参数重导＋timingSafeEqual 常时比较；坏串 false 不抛。 */
export async function verifyPassword(password: string, stored: string): Promise<boolean> {
  const parts = stored.split("$");
  if (parts.length !== 6 || parts[0] !== "scrypt") {
    return false;
  }
  const [_, nStr, rStr, pStr, saltB64, hashB64] = parts;
  const N = Number(nStr);
  const r = Number(rStr);
  const p = Number(pStr);
  const salt = Buffer.from(saltB64, "base64");
  const expected = Buffer.from(hashB64, "base64");
  if (!Number.isInteger(N) || !Number.isInteger(r) || !Number.isInteger(p)
    || N <= 0 || r <= 0 || p <= 0 || salt.length === 0 || expected.length === 0) {
    return false;
  }
  try {
    const derived = await scryptAsync(password, salt, N, r, p);
    return derived.length === expected.length && timingSafeEqual(derived, expected);
  } catch {
    return false;
  }
}
