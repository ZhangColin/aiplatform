// @vitest-environment happy-dom
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { M01Dialog } from "@/components/auth/m01-dialog";
import { SseProvider } from "@/lib/sse/provider";
import { useM01Store } from "@/lib/store/m01";

import { HomeHeader } from "./home-header";
import { HomeHero } from "./home-hero";

/**
 * A1 回归（匿名首访）：无凭证 cookie 的首页「只逛零足迹」——最近项目/会话身份
 * 背景查询不发请求（无 GET /api/projects），SSE 会话探活的 401 静默、不弹 M01
 * 登录框。凭证存在性由首页 Server Component 在 cookie 面判定后下传
 *（credentialPresent=false 即完全匿名）。
 */

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn() }),
  usePathname: () => "/",
}));

const fetchMock = vi.hoisted(() => vi.fn());

beforeEach(() => {
  fetchMock.mockReset();
  fetchMock.mockImplementation((input: string | URL | Request) => {
    const url = String(input);
    // SSE provider 的会话探活：匿名 401（silentUnauthorized——不发全局出口事件）
    const status = url.includes("/api/me") ? 401 : 200;
    return Promise.resolve(
      new Response(JSON.stringify(url.includes("/api/me") ? {} : { data: [] }), {
        status,
        headers: { "Content-Type": "application/json" },
      }),
    );
  });
  vi.stubGlobal("fetch", fetchMock);
  useM01Store.setState({ open: false, trigger: null });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function renderAnonymousHome() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <SseProvider>
        <HomeHeader credentialPresent={false} />
        <HomeHero credentialPresent={false} />
      </SseProvider>
      <M01Dialog />
    </QueryClientProvider>,
  );
}

describe("首页匿名首访（A1）", () => {
  it("不弹 M01、不发最近项目背景查询；头像呈现「登录」入口", async () => {
    renderAnonymousHome();

    // 等 SSE 探活（唯一的背景请求）落定
    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    await act(async () => {});

    const urls = fetchMock.mock.calls.map((call) => String(call[0]));
    expect(urls.some((url) => url.includes("/api/projects"))).toBe(false);
    expect(useM01Store.getState().open).toBe(false);
    expect(screen.queryByText("登录保存项目")).toBeNull();
    expect(screen.getAllByText("登录").length).toBeGreaterThan(0);
  });
});
