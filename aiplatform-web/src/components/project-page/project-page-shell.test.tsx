import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { SidebarProvider } from "@/components/ui/sidebar";
import type { AgentRun, AgentRunsState } from "@/lib/store/agent-runs";

import { ProjectPageRunStatus, ProjectPageShell } from "./project-page-shell";

// 顶栏运行状态（LIVE 真绑定）直读运行注册表（最近 run 读口
// latestProjectRun）。zustand v5 在 react-dom/server 下 server snapshot
// 读 create 时初始快照、setState 播种对静态渲染不可见，mock 单个 hook
// 直读种子状态，store 本体行为由 agent-runs.test 覆盖。
type SeedState = Pick<AgentRunsState, "runs" | "order">;
const seed = vi.hoisted(() => ({ state: { runs: {}, order: [] } as SeedState }));

vi.mock("@/lib/store/agent-runs", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/store/agent-runs")>();
  return {
    ...actual,
    useAgentRunsStore: <T,>(selector: (state: SeedState) => T): T => selector(seed.state),
  };
});

function runOf(status: AgentRun["status"], startedAt = Date.now() - 65_000): AgentRun {
  return { runId: "r1", projectId: "p1", status, startedAt };
}

function seedRun(run: AgentRun) {
  seed.state = { runs: { [run.runId]: run }, order: [run.runId] };
}

describe("ProjectPageRunStatus · LIVE 真绑定", () => {
  beforeEach(() => {
    // 计时锚断言需确定性时钟（锚 run.startedAt，非挂载起跳）
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-08-27T10:00:00Z"));
    seed.state = { runs: {}, order: [] };
  });
  afterEach(() => vi.useRealTimers());

  it("无 run：整块不渲染（不再常驻装饰）", () => {
    const html = renderToStaticMarkup(<ProjectPageRunStatus projectId="p1" />);
    expect(html).not.toContain("LIVE");
  });

  it("run 进行中：LIVE 脉冲 + 计时锚 run.startedAt（65s 前起跑非从 0）", () => {
    seedRun(runOf("running"));
    const html = renderToStaticMarkup(<ProjectPageRunStatus projectId="p1" />);
    expect(html).toContain("LIVE");
    expect(html).toContain("01:05");
    expect(html).not.toContain("00:00");
  });

  it("questioning 也是进行中：等用户 ≠ 终态", () => {
    seedRun(runOf("questioning", Date.now() - 5_000));
    const html = renderToStaticMarkup(<ProjectPageRunStatus projectId="p1" />);
    expect(html).toContain("LIVE");
  });

  it("终态（finished / error）：整块不渲染", () => {
    seedRun(runOf("finished"));
    expect(renderToStaticMarkup(<ProjectPageRunStatus projectId="p1" />)).not.toContain("LIVE");
    seedRun(runOf("error"));
    expect(renderToStaticMarkup(<ProjectPageRunStatus projectId="p1" />)).not.toContain("LIVE");
  });

  it("别的项目的 run 不串台：按 projectId 键控", () => {
    seedRun({ ...runOf("running"), projectId: "p2" });
    expect(renderToStaticMarkup(<ProjectPageRunStatus projectId="p1" />)).not.toContain("LIVE");
  });
});

// 项目页壳（#79 对话主角式定稿）：对话居中 + 呼出式成果区（resizable 双槽、
// 收起态顶栏出「成果」键）+ <lg 双页签退化 + 顶栏运行状态插槽。只断言结构，
// 栏宽 / 槽位内容归场景插槽。
describe("ProjectPageShell", () => {
  it("成果区滑出（outputsOpen）：resizable 双槽——对话列 + 成果列都在", () => {
    const html = renderToStaticMarkup(
      <SidebarProvider>
        <ProjectPageShell
          header={<span>项目甲</span>}
          running={<ProjectPageRunStatus projectId="p1" />}
          chat={<span>对话区</span>}
          outputs={<span>成果区</span>}
          outputsOpen
          onOutputsOpen={() => {}}
          mobileTabs={["对话", "成果"]}
        />
      </SidebarProvider>,
    );

    // 双槽：resizable 面板组恰两个 panel（对话列 / 成果列）+ 拖拽分隔条
    expect(html).toContain('data-slot="resizable-panel-group"');
    expect(html.match(/data-slot="resizable-panel"/g)).toHaveLength(2);
    expect(html).toContain('data-slot="resizable-handle"');
    // 滑出态不出「成果」呼出键
    expect(html).not.toContain('aria-label="展开成果区"');
    // <lg 退化双页签 + 两槽内容都在
    expect(html).toContain("对话区");
    expect(html).toContain("成果区");
  });

  it("成果区收起（outputsOpen=false）：单槽满宽 + 顶栏出「成果」呼出键", () => {
    const html = renderToStaticMarkup(
      <SidebarProvider>
        <ProjectPageShell
          header={<span>项目甲</span>}
          running={null}
          chat={<span>对话区</span>}
          outputs={<span>成果区</span>}
          outputsOpen={false}
          onOutputsOpen={() => {}}
          mobileTabs={["对话", "成果"]}
        />
      </SidebarProvider>,
    );

    // lg 单槽（对话列独占，无分隔条）
    expect(html.match(/data-slot="resizable-panel"/g)).toHaveLength(1);
    expect(html).not.toContain('data-slot="resizable-handle"');
    expect(html).toContain("对话区");
    // 顶栏「成果」呼出键（收起态唯一入口）
    expect(html).toContain('aria-label="展开成果区"');
  });

  it("闲聊期（outputs 缺省）：单槽满宽、无 resizable / 页签 / 呼出键（#19 尚无产物）", () => {
    const html = renderToStaticMarkup(
      <SidebarProvider>
        <ProjectPageShell
          header={<span>项目甲</span>}
          running={null}
          chat={<span>对话区</span>}
          mobileTabs={["对话"]}
        />
      </SidebarProvider>,
    );

    expect(html).not.toContain('data-slot="resizable-panel-group"');
    expect(html).not.toContain('data-slot="tabs-trigger"');
    expect(html).not.toContain('aria-label="展开成果区"');
    expect(html).toContain("对话区");
  });
});
