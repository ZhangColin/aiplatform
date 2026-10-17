// @vitest-environment happy-dom
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { useRecentProjects } from "./use-projects";

/**
 * 最近项目查询的匿名守卫（A1 回归）：首页在无凭证 cookie 时以 enabled=false
 * 挂查询——「只逛零足迹」不发 GET /api/projects；有凭证（访客/账号）照常拉。
 */

const get = vi.hoisted(() => vi.fn());

vi.mock("@/lib/api/client", () => ({
  api: { get: (...args: unknown[]) => get(...args) },
}));

function withQueryClient() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
  };
}

afterEach(() => {
  get.mockReset();
});

describe("useRecentProjects · 匿名守卫（A1）", () => {
  it("enabled=false（匿名首访）：不发列表请求，返回空集", () => {
    const { result } = renderHook(() => useRecentProjects(false), {
      wrapper: withQueryClient(),
    });

    expect(result.current).toEqual([]);
    expect(get).not.toHaveBeenCalled();
  });

  it("enabled=true（有凭证）：照常拉列表并过滤已归档", async () => {
    get.mockResolvedValue([
      { id: "p1", name: "进行中", archived: false },
      { id: "p2", name: "已归档", archived: true },
    ]);

    const { result } = renderHook(() => useRecentProjects(true), {
      wrapper: withQueryClient(),
    });

    await waitFor(() => expect(result.current).toHaveLength(1));
    expect(result.current[0]?.id).toBe("p1");
    expect(get).toHaveBeenCalledWith("/projects", expect.anything());
  });
});
