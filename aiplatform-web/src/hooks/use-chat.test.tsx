// @vitest-environment happy-dom
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { useSaveStateStore } from "@/lib/store/save-state";

import { useAnswerQuestion, usePostMessage } from "./use-chat";

/**
 * 保存胶囊三态接真（B2 验收闭合）：发消息/作答是项目内的持久化动作——
 * onMutate 置「保存中」、成功回「已保存」、失败置「保存失败」。
 */

const post = vi.hoisted(() => vi.fn());

vi.mock("@/lib/api/client", () => ({
  api: { post: (...args: unknown[]) => post(...args) },
}));
vi.mock("sonner", () => ({ toast: { error: vi.fn(), success: vi.fn() } }));

function withQueryClient() {
  const queryClient = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
  };
}

beforeEach(() => {
  post.mockReset();
  useSaveStateStore.setState({ state: "saved" });
});

describe("usePostMessage · 保存状态三态（B2）", () => {
  it("提交即保存中，成功后回已保存", async () => {
    let resolve!: (value: unknown) => void;
    post.mockReturnValue(new Promise((res) => (resolve = res)));

    const { result } = renderHook(() => usePostMessage("p1"), {
      wrapper: withQueryClient(),
    });

    act(() => {
      result.current.mutate({ content: "做一个预约系统" });
    });
    expect(useSaveStateStore.getState().state).toBe("saving");

    await act(async () => {
      resolve({ runId: "r1" });
    });

    await waitFor(() => expect(useSaveStateStore.getState().state).toBe("saved"));
  });

  it("提交失败 → 保存失败（胶囊自带「请重试」）", async () => {
    post.mockRejectedValue(new Error("boom"));

    const { result } = renderHook(() => usePostMessage("p1"), {
      wrapper: withQueryClient(),
    });

    act(() => {
      result.current.mutate({ content: "做一个预约系统" });
    });

    await waitFor(() => expect(useSaveStateStore.getState().state).toBe("failed"));
  });
});

describe("useAnswerQuestion · 保存状态三态（B2）", () => {
  it("作答成功同样回已保存", async () => {
    post.mockResolvedValue(undefined);

    const { result } = renderHook(() => useAnswerQuestion("p1"), {
      wrapper: withQueryClient(),
    });

    act(() => {
      result.current.mutate({
        qid: "q1",
        command: { answer: "是", runId: "r1", toolCalls: [] },
      });
    });
    await waitFor(() => expect(useSaveStateStore.getState().state).toBe("saved"));
  });
});
