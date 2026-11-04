// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from "vitest";

import type { paths } from "@/lib/api/schema";

import { sendCode, verifyCode } from "./code-login";

// 验证码登录的路径契约（#381「获取验证码」404 根因）：/auth 族端点在后端注册为
// 裸前缀（CodeLoginController，有意落在 /api/** 拦截面外——登录端点未登录可调，
// ApiAuthInterceptor 白名单），Next rewrite 第二条 /auth/:path* 原样转发。薄
// client 缺省会拼 /api 前缀——拼上即契约外路径：拦截面按访客凭证放行后无路由
// 可匹配，404 "Resource not found"。此测试锁 fetch 出门 URL 必须是裸路径。

// 编译期契约锚：后端 swagger（gen:api 正本）里必须存在这两条路径——契约侧删改
// 时 typecheck 即红，防止前后端路径漂移。
const CODE_PATH: keyof paths = "/auth/code";
const VERIFY_PATH: keyof paths = "/auth/verify";

function lastFetchUrl(): string {
  const calls = vi.mocked(fetch).mock.calls;
  return String(calls[calls.length - 1][0]);
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("code-login：裸路径契约（/auth 族不拼 /api 前缀）", () => {
  it("sendCode 发 POST {origin}/auth/code——拼 /api 前缀会被 /api/** 拦截面放行后 404", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ data: { expiresInSeconds: 300 } }), { status: 200 }),
    );
    vi.stubGlobal("fetch", fetchMock);

    await expect(sendCode("user@example.com")).resolves.toEqual({ expiresInSeconds: 300 });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(lastFetchUrl()).toBe(`${window.location.origin}${CODE_PATH}`);
    expect(String(fetchMock.mock.calls[0][0])).not.toContain("/api/auth");
  });

  it("verifyCode 发 POST {origin}/auth/verify", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ data: { accountId: "1", displayName: "张三" } }), {
        status: 200,
      }),
    );
    vi.stubGlobal("fetch", fetchMock);

    await expect(verifyCode("user@example.com", "123456")).resolves.toMatchObject({
      accountId: "1",
    });

    expect(lastFetchUrl()).toBe(`${window.location.origin}${VERIFY_PATH}`);
  });
});
