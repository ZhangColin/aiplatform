"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";

import { ApiError } from "@/lib/api/api-error";

function makeQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        // 只重试网络错误与 5xx；4xx（含 401）不重试。ADR 0002
        retry: (_failureCount, error) => {
          if (error instanceof ApiError) return error.status >= 500;
          if (error instanceof DOMException && error.name === "AbortError") return false;
          return true; // 网络错误（fetch 抛出，非 ApiError）
        },
      },
      // mutation 不自动重试，失败后由显式重试按钮承接。ADR 0002
      mutations: { retry: false },
    },
  });
}

export function QueryProvider({ children }: { children: ReactNode }) {
  // 每次挂载建独立实例，避免 SSR 请求间共享缓存
  const [queryClient] = useState(makeQueryClient);

  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}
