// @vitest-environment happy-dom
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { ReactNode } from "react";

import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import { useSseStatusStore } from "@/lib/store/sse-status";

import { useProjectPreview } from "./use-project-preview";

/**
 * 轮询降级（#105 URL 事件驱动）：useProjectPreview 不再无条件 3s 轮询——URL 由
 * 切片收口的 preview-ready 事件推送（bridge setQueryData 写预览查询缓存），轮询
 * 降级为 SSE 断线兜底。断言 query 的 refetchInterval 选项：连接健康 → false
 * （不空转、等事件推 URL）、断线 → 15s 门控兜底（重拉补齐错过的 URL）。
 */

// refetch 触发的请求断言（#182 三入口触碰先行）用可摆 transport
vi.mock("@/lib/api/client", () => ({
  api: { get: vi.fn().mockResolvedValue({ url: "http://localhost:42659" }) },
}));

function withQueryClient(queryClient: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
  };
}

afterEach(() => {
  useSseStatusStore.setState({ statuses: { notification: "offline", agent: "offline" } });
});

describe("useProjectPreview · 轮询降级（#105）", () => {
  it("SSE 连接健康：refetchInterval 关（不再无条件 3s 轮询，靠 preview-ready 推 URL）", () => {
    useSseStatusStore.getState().setStatus("notification", "connected");
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    renderHook(() => useProjectPreview("p1", /* active= */ false), {
      wrapper: withQueryClient(queryClient),
    });

    const observer = queryClient.getQueryCache().find({
      queryKey: queryKeys.projects.preview("p1"),
    })?.observers[0];
    expect(observer?.options.refetchInterval).toBe(false);
  });

  it("SSE 断线：refetchInterval 门控兜底（15s 重拉拿 URL）", () => {
    useSseStatusStore.getState().setStatus("notification", "offline");
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    renderHook(() => useProjectPreview("p1", /* active= */ false), {
      wrapper: withQueryClient(queryClient),
    });

    const observer = queryClient.getQueryCache().find({
      queryKey: queryKeys.projects.preview("p1"),
    })?.observers[0];
    expect(observer?.options.refetchInterval).toBe(15_000);
  });
});

describe("useProjectPreview · refetch 触发的请求（#182 三入口触碰先行）", () => {
  it("refetch() → GET /projects/{id}/preview——三入口触碰先行借力的同一条请求（后端触碰拦截器拨 last-touch 异步唤醒）", async () => {
    useSseStatusStore.getState().setStatus("notification", "connected");
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const getMock = vi.mocked(api.get);

    const { result } = renderHook(() => useProjectPreview("p1", /* active= */ true), {
      wrapper: withQueryClient(queryClient),
    });

    // 组件层三入口（刷新/goto/新窗口）调的就是这个 refetch——请求路径即触碰面；
    // 断言走缓存面（refetch promise 落定即缓存已写，renderHook 快照在 happy-dom
    // 下不随异步解析更新，不走 result.current.data——同 bridge.test 的 QueryObserver 口径）
    await result.current.refetch();

    expect(getMock).toHaveBeenCalledWith(
      "/projects/p1/preview",
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    );
    expect(queryClient.getQueryData(queryKeys.projects.preview("p1"))).toEqual({
      url: "http://localhost:42659",
    });
  });
});
