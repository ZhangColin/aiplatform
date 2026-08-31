// @vitest-environment happy-dom
import { cleanup, render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { PrdNoticesState } from "@/lib/store/prd-notices";

import { PrdPanel } from "./prd-panel";

// PRD 文件呈现（#20 成果区文件模式）：markdown 正文渲染、修订「已更新」标记
// （pending 通知驱动）、挂载兜底登记 markSeen（页面加载已产出项目——SSE 断线
// 漏首产事件时 seen 仍落位，后续修订才出胶囊）。直读种子状态渲染（zustand v5
// server snapshot 限制同 workbench-shell.test）；PRD 读口 mock 掉。
const seed = vi.hoisted(() => ({
  notices: { seen: {}, pending: {} } as Pick<PrdNoticesState, "seen" | "pending">,
  prd: {
    data: undefined as { content?: string; updatedAt?: string } | null | undefined,
    isPending: false,
  },
  markSeen: vi.fn(),
}));

vi.mock("@/hooks/use-prd", () => ({
  usePrd: () => seed.prd,
}));

// 直读种子状态渲染（zustand v5 server snapshot 限制同 workbench-shell.test）；
// markSeen 以 spy 形状进 selector 面（effect 断言目标）
vi.mock("@/lib/store/prd-notices", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/store/prd-notices")>();
  return {
    ...actual,
    usePrdNoticesStore: <T,>(selector: (state: Pick<PrdNoticesState, "seen" | "pending"> & { markSeen: typeof seed.markSeen }) => T): T =>
      selector({ ...seed.notices, markSeen: seed.markSeen }),
  };
});

beforeEach(() => {
  seed.notices = { seen: {}, pending: {} };
  seed.prd = {
    data: { content: "# 需求背景\n给宠物医院做预约管理系统。", updatedAt: "2026-08-31T08:00:00Z" },
    isPending: false,
  };
  seed.markSeen.mockClear();
});

afterEach(() => cleanup());

describe("PrdPanel · PRD 文件呈现（#20）", () => {
  it("markdown 正文渲染（七章节内容直出）+ 文件标识 docs/PRD.md", () => {
    const { container } = render(<PrdPanel projectId="p1" />);

    expect(container.textContent).toContain("给宠物医院做预约管理系统。");
    expect(container.textContent).toContain("docs/PRD.md");
    expect(container.querySelector("h1")?.textContent).toBe("需求背景");
  });

  it("有未认领修订：「已更新」标记；无修订不标", () => {
    seed.notices = { seen: { p1: true }, pending: { p1: 1 } };
    const { container } = render(<PrdPanel projectId="p1" />);
    expect(container.textContent).toContain("已更新");

    seed.notices = { seen: { p1: true }, pending: {} };
    const plain = render(<PrdPanel projectId="p1" />);
    expect(plain.container.textContent).not.toContain("已更新");
  });

  it("PRD 数据落位即 markSeen（挂载兜底登记）；未产出（null）不登记、呈引导占位", () => {
    render(<PrdPanel projectId="p1" />);
    expect(seed.markSeen).toHaveBeenCalledWith("p1");

    seed.prd = { data: null, isPending: false };
    const { container } = render(<PrdPanel projectId="p2" />);
    expect(container.textContent).toContain("整理好的 PRD 会出现在这里");
  });

  it("加载中：骨架占位（无正文闪现）", () => {
    seed.prd = { data: undefined, isPending: true };
    const { container } = render(<PrdPanel projectId="p1" />);
    expect(container.querySelector('[data-slot="skeleton"]')).toBeTruthy();
  });
});
