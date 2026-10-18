// @vitest-environment happy-dom
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { components } from "@/lib/api/schema";
import type { ProjectDetail } from "@/lib/projects/detail";
import { useVersionRecordStore } from "@/lib/store/version-record";

import { DemoExperiencePage } from "./demo-experience-page";

type DemoVersion = components["schemas"]["DemoVersionSummaryResponse"];

const seed = vi.hoisted(() => ({
  detail: undefined as ProjectDetail | undefined,
  demoVersions: [] as DemoVersion[],
  prdVersions: [] as { versionNo: number }[],
  confirmMutate: vi.fn(),
}));

vi.mock("@/hooks/use-project", () => ({
  useProject: () => ({
    data: seed.detail,
    isPending: false,
    isError: false,
    error: null,
    refetch: vi.fn(),
  }),
}));

vi.mock("@/hooks/use-demo-version", () => ({
  useDemoVersions: () => ({ data: seed.demoVersions }),
  useConfirmDemoVersion: () => ({ mutate: seed.confirmMutate, isPending: false }),
}));

vi.mock("@/hooks/use-prd", () => ({
  usePrdVersions: () => ({ data: seed.prdVersions }),
}));

vi.mock("@/lib/sse/agent-event-channel", () => ({ useAgentEventChannel: () => ({}) }));
vi.mock("@/lib/sse/provider", () => ({ useSseStatus: () => "connected" }));

vi.mock("@/components/project-page/system-panel", () => ({
  SystemPanel: ({ onOpenFiles }: { onOpenFiles?: () => void }) => (
    <div>
      <div>真实 Demo 预览与等待态</div>
      <button type="button" onClick={onOpenFiles}>查看源码文件</button>
    </div>
  ),
}));

vi.mock("@/components/project-page/command-area", () => ({
  CommandArea: () => <div>对话直播、圈注附件与反馈输入</div>,
}));

vi.mock("@/components/project-page/files-panel", () => ({
  FilesPanel: () => <div>源码树与文件内容</div>,
}));

function seedReadyProject() {
  seed.detail = {
    id: "p1",
    name: "智能企业客户预约系统",
    prdProducedAt: "2026-10-18T01:00:00Z",
    generatedAt: "2026-10-18T02:00:00Z",
    generationState: "generated",
    segments: [
      { ord: 1, description: "客户在线预约日历排班引擎", status: "closed" },
      { ord: 2, description: "智能技工时间冲突自动检测与拦截", status: "closed" },
      { ord: 3, description: "工单多状态联动与流转 (待签/执行/归档)", status: "closed" },
    ],
  } as ProjectDetail;
  seed.demoVersions = [
    {
      projectId: "p1",
      versionNo: 1,
      prdVersionNo: 2,
      runId: "run-1",
      createdAt: "2026-10-18T02:00:00Z",
      confirmedAt: undefined,
    },
  ];
  seed.prdVersions = [{ versionNo: 2 }];
}

function renderPage() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <DemoExperiencePage projectId="p1" />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  seedReadyProject();
  seed.confirmMutate.mockReset();
  useVersionRecordStore.getState().closeVersionRecord();
});

afterEach(cleanup);

describe("Demo 体验页 P05 · 原型结构（#347）", () => {
  it("项目横幅与右栏三段式照原型落位，基准和流水吃真实版本/切片记录", () => {
    renderPage();

    expect(screen.getByText("PROJECT")).toBeTruthy();
    expect(screen.getByText("智能企业客户预约系统")).toBeTruthy();
    expect(screen.getByText("Demo v1")).toBeTruthy();
    expect(screen.getByText("(基于 PRD v2 快照)")).toBeTruthy();
    expect(screen.getByText("预览运行中 (Ready)")).toBeTruthy();
    expect(screen.getByRole("button", { name: "阶段存盘" })).toBeTruthy();

    expect(screen.getByText("版本交付基准")).toBeTruthy();
    expect(screen.getByText("PRD v2 对齐")).toBeTruthy();
    expect(screen.getByText("客户在线预约日历排班引擎")).toBeTruthy();
    expect(screen.getByText("历史版本生成流流水")).toBeTruthy();
    expect(screen.getByText("ALL GREEN")).toBeTruthy();
    expect(screen.getByText("演示数据")).toBeTruthy();
    expect(screen.getByText("演示限制")).toBeTruthy();

    expect(screen.getByText("反馈与需求迭代")).toBeTruthy();
    expect(screen.getByText("围绕此 Demo 提建议")).toBeTruthy();
    expect(screen.getByText("对话直播、圈注附件与反馈输入")).toBeTruthy();
    expect(screen.queryByText("问题或调整类型")).toBeNull();
    expect(screen.queryByText("反馈详情描述")).toBeNull();

    expect(screen.getByText("确认此版本进入签约")).toBeTruthy();
    expect(screen.getByText("PRD 确定")).toBeTruthy();
    expect(screen.getByText("Demo 确定")).toBeTruthy();
    expect(screen.getByText("签约付款")).toBeTruthy();
    expect(
      screen.getByRole("button", { name: "确认当前版本 · 进入签约流程" }),
    ).toBeTruthy();
  });

  it("横幅主按钮与确认卡共用当前真实 Demo 版本的确认标记动作", () => {
    renderPage();

    fireEvent.click(screen.getByRole("button", { name: "确认此版本，准备签约" }));
    fireEvent.click(screen.getByRole("button", { name: "确认当前版本 · 进入签约流程" }));

    expect(seed.confirmMutate).toHaveBeenCalledTimes(2);
    expect(seed.confirmMutate).toHaveBeenNthCalledWith(1, 1);
    expect(seed.confirmMutate).toHaveBeenNthCalledWith(2, 1);
  });

  it("横幅 Demo 版本 chip 打开项目壳共用的版本记录抽屉", () => {
    renderPage();

    fireEvent.click(screen.getByRole("button", { name: /查看版本记录/ }));

    expect(useVersionRecordStore.getState().open).toBe(true);
  });

  it("生成中同页切换状态胶囊与基准流水，不虚构下一版 Demo 号", () => {
    seed.detail = { ...seed.detail!, generationState: "generating", generatedAt: undefined };
    seed.demoVersions = [];
    renderPage();

    expect(screen.getByText("Demo 生成中 (Generating)")).toBeTruthy();
    expect(screen.getByText("GENERATING")).toBeTruthy();
    expect(screen.queryByText("Demo v2")).toBeNull();
    expect(screen.getByRole("button", { name: "确认当前版本 · 进入签约流程" }).hasAttribute("disabled")).toBe(true);
  });

  it("遗留 Demo 没有 PRD 锚时不拿最新 PRD 版本补写关联", () => {
    seed.demoVersions = [{ ...seed.demoVersions[0]!, prdVersionNo: undefined }];
    seed.prdVersions = [{ versionNo: 9 }];
    renderPage();

    expect(screen.getByText("PRD 对齐")).toBeTruthy();
    expect(screen.queryByText("PRD v9 对齐")).toBeNull();
  });

  it("文件区收进 Demo 页辅助抽屉，打开后保留源码树与文件内容", () => {
    renderPage();

    fireEvent.click(screen.getByRole("button", { name: "查看源码文件" }));

    expect(screen.getByText("项目文件与源码")).toBeTruthy();
    expect(screen.getByText("源码树与文件内容")).toBeTruthy();
  });
});
