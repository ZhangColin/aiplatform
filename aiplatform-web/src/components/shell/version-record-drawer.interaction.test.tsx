// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { act } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { useVersionRecordStore } from "@/lib/store/version-record";

import { VersionRecordDrawer } from "./version-record-drawer";

/**
 * 版本记录抽屉交互面（#344，spec #320 ⑥——交接包 §5.2 要求、原型未画→补建）：
 * 开合（store 驱动＝顶栏「版本记录」与 Demo 版本 chip 双挂点共用契约）、分组
 * （PRD 版本组＋Demo 版本组）、默认呈现最新可用版本（首行「最新」标记）、
 * 历史只读（无确认/回滚动作）、回签选择提示呈现位预留（签约页 #348 消费）。
 * 数据面 hook 全部 mock（真实 REST 事实由后端集测覆盖）。
 */

const seed = vi.hoisted(() => ({
  prdVersions: [] as { versionNo?: number; confirmedAt?: string }[],
  prdDetail: null as {
    versionNo?: number;
    content?: string;
    confirmedAt?: string;
  } | null,
  demoVersions: [] as {
    versionNo?: number;
    prdVersionNo?: number | null;
    runId?: string;
    createdAt?: string;
    confirmedAt?: string | null;
  }[],
  gitVersions: [] as { commitHash?: string; runId?: string }[],
  gitVersionsError: false,
  startView: {
    mutate: vi.fn(),
    reset: vi.fn(),
    data: undefined as { viewId?: string; previewUrl?: string } | undefined,
    isPending: false,
    isError: false,
  },
  start: vi.fn(),
  finish: vi.fn(),
}));

vi.mock("@/hooks/use-prd", () => ({
  usePrdVersions: () => ({ data: seed.prdVersions, isPending: false }),
  usePrdVersion: () => ({ data: seed.prdDetail, isPending: false }),
}));
vi.mock("@/hooks/use-demo-version", () => ({
  useDemoVersions: () => ({ data: seed.demoVersions, isPending: false }),
}));
// Demo 条目＝「查看当时」系统快照：git 版本表供 runId→commitHash 映射，会话
// hook（起服/销毁生命周期单点，自身行为归 use-version 测试）桩件断言接线
vi.mock("@/hooks/use-version", () => ({
  useGitVersions: () => ({
    data: seed.gitVersions,
    isPending: false,
    isError: seed.gitVersionsError,
  }),
  useVersionViewSession: () => ({
    startView: seed.startView,
    start: seed.start,
    finish: seed.finish,
  }),
}));

beforeEach(() => {
  seed.prdVersions = [];
  seed.prdDetail = null;
  seed.demoVersions = [];
  seed.gitVersions = [];
  seed.gitVersionsError = false;
  seed.startView.data = undefined;
  seed.startView.isPending = false;
  seed.startView.isError = false;
  seed.startView.mutate.mockClear();
  seed.startView.reset.mockClear();
  seed.start.mockClear();
  seed.finish.mockClear();
  useVersionRecordStore.setState({ open: false });
});

afterEach(() => {
  cleanup();
  useVersionRecordStore.setState({ open: false });
});

function givenVersions() {
  // 清单契约：versionNo 倒序（最新在前）
  seed.prdVersions = [
    { versionNo: 2, confirmedAt: "2026-10-16T09:00:00Z" },
    { versionNo: 1, confirmedAt: "2026-10-14T09:00:00Z" },
  ];
  seed.demoVersions = [
    {
      versionNo: 3,
      prdVersionNo: 2,
      runId: "r-3",
      createdAt: "2026-10-17T09:00:00Z",
      confirmedAt: "2026-10-17T10:00:00Z",
    },
    { versionNo: 2, prdVersionNo: 1, runId: "r-2", createdAt: "2026-10-16T12:00:00Z" },
    { versionNo: 1, prdVersionNo: null, runId: "r-1", createdAt: "2026-10-15T12:00:00Z" },
  ];
}

/** 开抽屉（挂点同款动作：写 store；act 包住让订阅重渲当场落定）。 */
function openDrawer() {
  act(() => {
    useVersionRecordStore.getState().openVersionRecord();
  });
}

