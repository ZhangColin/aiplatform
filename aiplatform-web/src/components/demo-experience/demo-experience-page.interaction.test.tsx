// @vitest-environment happy-dom
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { components } from "@/lib/api/schema";
import { ApiError } from "@/lib/api/api-error";
import type { ProjectDetail } from "@/lib/projects/detail";
import { useM01Store } from "@/lib/store/m01";
import { useVersionRecordStore } from "@/lib/store/version-record";

import { DemoExperiencePage } from "./demo-experience-page";

type DemoVersion = components["schemas"]["DemoVersionSummaryResponse"];

const seed = vi.hoisted(() => ({
  detail: undefined as ProjectDetail | undefined,
  demoVersions: [] as DemoVersion[],
  prdVersions: [] as { versionNo: number }[],
  confirmMutate: vi.fn(),
  confirmOptions: undefined as { onSuccess?: () => void } | undefined,
  orderMutate: vi.fn(),
  orderOptions: undefined as
    | { onSuccess?: () => void; onError?: (e: unknown) => void }
    | undefined,
  routerPush: vi.fn(),
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
  useConfirmDemoVersion: () => ({
    mutate: (versionNo: number, options?: { onSuccess?: () => void }) => {
      seed.confirmOptions = options;
      seed.confirmMutate(versionNo, options);
    },
    isPending: false,
  }),
}));

vi.mock("@/hooks/use-order", () => ({
  usePlaceOrder: () => ({
    mutate: (_input?: unknown, options?: { onSuccess?: () => void; onError?: (e: unknown) => void }) => {
      seed.orderOptions = options;
      seed.orderMutate();
    },
    isPending: false,
  }),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: seed.routerPush }),
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
  seed.confirmMutate.mockClear();
  seed.confirmOptions = undefined;
  seed.orderMutate.mockClear();
  seed.orderOptions = undefined;
  seed.routerPush.mockClear();
  useM01Store.getState().closeM01();
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
    expect(seed.confirmMutate).toHaveBeenNthCalledWith(1, 1, expect.anything());
    expect(seed.confirmMutate).toHaveBeenNthCalledWith(2, 1, expect.anything());
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

describe("Demo 体验页 · 确认门并入下单（#350：顶栏旧「确认下单」退役的并入位）", () => {
  it("确认标记成功 → 建单 → 建单成功自动跳签约页", () => {
    renderPage();

    fireEvent.click(screen.getByRole("button", { name: "确认此版本，准备签约" }));
    expect(seed.confirmMutate).toHaveBeenCalledWith(1, expect.anything());
    // 确认未收口前不建单（ORD_019 前置：无 Demo 确认版本不可下单）
    expect(seed.orderMutate).not.toHaveBeenCalled();

    act(() => seed.confirmOptions?.onSuccess?.());
    expect(seed.orderMutate).toHaveBeenCalledTimes(1);
    expect(seed.routerPush).not.toHaveBeenCalled();

    act(() => seed.orderOptions?.onSuccess?.());
    expect(seed.routerPush).toHaveBeenCalledWith("/projects/p1/contract");
  });

  it("建单被签约门拦（ORD_017/5017）→ 弹 M01（stage-gate），续跑回调重试建单", () => {
    renderPage();

    fireEvent.click(screen.getByRole("button", { name: "确认此版本，准备签约" }));
    act(() => seed.confirmOptions?.onSuccess?.());
    act(() => {
      seed.orderOptions?.onError?.(
        new ApiError({ status: 403, code: 5017, message: "签约前请先登录绑定账号" }),
      );
    });

    expect(useM01Store.getState().open).toBe(true);
    expect(useM01Store.getState().trigger).toBe("stage-gate");
    // 绑定后放行：续跑只重建单（版本确认标记已落，不重复打）
    const resume = useM01Store.getState().resume;
    expect(typeof resume).toBe("function");
    seed.orderMutate.mockClear();
    act(() => resume?.());
    expect(seed.orderMutate).toHaveBeenCalledTimes(1);
    expect(seed.confirmMutate).toHaveBeenCalledTimes(1);
  });

  it("建单遇非签约门错误不弹 M01（归 hook toast）", () => {
    renderPage();

    fireEvent.click(screen.getByRole("button", { name: "确认此版本，准备签约" }));
    act(() => seed.confirmOptions?.onSuccess?.());
    act(() => {
      seed.orderOptions?.onError?.(
        new ApiError({ status: 409, code: 5003, message: "已有未终结订单" }),
      );
    });

    expect(useM01Store.getState().open).toBe(false);
    expect(seed.routerPush).not.toHaveBeenCalled();
  });

  it("已确认未建单（确认后建单失败/存量）：按钮转「重新提交订单」——入口不死路", () => {
    renderPage();

    // 确认标记落库、建单失败（非 5017）→ 详情重拉后 confirmedAt 在场、无未终结订单
    fireEvent.click(screen.getByRole("button", { name: "确认此版本，准备签约" }));
    act(() => seed.confirmOptions?.onSuccess?.());
    act(() => {
      seed.orderOptions?.onError?.(new ApiError({ status: 500, code: 5000, message: "服务繁忙" }));
    });
    cleanup(); // 重拉后的新挂载（旧实例退场，防双挂假性多按钮）

    seed.demoVersions = [{ ...seed.demoVersions[0]!, confirmedAt: "2026-10-18T03:00:00Z" }];
    renderPage();

    const retryButtons = screen.getAllByRole("button", { name: /重新提交订单/ });
    expect(retryButtons.length).toBeGreaterThanOrEqual(2); // 横幅＋确认卡双位
    for (const btn of retryButtons) expect(btn.hasAttribute("disabled")).toBe(false);

    // 重试＝直建单（不重复打确认标记）；成功照跳签约页
    fireEvent.click(retryButtons[0]!);
    expect(seed.orderMutate).toHaveBeenCalledTimes(2);
    expect(seed.confirmMutate).toHaveBeenCalledTimes(1);
    act(() => seed.orderOptions?.onSuccess?.());
    expect(seed.routerPush).toHaveBeenCalledWith("/projects/p1/contract");
  });

  it("已确认且已有未终结订单：维持「当前版本已确认」禁用（不出重试位）", () => {
    seed.demoVersions = [{ ...seed.demoVersions[0]!, confirmedAt: "2026-10-18T03:00:00Z" }];
    seed.detail = {
      ...seed.detail!,
      activeOrder: { id: "o1", status: 1 },
    } as ProjectDetail;
    renderPage();

    expect(screen.queryByRole("button", { name: /重新提交订单/ })).toBeNull();
    expect(screen.getByRole("button", { name: "当前版本已确认" }).hasAttribute("disabled")).toBe(true);
  });
});

describe("Demo 体验页 · 移动单区域切换（#350，交接包 13.2：对话/预览单区域切换）", () => {
  const areaOf = (name: string) => document.querySelector(`[data-area="${name}"]`);

  it("移动切换件出「预览/对话」两区：默认预览区可见、对话区收起；点「对话」切换", () => {
    renderPage();

    expect(screen.getByRole("button", { name: "预览" }).getAttribute("aria-pressed")).toBe("true");
    expect(screen.getByRole("button", { name: "对话" }).getAttribute("aria-pressed")).toBe("false");
    expect(areaOf("preview")?.className).not.toContain("hidden");
    expect(areaOf("chat")?.className).toContain("hidden");

    fireEvent.click(screen.getByRole("button", { name: "对话" }));
    expect(areaOf("chat")?.className).not.toContain("hidden");
    expect(areaOf("preview")?.className).toContain("hidden");
  });
});
