// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, describe, expect, it, vi } from "vitest";

import { useVersionRecordStore } from "@/lib/store/version-record";

import { DemoVersionChip } from "./demo-version-chip";

// 版本 chip（#333 最小呈现／#626 改接单轴时间线）：数据面 hook mock 掉——
// 呈现形态走 SSR 断言，点开时间线（与顶栏「版本记录」同一 store 契约）走交互断言。
vi.mock("@/hooks/use-version", () => ({
  useGitVersions: vi.fn(),
}));

import { useGitVersions } from "@/hooks/use-version";

function givenVersions(versions: unknown[] | undefined) {
  vi.mocked(useGitVersions).mockReturnValue({ data: versions } as never);
}

afterEach(() => {
  cleanup();
  useVersionRecordStore.setState({ open: false });
});

describe("VersionChip · 版本 chip 呈现（#626 单轴）", () => {
  it("最新版在场：出「版本 vN」（列表最新在前——取首行）", () => {
    givenVersions([
      { commitHash: "c2", versionNo: 2, subject: "把按钮改成蓝色", current: true },
      { commitHash: "a1", versionNo: 1, subject: "做一个预约系统", current: false },
    ]);

    const html = renderToStaticMarkup(<DemoVersionChip projectId="p1" />);

    expect(html).toContain("版本 v2");
    expect(html).not.toContain("版本 v1");
    expect(html).not.toContain("已确认"); // 当前版未确认：无确认缀
  });

  it("当前版已确认：缀「已确认」", () => {
    givenVersions([
      { commitHash: "c2", versionNo: 2, subject: "把按钮改成蓝色", current: true, confirmed: true },
    ]);

    const html = renderToStaticMarkup(<DemoVersionChip projectId="p1" />);

    expect(html).toContain("版本 v2");
    expect(html).toContain("已确认");
  });

  it("从未成版（空列表）/列表未到：不出 chip——诚实优先于占位", () => {
    givenVersions([]);
    expect(renderToStaticMarkup(<DemoVersionChip projectId="p1" />)).toBe("");

    givenVersions(undefined);
    expect(renderToStaticMarkup(<DemoVersionChip projectId="p1" />)).toBe("");
  });
});

describe("VersionChip · 点开版本时间线（#626 单轴入口）", () => {
  it("点 chip → 开时间线（同一 store 契约——与顶栏「版本记录」同一入口）", () => {
    givenVersions([{ commitHash: "c2", versionNo: 2, subject: "把按钮改成蓝色", current: true }]);
    render(<DemoVersionChip projectId="p1" />);

    fireEvent.click(screen.getByRole("button", { name: /查看版本.*v2/ }));

    expect(useVersionRecordStore.getState().open).toBe(true);
  });
});
