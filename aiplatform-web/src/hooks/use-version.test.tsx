// @vitest-environment happy-dom
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { useVersionViewSession } from "@/hooks/use-version";
import { api } from "@/lib/api/client";

/**
 * 「查看当时」起服/销毁会话 hook（#344 自收尾卡收敛单点）的行为面：销毁锚＝
 * mutate 回执成对到场的 hash×viewId（不取「当前 data×当前锚」拼配——在途换版
 * 错位防线）；关窗即销毁；起服中关窗（回执未到）→ 回执到场即销毁（防孤儿）。
 * 经 api client 桩驱动真实 mutation（hook 组合的公共面），不起组件内部细节。
 */

vi.mock("@/lib/api/client", () => ({
  api: {
    post: vi.fn(),
    delete: vi.fn().mockResolvedValue(undefined),
  },
}));

/** 探针组件：把会话动作挂到按钮上（hook 的公共面即 start/finish/startView）。 */
function Probe() {
  const { startView, start, finish } = useVersionViewSession("p1");
  return (
    <div>
      <button type="button" onClick={() => start("abc123")}>
        起A
      </button>
      <button type="button" onClick={() => start("def456")}>
        起B
      </button>
      <button type="button" onClick={finish}>
        关窗
      </button>
      <span data-testid="pending">{String(startView.isPending)}</span>
    </div>
  );
}

function mountProbe() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <Probe />
    </QueryClientProvider>,
  );
}

/** 起服回执：可控行为的 deferred promise（模拟后端起快照返回 viewId）。 */
function deferredStart(viewId: string) {
  let resolve!: (value: { viewId: string; previewUrl: string }) => void;
  const promise = new Promise<{ viewId: string; previewUrl: string }>((r) => {
    resolve = r;
  });
  vi.mocked(api.post).mockReturnValueOnce(promise as never);
  return { arrive: () => resolve({ viewId, previewUrl: "about:blank" }) };
}

beforeEach(() => {
  vi.mocked(api.post).mockReset();
  vi.mocked(api.delete).mockClear();
});

afterEach(cleanup);

describe("useVersionViewSession · 起服与关窗销毁", () => {
  it("start 起服（POST view）；回执到场后关窗 → 成对销毁（DELETE hash/viewId）", async () => {
    mountProbe();
    const start = deferredStart("view-1");

    fireEvent.click(screen.getByText("起A"));
    await act(async () => {}); // mutationFn 走微任务——flush 一拍再断言
    expect(api.post).toHaveBeenCalledWith("/projects/p1/versions/abc123/view");

    await act(async () => start.arrive());
    fireEvent.click(screen.getByText("关窗"));

    await waitFor(() =>
      expect(api.delete).toHaveBeenCalledWith(
        "/projects/p1/versions/abc123/view/view-1",
      ),
    );
  });
});

describe("useVersionViewSession · 起服中关窗（防孤儿快照）", () => {
  it("关窗时回执未到 → 不发 DELETE；回执到场即补销毁", async () => {
    mountProbe();
    const start = deferredStart("view-2");

    fireEvent.click(screen.getByText("起A"));
    fireEvent.click(screen.getByText("关窗")); // 起服在途关窗
    expect(api.delete).not.toHaveBeenCalled();

    await act(async () => start.arrive()); // 回执到场 → 补销毁
    await waitFor(() =>
      expect(api.delete).toHaveBeenCalledWith(
        "/projects/p1/versions/abc123/view/view-2",
      ),
    );
  });
});

describe("useVersionViewSession · 在途换版（销毁锚不错配）", () => {
  it("A 在途换起 B：销毁只按回执成对锚发——不出现新 hash×旧 viewId 拼配", async () => {
    mountProbe();
    const startA = deferredStart("view-a");
    const startB = deferredStart("view-b");

    fireEvent.click(screen.getByText("起A"));
    fireEvent.click(screen.getByText("起B")); // A 在途被顶掉
    await act(async () => {
      await startA.arrive();
      await startB.arrive();
    });

    // 会话锚＝最后回执（B 的成对锚）；关窗销毁 B，不发 A×view-b / B×view-a 拼配
    fireEvent.click(screen.getByText("关窗"));
    await waitFor(() =>
      expect(api.delete).toHaveBeenCalledWith(
        "/projects/p1/versions/def456/view/view-b",
      ),
    );
    const wrongPairing = vi
      .mocked(api.delete)
      .mock.calls.some(([url]) => typeof url === "string" && (/def456\/view\/view-a/.test(url) || /abc123\/view\/view-b/.test(url)));
    expect(wrongPairing).toBe(false);
  });
});
