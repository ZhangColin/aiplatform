// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { act } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { useVersionRecordStore } from "@/lib/store/version-record";

import { VersionTimelineSheet } from "./version-timeline-sheet";

/**
 * 单轴版本时间线交互面（#626，ADR-0056）：store 驱动开合（顶栏「版本记录」与
 * 版本 chip 同入口）；每项＝版本号＋用户那句话＋时间＋预览入口＋「回到此版」，
 * 标出当前版/已确认版；空态与错误态如实呈现。数据面 hook 全部 mock。
 */

const seed = vi.hoisted(() => ({
  versions: [] as {
    commitHash?: string;
    versionNo?: number;
    subject?: string;
    committedAt?: string;
    current?: boolean;
    confirmed?: boolean;
  }[],
  isPending: false,
  isError: false,
  previewUrl: undefined as string | undefined,
  refetch: vi.fn(),
  rollbackMutate: vi.fn(),
  rollbackPending: false,
}));

vi.mock("@/hooks/use-version", () => ({
  useGitVersions: () => ({
    data: seed.versions,
    isPending: seed.isPending,
    isError: seed.isError,
  }),
  useRollbackVersion: () => ({
    mutate: seed.rollbackMutate,
    isPending: seed.rollbackPending,
  }),
}));
vi.mock("@/hooks/use-project-preview", () => ({
  useProjectPreview: () => ({
    data: seed.previewUrl ? { url: seed.previewUrl } : undefined,
    refetch: seed.refetch,
  }),
}));

beforeEach(() => {
  seed.versions = [];
  seed.isPending = false;
  seed.isError = false;
  seed.previewUrl = undefined;
  seed.rollbackPending = false;
  seed.refetch.mockClear();
  seed.rollbackMutate.mockClear();
  useVersionRecordStore.setState({ open: false });
});

afterEach(() => {
  cleanup();
  useVersionRecordStore.setState({ open: false });
  vi.restoreAllMocks();
});

function openTimeline() {
  act(() => {
    useVersionRecordStore.getState().openVersionRecord();
  });
}

describe("VersionTimelineSheet · 开合（store 驱动＝版本 chip/顶栏同入口）", () => {
  it("store 开 → 时间线在场（标题「版本」）；store 关 → 撤下", () => {
    const { rerender } = render(<VersionTimelineSheet projectId="p1" />);
    expect(screen.queryByText("版本")).toBeNull();

    useVersionRecordStore.getState().openVersionRecord();
    rerender(<VersionTimelineSheet projectId="p1" />);
    expect(screen.getByRole("heading", { name: "版本" })).toBeTruthy();

    useVersionRecordStore.getState().closeVersionRecord();
    rerender(<VersionTimelineSheet projectId="p1" />);
    expect(screen.queryByRole("heading", { name: "版本" })).toBeNull();
  });
});

describe("VersionTimelineSheet · 单轴列表（时间＋那句话＋预览＋回到此版）", () => {
  it("列表新→旧：版本号、用户那句话、当前/已确认标记在场", () => {
    seed.versions = [
      {
        commitHash: "c2",
        versionNo: 2,
        subject: "把按钮改成蓝色",
        committedAt: "2026-10-17T09:00:00Z",
        current: true,
      },
      {
        commitHash: "a1",
        versionNo: 1,
        subject: "做一个预约系统",
        committedAt: "2026-10-16T09:00:00Z",
        confirmed: true,
      },
    ];
    render(<VersionTimelineSheet projectId="p1" />);
    openTimeline();

    expect(screen.getByText("v2")).toBeTruthy();
    expect(screen.getByText("v1")).toBeTruthy();
    expect(screen.getByText("把按钮改成蓝色")).toBeTruthy();
    expect(screen.getByText("做一个预约系统")).toBeTruthy();
    expect(screen.getAllByText("当前")).toHaveLength(1);
    expect(screen.getByText("已确认")).toBeTruthy();
  });

  it("预览入口打开当前运行中的系统预览（URL 就绪才可点）", () => {
    const open = vi.spyOn(window, "open").mockImplementation(() => null);
    seed.previewUrl = "http://preview.local/";
    seed.versions = [
      { commitHash: "c2", versionNo: 2, subject: "把按钮改成蓝色", current: true },
    ];
    render(<VersionTimelineSheet projectId="p1" />);
    openTimeline();

    fireEvent.click(screen.getByRole("button", { name: "打开当前预览（v2）" }));

    expect(open).toHaveBeenCalledWith("http://preview.local/", "_blank", "noopener");
  });

  it("预览未就绪：预览按钮禁用", () => {
    seed.versions = [
      { commitHash: "c2", versionNo: 2, subject: "把按钮改成蓝色", current: true },
    ];
    render(<VersionTimelineSheet projectId="p1" />);
    openTimeline();

    expect(
      (screen.getByRole("button", { name: "打开当前预览（v2）" }) as HTMLButtonElement).disabled,
    ).toBe(true);
  });

  it("「回到此版」回滚到该版 commit；当前版按钮禁用", () => {
    seed.versions = [
      { commitHash: "c2", versionNo: 2, subject: "把按钮改成蓝色", current: true },
      { commitHash: "a1", versionNo: 1, subject: "做一个预约系统" },
    ];
    render(<VersionTimelineSheet projectId="p1" />);
    openTimeline();

    // 当前版不可再回滚（禁用）
    expect(
      (screen.getByRole("button", { name: "回到 v2" }) as HTMLButtonElement).disabled,
    ).toBe(true);
    // 历史版可回滚到该版 commit
    fireEvent.click(screen.getByRole("button", { name: "回到 v1" }));
    expect(seed.rollbackMutate).toHaveBeenCalledWith("a1");
  });
});

describe("VersionTimelineSheet · 空态与错误态", () => {
  it("尚无版本：如实出空态（不虚构版本）", () => {
    render(<VersionTimelineSheet projectId="p1" />);
    openTimeline();
    expect(screen.getByText(/尚无版本/)).toBeTruthy();
  });

  it("查询失败：如实走错误态", () => {
    seed.isError = true;
    render(<VersionTimelineSheet projectId="p1" />);
    openTimeline();
    expect(screen.getByText(/版本列表加载失败/)).toBeTruthy();
  });
});