describe("VersionRecordDrawer · 开合（store 驱动＝双挂点共用契约）", () => {
  it("store 开 → 抽屉在场（标题＋两组）；store 关 → 撤下", () => {
    const { rerender } = render(<VersionRecordDrawer projectId="p1" />);
    expect(screen.queryByText("版本记录")).toBeNull();

    useVersionRecordStore.getState().openVersionRecord();
    rerender(<VersionRecordDrawer projectId="p1" />);
    expect(screen.getByText("版本记录")).toBeTruthy();
    expect(screen.getByRole("region", { name: "PRD 版本" })).toBeTruthy();
    expect(screen.getByRole("region", { name: "Demo 版本" })).toBeTruthy();

    useVersionRecordStore.getState().closeVersionRecord();
    rerender(<VersionRecordDrawer projectId="p1" />);
    expect(screen.queryByText("版本记录")).toBeNull();
  });

  it("抽屉自带关闭钮 → onOpenChange 收敛回 store（关）", () => {
    render(<VersionRecordDrawer projectId="p1" />);
    openDrawer(); // 渲染期外转移 store——组件订阅自动重渲
    fireEvent.click(screen.getByRole("button", { name: "Close" }));
    expect(useVersionRecordStore.getState().open).toBe(false);
    expect(screen.queryByText("版本记录")).toBeNull();
  });
});

describe("VersionRecordDrawer · 分组（PRD 组＋Demo 组）", () => {
  it("两组条目齐备：版本号、PRD 关联、确认标记", () => {
    givenVersions();
    render(<VersionRecordDrawer projectId="p1" />);
    openDrawer();

    const prdGroup = screen.getByRole("region", { name: "PRD 版本" });
    expect(within(prdGroup).getByText("PRD v2")).toBeTruthy();
    expect(within(prdGroup).getByText("PRD v1")).toBeTruthy();

    const demoGroup = screen.getByRole("region", { name: "Demo 版本" });
    expect(within(demoGroup).getByText("Demo v3")).toBeTruthy();
    expect(within(demoGroup).getByText(/基于 PRD v2 快照/)).toBeTruthy();
    // 迭代成版链：v2 锚旧 PRD v1（改动 PRD 出新版后旧 Demo 关联如实呈现）
    expect(within(demoGroup).getByText(/基于 PRD v1 快照/)).toBeTruthy();
    expect(within(demoGroup).getByText("已确认")).toBeTruthy();
    // 无确认版起跑（legacy/设计类）：不虚构 PRD 关联——Demo v1 行无「基于」
    const legacyRow = within(demoGroup).getByText("Demo v1").closest("button")!;
    expect(legacyRow.textContent).not.toContain("基于 PRD");
  });

  it("空组如实出空态（诚实优先于占位）：尚未确认 PRD／尚未生成 Demo", () => {
    render(<VersionRecordDrawer projectId="p1" />);
    openDrawer();

    expect(screen.getByText(/尚未确认过 PRD/)).toBeTruthy();
    expect(screen.getByText(/尚未生成 Demo/)).toBeTruthy();
  });
});

describe("VersionRecordDrawer · 默认呈现最新可用版本", () => {
  it("两组首行（清单最新在前）带「最新」标记，历史行不带", () => {
    givenVersions();
    render(<VersionRecordDrawer projectId="p1" />);
    openDrawer();

    const latestMarkers = screen.getAllByText("最新");
    expect(latestMarkers).toHaveLength(2); // PRD v2 与 Demo v3 各一枚
    expect(latestMarkers[0]!.closest("button")?.textContent).toContain("PRD v2");
    expect(latestMarkers[1]!.closest("button")?.textContent).toContain("Demo v3");
    // 历史行不带（两枚都归首行，历史行无可命中）
    const prdGroup = screen.getByRole("region", { name: "PRD 版本" });
    expect(within(prdGroup).getAllByText("最新")).toHaveLength(1);
  });
});

describe("VersionRecordDrawer · 历史只读＋回签选择呈现位预留", () => {
  it("无确认/回滚等变更动作——条目只读可回看", () => {
    givenVersions();
    render(<VersionRecordDrawer projectId="p1" />);
    openDrawer();

    const mutateButtons = screen
      .getAllByRole("button")
      .filter((b) => /回滚|确认此版|准备签约/.test(b.textContent ?? ""));
    expect(mutateButtons).toHaveLength(0);
  });

  it("回签选择提示在场（呈现位预留，签约页 #348 消费）", () => {
    render(<VersionRecordDrawer projectId="p1" />);
    openDrawer();

    expect(
      screen.getByText(/从历史版本返回签约时，需明确选择要确认的版本/),
    ).toBeTruthy();
  });
});

