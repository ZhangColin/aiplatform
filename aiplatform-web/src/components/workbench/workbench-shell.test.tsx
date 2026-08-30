import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { SidebarProvider } from "@/components/ui/sidebar";
import type { AgentRun, AgentStreamsState } from "@/lib/store/agent-streams";

import { RightPanelToggle, WorkbenchRunStatus, WorkbenchShell } from "./workbench-shell";

// 顶栏运行状态（issue #59 LIVE 真绑定）直读 streams store（最近 run 读口
// latestProjectRun，与聊天区运行条同口）。zustand v5 在 react-dom/server 下
// server snapshot 读 create 时初始快照、setState 播种对静态渲染不可见
// （advisor-chat.test 同款注释），mock 单个 hook 直读种子状态，store 本体
// 行为由 agent-streams.test 覆盖。
type SeedState = Pick<AgentStreamsState, "runs" | "order">;
const seed = vi.hoisted(() => ({ state: { runs: {}, order: [] } as SeedState }));

vi.mock("@/lib/store/agent-streams", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/store/agent-streams")>();
  return {
    ...actual,
    useAgentStreamsStore: <T,>(selector: (state: SeedState) => T): T => selector(seed.state),
  };
});

function runOf(status: AgentRun["status"], startedAt = Date.now() - 65_000): AgentRun {
  return { runId: "r1", projectId: "p1", status, startedAt, segments: [] };
}

function seedRun(run: AgentRun) {
  seed.state = { runs: { [run.runId]: run }, order: [run.runId] };
}

describe("WorkbenchRunStatus · LIVE 真绑定（issue #59）", () => {
  beforeEach(() => {
    // 计时锚断言需确定性时钟（锚 run.startedAt，非挂载起跳）
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-08-27T10:00:00Z"));
    seed.state = { runs: {}, order: [] };
  });
  afterEach(() => vi.useRealTimers());

  it("无 run：整块不渲染（不再常驻装饰）", () => {
    const html = renderToStaticMarkup(<WorkbenchRunStatus projectId="p1" />);
    expect(html).not.toContain("LIVE");
    expect(html).not.toContain("终止");
  });

  it("run 进行中：LIVE 脉冲 + 计时锚 run.startedAt（65s 前起跑非从 0），无终止按钮", () => {
    seedRun(runOf("running"));
    const html = renderToStaticMarkup(<WorkbenchRunStatus projectId="p1" />);
    expect(html).toContain("LIVE");
    expect(html).toContain("01:05");
    expect(html).not.toContain("00:00");
    expect(html).not.toContain("终止");
  });

  it("waiting 也是进行中：等用户 ≠ 终态（同运行条 spinner 口径）", () => {
    seedRun(runOf("waiting", Date.now() - 5_000));
    const html = renderToStaticMarkup(<WorkbenchRunStatus projectId="p1" />);
    expect(html).toContain("LIVE");
  });

  it("终态（finished / error）：整块不渲染", () => {
    seedRun(runOf("finished"));
    expect(renderToStaticMarkup(<WorkbenchRunStatus projectId="p1" />)).not.toContain("LIVE");
    seedRun(runOf("error"));
    expect(renderToStaticMarkup(<WorkbenchRunStatus projectId="p1" />)).not.toContain("LIVE");
  });

  it("别的项目的 run 不串台：按 projectId 键控", () => {
    seedRun({ ...runOf("running"), projectId: "p2" });
    expect(renderToStaticMarkup(<WorkbenchRunStatus projectId="p1" />)).not.toContain("LIVE");
  });
});

// 工作台 D 壳（spec 0001 §3）：resizable 三栏、右栏显式开关、<lg 三页签退化、
// 顶栏运行状态插槽。只断言结构，栏宽 / 面板内容归场景插槽。
describe("WorkbenchShell", () => {
  it("三栏框架 + 右栏显式开关 + <lg 三页签一起就位", () => {
    const html = renderToStaticMarkup(
      <SidebarProvider>
        <WorkbenchShell
          header={<span>项目甲</span>}
          running={<WorkbenchRunStatus projectId="p1" />}
          left={<span>Agent 区</span>}
          center={(api) => (
            <RightPanelToggle open={api.rightOpen} onClick={api.toggleRight} label="阶段面板" />
          )}
          right={<span>阶段·任务</span>}
          mobileTabs={["对话", "工作区", "阶段"]}
        />
      </SidebarProvider>,
    );

    // 三栏：resizable 面板组含三个 panel（左 Agent / 中主 / 右呼出）
    expect(html).toContain('data-slot="resizable-panel-group"');
    expect(html.match(/data-slot="resizable-panel"/g)).toHaveLength(3);
    // 右栏默认展开 → 开关钮为「收起」语义
    expect(html).toContain("收起阶段面板");
    // <lg 退化三页签
    expect(html).toContain("对话");
    expect(html).toContain("工作区");
    expect(html).toContain("阶段");
    // 三栏插槽内容都在
    expect(html).toContain("Agent 区");
    expect(html).toContain("阶段·任务");
  });
});
