import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { AgentRun, AgentStreamsState } from "@/lib/store/agent-streams";

import { ChatMode } from "./chat-mode";

// 聊天区运行条（issue #59：终止占位下架、终态徽章保持）：读 streams store 最近
// run（SSR mock 播种，advisor-chat.test 同款——zustand v5 静态渲染读 create 时
// 初始快照，setState 播种不可见；store 本体行为由 agent-streams.test 覆盖）。
type SeedState = Pick<AgentStreamsState, "runs" | "order">;
const seed = vi.hoisted(() => ({ state: { runs: {}, order: [] } as SeedState }));

vi.mock("@/lib/store/agent-streams", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/store/agent-streams")>();
  return {
    ...actual,
    useAgentStreamsStore: <T,>(selector: (state: SeedState) => T): T => selector(seed.state),
  };
});

function render() {
  const qc = new QueryClient();
  return renderToStaticMarkup(
    <QueryClientProvider client={qc}>
      <ChatMode projectId="p1" />
    </QueryClientProvider>,
  );
}

function runOf(status: AgentRun["status"]): AgentRun {
  return { runId: "r1", projectId: "p1", status, segments: [] };
}

describe("ChatMode · 运行条（issue #59）", () => {
  beforeEach(() => {
    seed.state = { runs: {}, order: [] };
  });

  it("run 进行中：运行条在（runId + 计时），终止占位按钮已撤", () => {
    seed.state = { runs: { r1: runOf("running") }, order: ["r1"] };
    const html = render();
    expect(html).toContain("r1");
    expect(html).not.toContain("终止");
  });

  it("finished 终态徽章保持：『已完成』", () => {
    seed.state = { runs: { r1: runOf("finished") }, order: ["r1"] };
    const html = render();
    expect(html).toContain("已完成");
    expect(html).not.toContain("已出错");
    expect(html).not.toContain("终止");
  });

  it("error 终态徽章保持：『已出错』", () => {
    seed.state = { runs: { r1: runOf("error") }, order: ["r1"] };
    const html = render();
    expect(html).toContain("已出错");
    expect(html).not.toContain("终止");
  });

  it("无 run：运行条不渲染，下任务输入仍在", () => {
    const html = render();
    expect(html).toContain("给智能体下任务");
  });
});
