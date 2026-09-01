import { NextRequest } from "next/server";
import { describe, expect, it } from "vitest";

import { config, proxy } from "./proxy";

function request(path: string, cookie?: string) {
  return new NextRequest(`http://localhost:3333${path}`, {
    headers: cookie === undefined ? undefined : { cookie },
  });
}

/** matcher 字符串 "/((?!…).*)" 还原为路径判定正则：true = 进 proxy。 */
const guarded = new RegExp(`^${config.matcher[0]}`);

describe("proxy · 白名单（matcher）", () => {
  it.each([
    // 业务路由一律进守卫（含根路径：注销落 / 需弹回登录页）
    ["/", true],
    ["/projects", true],
    // 被删旧路由须进 proxy 才能吃到 302 回 /（issue #17）
    ["/dev", true],
    ["/dev/projects/p1", true],
    ["/opc/todos", true],
    ["/admin", true],
    ["/prototype/user-portal", true],
    // 相似前缀不误伤：apifoo / authz / devx 都不是白名单
    ["/apifoo", true],
    ["/authz", true],
    ["/devx", true],
    // spec 0004 §4 白名单：/auth /api /_next favicon.ico 静态资源
    ["/auth/login", false],
    ["/auth/callback", false],
    ["/api/me", false],
    ["/_next/static/chunk.js", false],
    ["/_next/image", false],
    ["/favicon.ico", false],
    ["/next.svg", false],
    ["/logo.webp", false],
  ])("%s → %s", (path, expected) => {
    expect(guarded.test(path)).toBe(expected);
  });
});

describe("proxy · 被删路由 302 回 /（issue #17 前端清场）", () => {
  it.each([
    ["/dev"],
    ["/dev/projects/p1"],
    ["/opc"],
    ["/opc/tasks/t9"],
    ["/admin"],
    ["/prototype/workbench"],
    ["/prototype"],
  ])("%s → 302 /（不带 returnTo）", (path) => {
    const res = proxy(request(path));
    expect(res.status).toBe(302);
    expect(res.headers.get("location")).toBe("http://localhost:3333/");
  });

  it("登录态同样 302（旧书签不进壳态页面，直接回首页）", () => {
    const res = proxy(request("/dev", "aiplatform_session=opaque-id"));
    expect(res.status).toBe(302);
    expect(res.headers.get("location")).toBe("http://localhost:3333/");
  });

  it("相似前缀不误伤：/developer /device 不是被删路由", () => {
    expect(proxy(request("/developer")).headers.get("location")).not.toBe(
      "http://localhost:3333/",
    );
    expect(proxy(request("/devices/x")).headers.get("location")).not.toBe(
      "http://localhost:3333/",
    );
  });
});

describe("proxy · 会话判定", () => {
  it("无 cookie 访问业务路由 → 302 /auth/login 且 returnTo 编码全路径", () => {
    const res = proxy(request("/projects/p123"));

    expect(res.status).toBe(302);
    expect(res.headers.get("location")).toBe(
      "http://localhost:3333/auth/login?returnTo=%2Fprojects%2Fp123",
    );
  });

  it("returnTo 携带 search（深在项目页过期不丢位置，spec 0004 §2）", () => {
    const res = proxy(request("/projects/p123?tab=logs&x=1"));

    expect(res.headers.get("location")).toBe(
      "http://localhost:3333/auth/login?returnTo=%2Fprojects%2Fp123%3Ftab%3Dlogs%26x%3D1",
    );
  });

  it("无 cookie 访问根路径 → 302，returnTo=%2F", () => {
    const res = proxy(request("/"));

    expect(res.status).toBe(302);
    expect(res.headers.get("location")).toBe(
      "http://localhost:3333/auth/login?returnTo=%2F",
    );
  });

  it("有 aiplatform_session cookie → 放行（只验存在性，spec 0004 §4）", () => {
    const res = proxy(request("/projects/p123", "aiplatform_session=opaque-id"));

    expect(res.status).toBe(200);
    expect(res.headers.get("location")).toBeNull();
  });

  it("有其它 cookie 但无 aiplatform_session → 仍 302", () => {
    const res = proxy(request("/projects/p123", "theme=dark; other=1"));

    expect(res.status).toBe(302);
  });
});
