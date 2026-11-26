// @vitest-environment happy-dom
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { useRollbackVersion } from "@/hooks/use-version";
import { api } from "@/lib/api/client";
import { useVersionRecordStore } from "@/lib/store/version-record";

/**
 * 回到某版 hook（#627 异步受理）的行为面：受理成功即收起时间线抽屉（进度回
 * 主视图看工作消息）、同步拒绝不收起（错误走 toast）。经 api client 桩驱动真实
 * mutation（hook 组合的公共面）。
 */

vi.mock("@/lib/api/client", () => ({
  api: {
    post: vi.fn(),
  },
}));

beforeEach(() => {
  vi.mocked(api.post).mockReset();
  useVersionRecordStore.setState({ open: false });
});

afterEach(cleanup);

/** 回到某版探针：动作挂按钮（hook 的公共面＝mutate）。 */
function RollbackProbe() {
  const rollback = useRollbackVersion("p1");
  return (
    <button type="button" onClick={() => rollback.mutate("abc123")}>
      回到此版
    </button>
  );
}

describe("useRollbackVersion · 回到某版（#627 异步受理）", () => {
  it("受理成功：POST rollback 并收起时间线抽屉（进度回主视图看工作消息）", async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ runId: "run-1", label: "回到 v1" } as never);
    useVersionRecordStore.setState({ open: true });
    render(
      <QueryClientProvider client={new QueryClient()}>
        <RollbackProbe />
      </QueryClientProvider>,
    );

    fireEvent.click(screen.getByText("回到此版"));

    await waitFor(() => expect(useVersionRecordStore.getState().open).toBe(false));
    expect(api.post).toHaveBeenCalledWith("/projects/p1/versions/abc123/rollback");
  });

  it("同步拒绝（在途/不存在）：抽屉不收起（错误由 toast 呈现）", async () => {
    vi.mocked(api.post).mockRejectedValueOnce(new Error("409"));
    useVersionRecordStore.setState({ open: true });
    render(
      <QueryClientProvider client={new QueryClient()}>
        <RollbackProbe />
      </QueryClientProvider>,
    );

    fireEvent.click(screen.getByText("回到此版"));

    await waitFor(() => expect(api.post).toHaveBeenCalled());
    expect(useVersionRecordStore.getState().open).toBe(true);
  });
});
