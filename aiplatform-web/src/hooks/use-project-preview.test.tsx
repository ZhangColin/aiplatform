// @vitest-environment happy-dom
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { ReactNode } from "react";

import { queryKeys } from "@/lib/api/keys";
import { useSseStatusStore } from "@/lib/store/sse-status";

import { useProjectPreview } from "./use-project-preview";

/**
 * 轮询降级（#105 URL 事件驱动）：useProjectPreview 不再无条件 3s 轮询——URL 由
 * 切片收口的 preview-ready 事件推送（bridge setQueryData 写预览查询缓存），轮询
 * 降级为 SSE 断线兜底。断言 query 的 refetchInterval 选项：连接健康 → false
 * （不空转、等事件推 URL）、断线 → 15s 门控兜底（重拉补齐错过的 URL）。
 */

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