describe("VersionRecordDrawer · PRD 条目＝快照全文查看（复用弹窗能力）", () => {
  it("点 PRD 行 → 全文弹窗：标题带版本号、正文出确认时快照全文", async () => {
    givenVersions();
    seed.prdDetail = {
      versionNo: 2,
      content: "# 预约系统需求\n\n确认时的全文快照，后续草稿改动不影响。",
      confirmedAt: "2026-10-16T09:00:00Z",
    };
    render(<VersionRecordDrawer projectId="p1" />);
    openDrawer();

    fireEvent.click(
      within(screen.getByRole("region", { name: "PRD 版本" })).getByText("PRD v2"),
    );

    expect(await screen.findByText(/PRD v2 · 快照全文/)).toBeTruthy();
    expect(await screen.findByText("确认时的全文快照，后续草稿改动不影响。")).toBeTruthy();
  });

  it("历史版本（非最新）同样可点开——全文弹窗无任何编辑入口（只读）", async () => {
    givenVersions();
    seed.prdDetail = { versionNo: 1, content: "第一版全文。" };
    render(<VersionRecordDrawer projectId="p1" />);
    openDrawer();

    fireEvent.click(
      within(screen.getByRole("region", { name: "PRD 版本" })).getByText("PRD v1"),
    );

    expect(await screen.findByText(/PRD v1 · 快照全文/)).toBeTruthy();
    expect(await screen.findByText("第一版全文。")).toBeTruthy();
  });
});

describe("VersionRecordDrawer · Demo 条目＝当时系统快照（复用「查看当时」弹窗）", () => {
  /** Demo v3（runId r-3 → commit abc123）点开的常用前置。 */
  function givenSnapshotReady() {
    givenVersions();
    seed.gitVersions = [
      { commitHash: "abc123", runId: "r-3" },
      { commitHash: "def456", runId: "r-2" },
    ];
    seed.startView.data = { viewId: "view-1", previewUrl: "about:blank" };
  }

  it("点 Demo 行 → 起该版快照（runId 映射 commit）＋弹窗带版本语境标题", async () => {
    givenSnapshotReady();
    render(<VersionRecordDrawer projectId="p1" />);
    openDrawer();

    fireEvent.click(
      within(screen.getByRole("region", { name: "Demo 版本" })).getByText("Demo v3"),
    );

    expect(seed.start).toHaveBeenCalledWith("abc123");
    expect(await screen.findByText("Demo v3 当时的系统")).toBeTruthy();
    expect(await screen.findByTitle("当时系统快照")).toBeTruthy();
  });

  it("关弹窗即收口会话（finish 销毁）且抽屉仍在——可续翻其他版本", async () => {
    givenSnapshotReady();
    render(<VersionRecordDrawer projectId="p1" />);
    openDrawer();

    fireEvent.click(
      within(screen.getByRole("region", { name: "Demo 版本" })).getByText("Demo v3"),
    );
    await screen.findByText("Demo v3 当时的系统");
    // 弹窗自带关闭钮（弹窗内 XPath 位；抽屉的 Close 是另一枚）
    const dialogClose = document.querySelector(
      '[data-slot="dialog-content"] [data-slot="dialog-close"]',
    )!;
    fireEvent.click(dialogClose);

    expect(seed.finish).toHaveBeenCalled(); // 销毁归会话 hook 单点（自身行为有直测）
    expect(screen.queryByText("Demo v3 当时的系统")).toBeNull();
    expect(screen.getByText("版本记录")).toBeTruthy(); // 抽屉仍开——可续翻其他版本
  });

  it("版本表查询失败：如实走错误态（不出无提示空舞台）", async () => {
    givenVersions();
    seed.gitVersionsError = true;
    render(<VersionRecordDrawer projectId="p1" />);
    openDrawer();

    fireEvent.click(
      within(screen.getByRole("region", { name: "Demo 版本" })).getByText("Demo v3"),
    );

    expect(await screen.findByText("Demo v3 当时的系统")).toBeTruthy();
    expect(await screen.findByText("快照起服失败，请关闭后重试")).toBeTruthy();
  });
});
