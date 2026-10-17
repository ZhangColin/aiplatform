// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, describe, expect, it, vi } from "vitest";

import { useVersionRecordStore } from "@/lib/store/version-record";

import { DemoVersionChip } from "./demo-version-chip";

// Demo 版本 chip（#333 最小呈现／#344 点开版本记录抽屉）：数据面 hook mock
// 掉——呈现形态走 SSR 断言（happy-dom 环境下同样可用），点开抽屉（#344 双挂点
// 之二，与顶栏「版本记录」同一 store 契约）走交互断言。
vi.mock("@/hooks/use-demo-version", () => ({
  useDemoVersions: vi.fn(),
}));

import { useDemoVersions } from "@/hooks/use-demo-version";

function givenVersions(versions: unknown[] | undefined) {
  vi.mocked(useDemoVersions).mockReturnValue({ data: versions } as never);
}

afterEach(() => {
  cleanup();
  useVersionRecordStore.setState({ open: false });
});

describe("DemoVersionChip · 版本 chip 呈现（#333）", () => {
  it("最新版在场：出「Demo vN · 基于 PRD vM 快照」（收口自动成版＋起跑时 PRD 锚）", () => {
    givenVersions([
      { versionNo: 2, prdVersionNo: 1, runId: "r-2", createdAt: "2026-10-17T09:00:00" },
      { versionNo: 1, prdVersionNo: 1, runId: "r-1", createdAt: "2026-10-16T12:00:00" },
    ]);

    const html = renderToStaticMarkup(<DemoVersionChip projectId="p1" />);

    // 清单最新在前——chip 取首行（Demo v2）
    expect(html).toContain("Demo v2");
    expect(html).toContain("基于 PRD v1 快照");
    expect(html).not.toContain("Demo v1");
    expect(html).not.toContain("已确认"); // 最新版未确认：无确认缀
  });

  it("最新版带确认标记：缀「已确认」（#335 确认态在版本可见处可见）", () => {
    givenVersions([
      { versionNo: 2, prdVersionNo: 1, runId: "r-2", confirmedAt: "2026-10-17T10:00:00Z" },
    ]);

    const html = renderToStaticMarkup(<DemoVersionChip projectId="p1" />);

    expect(html).toContain("Demo v2");
    expect(html).toContain("已确认");
  });

  it("无确认版起跑（legacy/设计类）：锚空只出版本号，不虚构 PRD 关联", () => {
    givenVersions([{ versionNo: 1, prdVersionNo: null, runId: "r-1" }]);

    const html = renderToStaticMarkup(<DemoVersionChip projectId="p1" />);

    expect(html).toContain("Demo v1");
    expect(html).not.toContain("基于 PRD");
  });

  it("从未成版（空列表）/列表未到：不出 chip——诚实优先于占位", () => {
    givenVersions([]);
    expect(renderToStaticMarkup(<DemoVersionChip projectId="p1" />)).toBe("");

    givenVersions(undefined);
    expect(renderToStaticMarkup(<DemoVersionChip projectId="p1" />)).toBe("");
  });
});

describe("DemoVersionChip · 点开版本记录抽屉（#344 双挂点之二）", () => {
  it("点 chip → 开抽屉（同一 store 契约——与顶栏「版本记录」同一抽屉）", () => {
    givenVersions([
      { versionNo: 2, prdVersionNo: 1, runId: "r-2", createdAt: "2026-10-17T09:00:00" },
    ]);
    render(<DemoVersionChip projectId="p1" />);

    fireEvent.click(screen.getByRole("button", { name: /Demo v2/ }));

    expect(useVersionRecordStore.getState().open).toBe(true);
  });
});
