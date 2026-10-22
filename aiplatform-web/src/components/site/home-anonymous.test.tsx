// @vitest-environment happy-dom
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { M01Dialog } from "@/components/auth/m01-dialog";
import { queryKeys } from "@/lib/api/keys";
import { SseProvider } from "@/lib/sse/provider";
import { useM01Store } from "@/lib/store/m01";

import { HomeHeader } from "./home-header";
import { HomeHero } from "./home-hero";

/**
 * A1 回归（匿名首访）：无凭证 cookie 的首页「只逛零足迹」——最近项目/会话身份
 * 背景查询不发请求（无全量列表 GET /api/projects），SSE 会话探活（/api/projects
 * ?page=1&size=1，静默）的 401 不弹 M01 登录框。凭证存在性由首页 Server
 * Component 在 cookie 面判定后下传（credentialPresent=false 即完全匿名）。
 *
 * A1b 回归（访客持凭证）：访客建项目后 cookie 面有凭证——/me 是账户端点、按
 * 设计对访客 401（「未登录」是答案不是故障），useMe 探针静默化后不得因该 401
 * 弹「登录保存项目」；头像位照常呈现「登录」入口（刷新/切页弹窗缺陷的回归锚）。
 */

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn() }),
  usePathname: () => "/",
}));

const fetchMock = vi.hoisted(() => vi.fn());

/** EventSource 桩：A1b 探针 200 时 SseProvider 会建连，不依赖 happy-dom 实现。 */
class FakeEventSource {
  static instances: FakeEventSource[] = [];
  readyState = 0;
  constructor(public url: string) {
    FakeEventSource.instances.push(this);
  }
  addEventListener() {}
  close() {
    this.readyState = 2;
  }
}

/** 按 URL 路由的 fetch 桩：meProbe/me 401（账户端点拒访客）、其余可配。 */
function stubFetch({ probeStatus = 401, listStatus = 200 }: { probeStatus?: number; listStatus?: number } = {}) {
  fetchMock.mockReset();
  fetchMock.mockImplementation((input: string | URL | Request) => {
    const url = String(input);
    let status = listStatus;
    if (url.includes("/api/me")) status = 401; // 访客/匿名：账户端点恒 401
    if (url.includes("page=1&size=1")) status = probeStatus; // SSE 会话探针
    return Promise.resolve(
      new Response(JSON.stringify({ data: [] }), {
        status,
        headers: { "Content-Type": "application/json" },
      }),
    );
  });
  vi.stubGlobal("fetch", fetchMock);
}

beforeEach(() => {
  FakeEventSource.instances = [];
  vi.stubGlobal("EventSource", FakeEventSource);
  useM01Store.setState({ open: false, trigger: null });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function renderHome(credentialPresent: boolean, queryClient: QueryClient) {
  return render(
    <QueryClientProvider client={queryClient}>
      <SseProvider>
        <HomeHeader credentialPresent={credentialPresent} />
        <HomeHero credentialPresent={credentialPresent} />
      </SseProvider>
      <M01Dialog />
    </QueryClientProvider>,
  );
}

describe("首页匿名首访（A1）", () => {
  beforeEach(() => {
    // 完全匿名：探针（唯一预期请求）吃 401，静默停手
    stubFetch({ probeStatus: 401 });
  });

  it("不弹 M01、不发最近项目背景查询；头像呈现「登录」入口", async () => {
    renderHome(false, new QueryClient({ defaultOptions: { queries: { retry: false } } }));

    // 等 SSE 探活（唯一的背景请求）落定
    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    await act(async () => {});

    const urls = fetchMock.mock.calls.map((call) => String(call[0]));
    // 全量列表查询（无参 /api/projects）不发；探针（?page=1&size=1）可发且静默
    expect(urls.some((url) => url.includes("/api/projects") && !url.includes("page=1&size=1"))).toBe(false);
    expect(useM01Store.getState().open).toBe(false);
    expect(screen.queryByText("登录保存项目")).toBeNull();
    expect(screen.getAllByText("登录").length).toBeGreaterThan(0);
  });
});

describe("首页访客持凭证（A1b：/me 401 不弹 M01）", () => {
  beforeEach(() => {
    // 访客在场：探针 200（账户或访客任一即活）、列表 200、/me 恒 401
    stubFetch({ probeStatus: 200, listStatus: 200 });
  });

  it("/me 401 是「未登录」答案：不弹「登录保存项目」、头像呈现「登录」入口", async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    renderHome(true, queryClient);

    // 等 /me 查询落定（「从没发生」类断言不能抢跑：401 → ApiError → 全局事件在
    // 查询拒绝面世前同步跑完，状态落 error 即事件链已走完）
    await waitFor(() =>
      expect(queryClient.getQueryState(queryKeys.me)?.fetchStatus).toBe("idle"),
    );
    await act(async () => {});

    // 回归锚：useMe 静默化前，这里的 401 会发全局事件 → M01 开门（刷新/切页必弹）
    expect(queryClient.getQueryState(queryKeys.me)?.status).toBe("error");
    expect(useM01Store.getState().open).toBe(false);
    expect(screen.queryByText("登录保存项目")).toBeNull();
    expect(screen.getAllByText("登录").length).toBeGreaterThan(0);
  });
});
