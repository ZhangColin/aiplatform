// @vitest-environment happy-dom
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { ProjectSummary } from "@/lib/projects/list";

import { ProjectListView } from "./project-list-view";

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn() }));
const seed = vi.hoisted(() => ({
  projects: [] as ProjectSummary[],
  isPending: false,
  isError: false,
}));

vi.mock("next/navigation", () => ({ useRouter: () => router }));
vi.mock("@/hooks/use-projects", () => ({
  useProjectList: () => ({
    data: seed.projects,
    isPending: seed.isPending,
    isError: seed.isError,
    error: null,
    refetch: vi.fn(),
  }),
  useArchiveProject: () => ({ mutate: vi.fn(), isPending: false }),
}));

function project(overrides: Partial<ProjectSummary>): ProjectSummary {
  return {
    id: "p0",
    name: "项目",
    archived: false,
    createdAt: "2026-10-01T00:00:00Z",
    updatedAt: "2026-10-02T00:00:00Z",
    prdConfirmed: false,
    demoConfirmed: false,
    needsUserSupplement: false,
    ...overrides,
  };
}

beforeEach(() => {
  seed.projects = [];
  seed.isPending = false;
  seed.isError = false;
  router.push.mockReset();
  router.replace.mockReset();
});

afterEach(cleanup);

describe("我的项目 P08（#349）", () => {
  it("列表展示项目名称、最后更新、生命周期阶段与待办；交易子态降副行并按最近更新排序", () => {
    seed.projects = [
      project({ id: "p1", name: "需求梳理项目", updatedAt: "2026-10-10T00:00:00Z" }),
      project({
        id: "p2",
        name: "签约项目",
        prdConfirmed: true,
        demoConfirmed: true,
        activeOrderStatus: 2,
        updatedAt: "2026-10-12T00:00:00Z",
      }),
      project({
        id: "p3",
        name: "生产项目",
        prdConfirmed: true,
        demoConfirmed: true,
        activeOrderStatus: 3,
        needsUserSupplement: true,
        updatedAt: "2026-10-11T00:00:00Z",
      }),
    ];
    const { container } = render(<ProjectListView guestOnly={false} />);

    expect(screen.getByText("需求梳理项目")).toBeTruthy();
    expect(screen.getAllByText("最后更新", { exact: false }).length).toBeGreaterThan(0);
    expect(screen.getByText("需求分析")).toBeTruthy();
    expect(screen.getByText("签约付款")).toBeTruthy();
    expect(screen.getByText("待支付")).toBeTruthy();
    expect(screen.getByText("生产承接")).toBeTruthy();
    expect(screen.getAllByText("待用户处理事项").length).toBe(3);
    expect(screen.getByText("补充资料或确认事项")).toBeTruthy();

    const text = container.textContent ?? "";
    expect(text.indexOf("签约项目")).toBeLessThan(text.indexOf("生产项目"));
    expect(text.indexOf("生产项目")).toBeLessThan(text.indexOf("需求梳理项目"));
  });

  it("无项目时空态提供「开始一个新需求」入口", () => {
    render(<ProjectListView guestOnly={false} />);

    expect(screen.getByRole("link", { name: "开始一个新需求" }).getAttribute("href"))
      .toBe("/");
  });

  it("未绑定访客只回当前会话的最近项目，不展示全量列表", async () => {
    seed.projects = [
      project({ id: "p-old", name: "旧需求", updatedAt: "2026-10-10T00:00:00Z" }),
      project({ id: "p-current", name: "当前需求", updatedAt: "2026-10-12T00:00:00Z" }),
    ];
    render(<ProjectListView guestOnly />);

    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/projects/p-current"));
    expect(screen.queryByText("旧需求")).toBeNull();
    expect(screen.queryByText("当前需求")).toBeNull();
  });

  it("绑定账号后保留全量列表，不回跳单个项目", () => {
    seed.projects = [
      project({ id: "p-one", name: "项目一" }),
      project({ id: "p-two", name: "项目二" }),
    ];
    render(<ProjectListView guestOnly={false} />);

    expect(screen.getByText("项目一")).toBeTruthy();
    expect(screen.getByText("项目二")).toBeTruthy();
    expect(router.replace).not.toHaveBeenCalled();
  });
});
